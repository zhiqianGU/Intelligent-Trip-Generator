package thesis.project.gu.catalog.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlanningZoneRetrievalResult(
        List<ScoredZone> semanticCandidates,
        List<ScoredZone> feasibilityFallbackCandidates
) {
    public PlanningZoneRetrievalResult {
        semanticCandidates = sanitizeCandidates(semanticCandidates);
        feasibilityFallbackCandidates = sanitizeCandidates(feasibilityFallbackCandidates);
    }

    public List<PlanningZoneSummary> orderedZones() {
        Map<String, PlanningZoneSummary> ordered = new LinkedHashMap<>();
        for (ScoredZone candidate : semanticCandidates) {
            ordered.put(candidate.zone().zoneId(), candidate.zone());
        }
        for (ScoredZone candidate : feasibilityFallbackCandidates) {
            ordered.putIfAbsent(candidate.zone().zoneId(), candidate.zone());
        }
        return List.copyOf(ordered.values());
    }

    private static List<ScoredZone> sanitizeCandidates(List<ScoredZone> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.zone() != null && candidate.zone().zoneId() != null)
                .toList();
    }

    public record ScoredZone(
            PlanningZoneSummary zone,
            int score,
            List<String> reasons
    ) {
        public ScoredZone {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
