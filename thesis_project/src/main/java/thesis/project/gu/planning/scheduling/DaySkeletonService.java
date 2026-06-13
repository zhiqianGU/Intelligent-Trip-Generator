package thesis.project.gu.planning.scheduling;

import io.micrometer.common.lang.Nullable;
import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DaySkeletonService {

    public DaySkeletonBatch build(@Nullable CreatePlanReq req, @Nullable PlanDraftResponse draft) {
        int requestedDays = resolveRequestedDays(req, draft);
        String pace = normalizePace(req == null ? null : req.pace());
        NonMealRange targetRange = nonMealRangeForPace(pace);
        List<String> areaCandidates = primaryAreaCandidates(req);
        List<DaySkeleton> skeletons = new ArrayList<>();
        for (int dayIndex = 1; dayIndex <= Math.max(0, requestedDays); dayIndex++) {
            DayPlan day = resolveDay(draft, dayIndex);
            skeletons.add(buildDaySkeleton(dayIndex, req, day, targetRange, areaCandidates));
        }
        return new DaySkeletonBatch(pace, requestedDays, skeletons);
    }

    public String toPromptHints(@Nullable DaySkeletonBatch batch) {
        if (batch == null || batch.skeletons() == null || batch.skeletons().isEmpty()) {
            return "";
        }
        return batch.skeletons().stream()
                .map(s -> {
                    String primaryArea = s.primaryArea() == null || s.primaryArea().isBlank() ? "same-cluster" : s.primaryArea();
                    String issue = s.capacityIssueCode() == null || s.capacityIssueCode().isBlank()
                            ? ""
                            : ",capacityIssue=" + s.capacityIssueCode();
                    return "day-" + s.dayIndex()
                            + "{primaryArea=" + primaryArea
                            + ",effectiveNonMeal=" + s.effectiveMinNonMealStops() + "-" + s.effectiveMaxNonMealStops()
                            + issue
                            + "}";
                })
                .collect(Collectors.joining("; "));
    }

    public Map<Integer, Integer> effectiveMinByDay(@Nullable DaySkeletonBatch batch, int fallbackMin) {
        if (batch == null || batch.skeletons() == null || batch.skeletons().isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (DaySkeleton skeleton : batch.skeletons()) {
            if (skeleton == null) {
                continue;
            }
            int effectiveMin = skeleton.effectiveMinNonMealStops() > 0 ? skeleton.effectiveMinNonMealStops() : fallbackMin;
            values.put(skeleton.dayIndex(), effectiveMin);
        }
        return values;
    }

    public String normalizePace(@Nullable String pace) {
        String normalized = pace == null ? "" : pace.trim().toLowerCase(Locale.ROOT).replace("_", "-").replace(" ", "-");
        return switch (normalized) {
            case "relaxed", "relax", "slow" -> "relaxed";
            case "rush", "fast", "fast-pace", "fastpaced", "intense" -> "rush";
            default -> "normal";
        };
    }

    public NonMealRange nonMealRangeForPace(@Nullable String pace) {
        return switch (normalizePace(pace)) {
            case "relaxed" -> new NonMealRange(2, 3);
            case "rush" -> new NonMealRange(4, 5);
            default -> new NonMealRange(3, 4);
        };
    }

    private int resolveRequestedDays(@Nullable CreatePlanReq req, @Nullable PlanDraftResponse draft) {
        if (req != null && req.days() > 0) {
            return req.days();
        }
        return draft == null || draft.daysPlan() == null ? 0 : draft.daysPlan().size();
    }

    private DayPlan resolveDay(@Nullable PlanDraftResponse draft, int dayIndex) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return null;
        }
        for (DayPlan day : draft.daysPlan()) {
            if (day != null && day.dayIndex() == dayIndex) {
                return day;
            }
        }
        int candidateIndex = dayIndex - 1;
        if (candidateIndex >= 0 && candidateIndex < draft.daysPlan().size()) {
            return draft.daysPlan().get(candidateIndex);
        }
        return null;
    }

    private DaySkeleton buildDaySkeleton(
            int dayIndex,
            @Nullable CreatePlanReq req,
            @Nullable DayPlan day,
            NonMealRange targetRange,
            List<String> areaCandidates
    ) {
        List<Place> stops = day == null || day.stops() == null ? List.of() : day.stops();
        Map<String, Integer> areaCounter = new LinkedHashMap<>();
        for (Place stop : stops) {
            if (!isCountedNonMealStop(stop)) {
                continue;
            }
            String area = areaLabel(stop);
            if (!area.isBlank()) {
                areaCounter.merge(area, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sortedAreas = areaCounter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .toList();
        String seedPrimaryArea = areaCandidates.get((dayIndex - 1) % areaCandidates.size());
        String primaryArea = sortedAreas.isEmpty() ? seedPrimaryArea : sortedAreas.getFirst().getKey();
        String secondaryArea = sortedAreas.size() < 2 ? "" : sortedAreas.get(1).getKey();
        if (isThemeParkPreferred(req) && resolveRequestedDays(req, null) > 1 && dayIndex == resolveRequestedDays(req, null)) {
            primaryArea = "remote theme-park cluster";
        }
        int estimatedCapacity = estimateCapacity(stops, areaCounter, primaryArea, secondaryArea, targetRange);
        NonMealRange effectiveRange = resolveEffectiveRange(targetRange, estimatedCapacity);
        String issueCode = effectiveRange.min() < targetRange.min() ? "insufficient_skeleton_capacity" : "";
        return new DaySkeleton(
                dayIndex,
                primaryArea,
                secondaryArea,
                estimatedCapacity,
                targetRange.min(),
                targetRange.max(),
                effectiveRange.min(),
                effectiveRange.max(),
                issueCode
        );
    }

    private int estimateCapacity(
            List<Place> stops,
            Map<String, Integer> areaCounter,
            String primaryArea,
            String secondaryArea,
            NonMealRange targetRange
    ) {
        int counted = countNonMealStops(stops);
        if (counted <= 0) {
            return targetRange.max();
        }
        if (areaCounter.isEmpty()) {
            return counted;
        }
        int capacity = 0;
        if (primaryArea != null && !primaryArea.isBlank()) {
            capacity += areaCounter.getOrDefault(primaryArea, 0);
        }
        if (secondaryArea != null && !secondaryArea.isBlank()) {
            capacity += areaCounter.getOrDefault(secondaryArea, 0);
        }
        return capacity > 0 ? capacity : counted;
    }

    private NonMealRange resolveEffectiveRange(NonMealRange targetRange, int estimatedCapacity) {
        if (estimatedCapacity <= 0) {
            return new NonMealRange(1, 2);
        }
        List<NonMealRange> ladder = new ArrayList<>();
        if (targetRange.min() >= 4) {
            ladder.add(new NonMealRange(4, 5));
            ladder.add(new NonMealRange(3, 4));
            ladder.add(new NonMealRange(2, 3));
        } else if (targetRange.min() == 3) {
            ladder.add(new NonMealRange(3, 4));
            ladder.add(new NonMealRange(2, 3));
        } else {
            ladder.add(new NonMealRange(2, 3));
        }
        ladder.add(new NonMealRange(1, 2));
        for (NonMealRange candidate : ladder) {
            if (estimatedCapacity >= candidate.min()) {
                return candidate;
            }
        }
        return new NonMealRange(1, 2);
    }

    private int countNonMealStops(List<Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Place stop : stops) {
            if (isCountedNonMealStop(stop)) {
                count++;
            }
        }
        return count;
    }

    private boolean isCountedNonMealStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalize(stop.category());
        if ("hotel".equals(category)) {
            return false;
        }
        return !isMealCategory(category);
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "bar".equals(category)
                || "bakery".equals(category);
    }

    private String areaLabel(Place stop) {
        if (stop == null) {
            return "";
        }
        String preferredArea = normalize(stop.preferredArea());
        if (!preferredArea.isBlank()) {
            return preferredArea;
        }
        String suburb = normalize(stop.suburb());
        if (!suburb.isBlank()) {
            return suburb;
        }
        return normalize(stop.city());
    }

    private List<String> primaryAreaCandidates(@Nullable CreatePlanReq req) {
        List<String> areas = new ArrayList<>();
        String city = req == null || req.city() == null ? "" : req.city().trim().toLowerCase(Locale.ROOT);
        switch (city) {
            case "melbourne" -> {
                areas.add("melbourne cbd");
                areas.add("southbank");
                areas.add("fitzroy/carlton");
            }
            case "sydney" -> {
                areas.add("sydney cbd");
                areas.add("circular quay/the rocks");
                areas.add("darling harbour");
            }
            case "brisbane" -> {
                areas.add("brisbane cbd");
                areas.add("south bank");
                areas.add("fortitude valley/new farm");
            }
            default -> {
                areas.add("city center");
                areas.add("riverfront cultural district");
                areas.add("inner neighborhood cluster");
            }
        }
        Set<String> styles = normalizedStyles(req);
        if (styles.contains("nature")) {
            areas.add("nearby park/riverfront cluster");
        }
        if (styles.contains("culture")) {
            areas.add("museum/gallery precinct");
        }
        if (styles.contains("market_shopping")) {
            areas.add("market/shopping precinct");
        }
        return areas;
    }

    private boolean isThemeParkPreferred(@Nullable CreatePlanReq req) {
        return normalizedStyles(req).contains("theme_park");
    }

    private Set<String> normalizedStyles(@Nullable CreatePlanReq req) {
        Set<String> styles = new HashSet<>();
        if (req == null || req.style() == null) {
            return styles;
        }
        for (String style : req.style()) {
            if (style == null || style.isBlank()) {
                continue;
            }
            styles.add(style.trim().toLowerCase(Locale.ROOT));
        }
        return styles;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    public record NonMealRange(int min, int max) {}

    public record DaySkeletonBatch(String paceBand, int requestedDays, List<DaySkeleton> skeletons) {}

    public record DaySkeleton(
            int dayIndex,
            String primaryArea,
            String nearbySecondaryArea,
            int estimatedNonMealCapacity,
            int targetMinNonMealStops,
            int targetMaxNonMealStops,
            int effectiveMinNonMealStops,
            int effectiveMaxNonMealStops,
            String capacityIssueCode
    ) {}
}
