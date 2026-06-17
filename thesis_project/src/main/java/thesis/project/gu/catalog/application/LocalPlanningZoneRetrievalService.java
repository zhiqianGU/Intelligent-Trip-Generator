package thesis.project.gu.catalog.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.domain.ZoneCapabilitySummary;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class LocalPlanningZoneRetrievalService implements PlanningZoneRetrievalService {
    private static final int SEMANTIC_CANDIDATE_LIMIT = 8;
    private static final int FALLBACK_CANDIDATE_LIMIT = 5;

    @Override
    public PlanningZoneRetrievalResult retrieve(
            PlanningZoneRetrievalQuery query,
            List<PlanningZoneSummary> availableZones
    ) {
        List<PlanningZoneSummary> filteredZones = availableZones == null ? List.of() : availableZones.stream()
                .filter(zone -> zone != null && zone.zoneId() != null && zone.capabilities() != null)
                .filter(zone -> destinationMatches(query, zone))
                .filter(zone -> supportsMinimumAllocation(query, zone))
                .toList();

        List<PlanningZoneRetrievalResult.ScoredZone> scoredSemanticCandidates = filteredZones.stream()
                .map(zone -> scoreSemanticCandidate(query, zone))
                .sorted(Comparator
                        .comparingInt(PlanningZoneRetrievalResult.ScoredZone::score).reversed()
                        .thenComparing(candidate -> candidate.zone().name()))
                .toList();

        List<PlanningZoneRetrievalResult.ScoredZone> semanticCandidates = scoredSemanticCandidates.stream()
                .filter(candidate -> !hasPreference(query) || hasSemanticReason(candidate))
                .limit(SEMANTIC_CANDIDATE_LIMIT)
                .toList();
        boolean useFeasibilityAsPrimary = !hasPreference(query) && semanticCandidates.isEmpty() && !scoredSemanticCandidates.isEmpty();
        if (useFeasibilityAsPrimary) {
            semanticCandidates = scoredSemanticCandidates.stream().limit(SEMANTIC_CANDIDATE_LIMIT).toList();
        }

        Set<String> semanticZoneIds = semanticCandidates.stream()
                .map(candidate -> candidate.zone().zoneId())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        List<PlanningZoneRetrievalResult.ScoredZone> fallbackCandidates = filteredZones.stream()
                .filter(zone -> !semanticZoneIds.contains(zone.zoneId()))
                .map(zone -> scoreFeasibilityFallback(query, zone))
                .sorted(Comparator
                        .comparingInt(PlanningZoneRetrievalResult.ScoredZone::score).reversed()
                        .thenComparing(candidate -> candidate.zone().name()))
                .limit(FALLBACK_CANDIDATE_LIMIT)
                .toList();

        return new PlanningZoneRetrievalResult(semanticCandidates, fallbackCandidates);
    }

    private boolean hasPreference(PlanningZoneRetrievalQuery query) {
        return query != null && query.preferredStyles() != null && !query.preferredStyles().isEmpty();
    }

    private boolean hasSemanticReason(PlanningZoneRetrievalResult.ScoredZone candidate) {
        return candidate.reasons() != null
                && (candidate.reasons().contains("style-match") || candidate.reasons().contains("theme-match"));
    }

    private PlanningZoneRetrievalResult.ScoredZone scoreSemanticCandidate(
            PlanningZoneRetrievalQuery query,
            PlanningZoneSummary zone
    ) {
        List<String> reasons = new ArrayList<>();
        int score = feasibilityScore(query, zone, reasons);
        int styleScore = styleTagMatchScore(query, zone);
        if (styleScore > 0) {
            reasons.add("style-match");
        }
        int themeScore = themeMatchScore(query, zone);
        if (themeScore > 0) {
            reasons.add("theme-match");
        }
        score += styleScore + themeScore;
        return new PlanningZoneRetrievalResult.ScoredZone(zone, score, List.copyOf(reasons));
    }

    private PlanningZoneRetrievalResult.ScoredZone scoreFeasibilityFallback(
            PlanningZoneRetrievalQuery query,
            PlanningZoneSummary zone
    ) {
        List<String> reasons = new ArrayList<>();
        int score = feasibilityScore(query, zone, reasons);
        return new PlanningZoneRetrievalResult.ScoredZone(zone, score, List.copyOf(reasons));
    }

    private int feasibilityScore(
            PlanningZoneRetrievalQuery query,
            PlanningZoneSummary zone,
            List<String> reasons
    ) {
        ZoneCapabilitySummary capabilities = zone.capabilities();
        int score = 0;
        if (capabilities.attractionCount() > 0) {
            score += Math.min(50, capabilities.attractionCount() * 5);
            reasons.add("activity-capacity");
        }
        if (capabilities.lunchRestaurantCount() > 0 && capabilities.dinnerRestaurantCount() > 0) {
            score += 35;
            reasons.add("meal-support");
        } else if (capabilities.lunchRestaurantCount() > 0 || capabilities.dinnerRestaurantCount() > 0) {
            score += 15;
            reasons.add("partial-meal-support");
        }
        int capacity = "relaxed".equals(normalize(query == null ? null : query.pace()))
                ? capabilities.relaxedDayCapacity()
                : capabilities.normalDayCapacity();
        if (capacity > 0) {
            score += Math.min(30, capacity * 10);
            reasons.add("day-capacity");
        }
        if (query != null && Boolean.TRUE.equals(detectedHint(query, "familyFriendly")) && capabilities.familyFriendlyCount() > 0) {
            score += Math.min(20, capabilities.familyFriendlyCount() * 4);
            reasons.add("family-support");
        }
        if (query != null && Boolean.TRUE.equals(detectedHint(query, "preferIndoorWhenRaining")) && indoorCandidateCount(capabilities) > 0) {
            score += Math.min(20, indoorCandidateCount(capabilities) * 5);
            reasons.add("indoor-support");
        }
        return score;
    }

    private Object detectedHint(PlanningZoneRetrievalQuery query, String key) {
        return query.detectedHints() == null ? null : query.detectedHints().get(key);
    }

    private int styleTagMatchScore(PlanningZoneRetrievalQuery query, PlanningZoneSummary zone) {
        if (query == null || query.preferredStyles() == null || query.preferredStyles().isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String style : query.preferredStyles()) {
            String normalizedStyle = normalize(style);
            if (zone.capabilities().styleTagCounts().containsKey(normalizedStyle)) {
                score += 20 + Math.min(20, zone.capabilities().styleTagCounts().get(normalizedStyle) * 3);
            }
        }
        return score;
    }

    private int themeMatchScore(PlanningZoneRetrievalQuery query, PlanningZoneSummary zone) {
        if (query == null || query.preferredStyles() == null || zone.themes() == null) {
            return 0;
        }
        Set<String> themes = zone.themes().stream()
                .map(this::normalize)
                .collect(java.util.stream.Collectors.toSet());
        int score = 0;
        for (String style : query.preferredStyles()) {
            if (themes.contains(normalize(style))) {
                score += 15;
            }
        }
        return score;
    }

    private boolean destinationMatches(PlanningZoneRetrievalQuery query, PlanningZoneSummary zone) {
        if (query == null || query.destinationCity() == null || query.destinationCity().isBlank()) {
            return true;
        }
        String queryCity = normalize(query.destinationCity());
        String zoneCity = normalize(zone.destinationCity());
        return queryCity.isBlank() || zoneCity.isBlank() || queryCity.equals(zoneCity);
    }

    private boolean supportsMinimumAllocation(PlanningZoneRetrievalQuery query, PlanningZoneSummary zone) {
        int attractionCount = zone.capabilities().attractionCount();
        if (attractionCount <= 0) {
            return false;
        }
        if (query == null || query.minimumAllocation() == null) {
            return true;
        }
        return switch (normalizeAllocation(query.minimumAllocation())) {
            case "HALF_DAY" -> attractionCount >= 1;
            case "FULL_DAY" -> attractionCount >= fullDayActivityRequirement(query);
            case "FULL_OR_HALF_DAY" -> attractionCount >= Math.min(2, fullDayActivityRequirement(query));
            default -> attractionCount >= 1;
        };
    }

    private int fullDayActivityRequirement(PlanningZoneRetrievalQuery query) {
        return switch (normalize(query == null ? null : query.pace())) {
            case "relaxed", "relax", "slow" -> 2;
            case "rush", "fast", "intense" -> 4;
            default -> 3;
        };
    }

    private String normalizeAllocation(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private int indoorCandidateCount(ZoneCapabilitySummary capabilities) {
        return countCategory(capabilities, "museum")
                + countCategory(capabilities, "gallery")
                + countCategory(capabilities, "art-gallery")
                + countCategory(capabilities, "shopping")
                + countCategory(capabilities, "market-shopping");
    }

    private int countCategory(ZoneCapabilitySummary capabilities, String category) {
        return capabilities.categoryCounts().getOrDefault(normalize(category), 0);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
    }
}
