package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.verification.RestaurantVerificationService;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MealRepairService {
    private static final Pattern STOP_ISSUE_PATTERN = Pattern.compile("^day-(\\d+)-stop-(\\d+)-(.+)$");

    private final RestaurantVerificationService restaurantVerificationService;
    private final PlanResponseAssembler planResponseAssembler;

    public MealRepairService(
            RestaurantVerificationService restaurantVerificationService,
            PlanResponseAssembler planResponseAssembler
    ) {
        this.restaurantVerificationService = restaurantVerificationService;
        this.planResponseAssembler = planResponseAssembler;
    }

    public Result verifyAndRepairMeals(
            PlanDraftResponse draft,
            List<String> existingValidationIssues,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            Operations operations
    ) {
        List<String> validationIssues = new ArrayList<>(existingValidationIssues == null ? List.of() : existingValidationIssues);

        long stageStartedAt = System.currentTimeMillis();
        var initialVerification = restaurantVerificationService.verifyAndNormalize(draft);
        draft = initialVerification.draft();
        operations.appendStageTiming(timingSummary, attemptLabel + "/meal-verify-1", System.currentTimeMillis() - stageStartedAt);
        if (initialVerification.issues().isEmpty()) {
            operations.logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            return new Result(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse repairedAfterInitialVerification = repairMealStops(draft, initialVerification.issues(), operations);
        Map<Integer, java.util.Set<Integer>> initialChangedMealTargets = detectChangedFoodStopIndexes(draft, repairedAfterInitialVerification);
        draft = repairedAfterInitialVerification;
        operations.appendStageTiming(timingSummary, attemptLabel + "/meal-repair-1", System.currentTimeMillis() - stageStartedAt);
        if (!hasChangedMealTargets(initialChangedMealTargets)) {
            operations.logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            validationIssues.addAll(initialVerification.issues());
            return new Result(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        var finalVerification = restaurantVerificationService.verifyAndNormalizeSelective(draft, initialChangedMealTargets);
        draft = finalVerification.draft();
        operations.appendStageTiming(timingSummary, attemptLabel + "/meal-verify-2", System.currentTimeMillis() - stageStartedAt);
        if (finalVerification.issues().isEmpty()) {
            operations.logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            return new Result(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse repairedAfterFinalVerification = repairMealStops(draft, finalVerification.issues(), operations);
        Map<Integer, java.util.Set<Integer>> finalChangedMealTargets = detectChangedFoodStopIndexes(draft, repairedAfterFinalVerification);
        draft = repairedAfterFinalVerification;
        operations.appendStageTiming(timingSummary, attemptLabel + "/meal-repair-2", System.currentTimeMillis() - stageStartedAt);
        if (!hasChangedMealTargets(finalChangedMealTargets)) {
            operations.logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            validationIssues.addAll(finalVerification.issues());
            return new Result(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        var settledVerification = restaurantVerificationService.verifyAndNormalizeSelective(draft, finalChangedMealTargets);
        draft = settledVerification.draft();
        operations.appendStageTiming(timingSummary, attemptLabel + "/meal-verify-3", System.currentTimeMillis() - stageStartedAt);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);

        validationIssues.addAll(settledVerification.issues());
        return new Result(draft, validationIssues);
    }

    public PlanDraftResponse repairMealStops(PlanDraftResponse draft, List<String> issues, Operations operations) {
        PlanDraftResponse updated = dropInvalidStrictMealStops(draft, issues);
        updated = restaurantVerificationService.ensureRequiredMeals(updated);
        return operations.normalizeDraftSchedule(updated);
    }

    private PlanDraftResponse dropInvalidStrictMealStops(PlanDraftResponse draft, List<String> issues) {
        if (draft == null || draft.daysPlan() == null || issues == null || issues.isEmpty()) {
            return draft;
        }
        List<DayPlan> updatedDays = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            List<Integer> dropIdx = collectStrictMealIndexesToDrop(day.dayIndex(), issues);
            if (dropIdx.isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> updatedStops = new ArrayList<>();
            for (int i = 0; i < stops.size(); i++) {
                Place stop = stops.get(i);
                if (dropIdx.contains(i) && isStrictMealStop(stop)) {
                    continue;
                }
                updatedStops.add(stop);
            }
            updatedDays.add(new DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(),
                    day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return planResponseAssembler.withDays(draft, updatedDays);
    }

    private Map<Integer, java.util.Set<Integer>> detectChangedFoodStopIndexes(PlanDraftResponse before, PlanDraftResponse after) {
        Map<Integer, java.util.Set<Integer>> changed = new java.util.LinkedHashMap<>();
        if (after == null || after.daysPlan() == null || after.daysPlan().isEmpty()) {
            return changed;
        }
        Map<Integer, DayPlan> beforeByDay = before == null || before.daysPlan() == null
                ? Map.of()
                : before.daysPlan().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        DayPlan::dayIndex,
                        day -> day,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        for (DayPlan afterDay : after.daysPlan()) {
            if (afterDay == null || afterDay.stops() == null || afterDay.stops().isEmpty()) {
                continue;
            }
            DayPlan beforeDay = beforeByDay.get(afterDay.dayIndex());
            List<Place> beforeStops = beforeDay == null || beforeDay.stops() == null ? List.of() : beforeDay.stops();
            for (int i = 0; i < afterDay.stops().size(); i++) {
                Place afterStop = afterDay.stops().get(i);
                if (!isFoodStop(afterStop)) {
                    continue;
                }
                Place beforeStop = i < beforeStops.size() ? beforeStops.get(i) : null;
                if (!sameStopIdentity(beforeStop, afterStop)) {
                    changed.computeIfAbsent(afterDay.dayIndex(), ignored -> new java.util.LinkedHashSet<>()).add(i);
                }
            }
        }
        return changed;
    }

    private boolean hasChangedMealTargets(Map<Integer, java.util.Set<Integer>> changedTargets) {
        if (changedTargets == null || changedTargets.isEmpty()) {
            return false;
        }
        return changedTargets.values().stream().anyMatch(indexes -> indexes != null && !indexes.isEmpty());
    }

    private boolean sameStopIdentity(Place left, Place right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(normalizeSlot(left.name()), normalizeSlot(right.name()))
                && Objects.equals(normalizeSlot(left.addressLine()), normalizeSlot(right.addressLine()))
                && Objects.equals(normalizeSlot(left.googleMapsUri()), normalizeSlot(right.googleMapsUri()))
                && Objects.equals(normalizeSlot(left.mealType()), normalizeSlot(right.mealType()))
                && Objects.equals(normalizeSlot(left.timeSlot()), normalizeSlot(right.timeSlot()));
    }

    private List<Integer> collectStrictMealIndexesToDrop(int dayIndex, List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        String prefix = "day-" + dayIndex + "-stop-";
        for (String issue : issues) {
            if (issue == null || !issue.startsWith(prefix)
                    || !(issue.endsWith("-google-places-low-confidence") || issue.endsWith("-google-places-no-match"))) {
                continue;
            }
            Matcher matcher = STOP_ISSUE_PATTERN.matcher(issue);
            if (matcher.matches()) {
                indexes.add(Integer.parseInt(matcher.group(2)) - 1);
            }
        }
        return indexes;
    }

    private boolean isStrictMealStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String slot = normalizeSlot(stop.timeSlot());
        String mealType = normalizeSlot(stop.mealType());
        String category = normalizeSlot(stop.category());
        return "lunch".equals(slot) || "dinner".equals(slot)
                || "lunch".equals(mealType) || "dinner".equals(mealType)
                || "restaurant".equals(category) || "cafe".equals(category) || "food".equals(category) || "dining".equals(category);
    }

    private boolean isFoodStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category);
    }

    private String normalizeSlot(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).trim();
    }

    public interface Operations {
        PlanDraftResponse normalizeDraftSchedule(PlanDraftResponse draft);

        void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs);

        void logPlanStageCounts(StringBuilder stageSummary, String attemptLabel, String stage, PlanDraftResponse draft);
    }

    public record Result(PlanDraftResponse draft, List<String> validationIssues) {
    }
}
