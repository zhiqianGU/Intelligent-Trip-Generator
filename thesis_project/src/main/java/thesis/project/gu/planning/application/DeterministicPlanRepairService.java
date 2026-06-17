package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeterministicPlanRepairService {
    private static final int DETERMINISTIC_REPAIR_MAX_PASSES = 3;

    private final DuplicatePoiRepairService duplicatePoiRepairService;
    private final MealTimeWindowRepairService mealTimeWindowRepairService;

    public DeterministicPlanRepairService(
            DuplicatePoiRepairService duplicatePoiRepairService,
            MealTimeWindowRepairService mealTimeWindowRepairService
    ) {
        this.duplicatePoiRepairService = duplicatePoiRepairService;
        this.mealTimeWindowRepairService = mealTimeWindowRepairService;
    }

    public PlanDraftResponse repair(
            PlanDraftResponse draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            Operations operations
    ) {
        PlanDraft repaired = repair(PlanDraft.fromResponse(draft), attemptLabel, stageSummary, timingSummary, operations);
        return repaired == null ? null : repaired.toResponse();
    }

    public PlanDraft repair(
            PlanDraft draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            Operations operations
    ) {
        long stageStartedAt = System.currentTimeMillis();
        PlanDraft repaired = draft;
        for (int pass = 0; pass < DETERMINISTIC_REPAIR_MAX_PASSES; pass++) {
            List<String> currentIssues = collectDeterministicValidationIssues(repaired, operations);
            if (currentIssues.isEmpty()) {
                break;
            }
            String beforeSnapshot = deterministicRepairSnapshot(repaired);
            Set<Integer> changedDayIndexes = new java.util.LinkedHashSet<>();

            if (hasAnyIssue(currentIssues, "-time-sensitive-too-late")) {
                PlanDraft beforeTimeSensitiveRepair = repaired;
                repaired = operations.repairTimeSensitiveLateStops(repaired);
                changedDayIndexes.addAll(operations.detectChangedDayIndexes(beforeTimeSensitiveRepair, repaired));
            }

            if (hasAnyIssue(currentIssues, "-lunch-time-invalid")
                    || hasAnyIssue(currentIssues, "-dinner-time-invalid")
                    || hasAnyIssue(currentIssues, "-dinner-too-early")
                    || hasAnyIssue(currentIssues, "-theme-park-dinner-too-late")) {
                PlanDraft beforeMealTimeRepair = repaired;
                repaired = mealTimeWindowRepairService.repair(repaired);
                changedDayIndexes.addAll(operations.detectChangedDayIndexes(beforeMealTimeRepair, repaired));
            }

            if (hasAnyIssue(currentIssues, "-duplicate-poi-across-days")) {
                PlanDraft beforeDuplicateRepair = repaired;
                repaired = duplicatePoiRepairService.repairCrossDayDuplicatePois(repaired);
                changedDayIndexes.addAll(operations.detectChangedDayIndexes(beforeDuplicateRepair, repaired));
            }

            if (hasAnyIssue(currentIssues, "-duplicate-poi-same-day")) {
                PlanDraft beforeDuplicateRepair = repaired;
                repaired = duplicatePoiRepairService.repairSameDayDuplicatePois(repaired);
                changedDayIndexes.addAll(operations.detectChangedDayIndexes(beforeDuplicateRepair, repaired));
            }

            if (!changedDayIndexes.isEmpty()) {
                repaired = operations.normalizeDraftScheduleWithRouteDurations(repaired, changedDayIndexes);
            }

            List<String> issuesAfterTargetedRepairs = collectDeterministicValidationIssues(repaired, operations);
            if (issuesAfterTargetedRepairs.isEmpty()) {
                break;
            }
            if (hasAnyIssue(issuesAfterTargetedRepairs, "-gap-too-large")) {
                repaired = operations.clampOversizedGaps(repaired);
                repaired = operations.bridgeSmallDeterministicGapOverruns(repaired);
            }
            if (beforeSnapshot.equals(deterministicRepairSnapshot(repaired))) {
                break;
            }
        }
        operations.appendStageTiming(timingSummary, attemptLabel + "/deterministic-repair", System.currentTimeMillis() - stageStartedAt);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "deterministic-repair", repaired);
        return repaired;
    }

    public List<String> collectDeterministicValidationIssues(PlanDraftResponse draft, Operations operations) {
        return collectDeterministicValidationIssues(PlanDraft.fromResponse(draft), operations);
    }

    public List<String> collectDeterministicValidationIssues(PlanDraft draft, Operations operations) {
        return operations.validateDraft(draft).stream()
                .filter(this::isDeterministicRepairIssue)
                .toList();
    }

    public boolean isDeterministicRepairIssue(String issue) {
        if (issue == null) {
            return false;
        }
        return issue.endsWith("-gap-too-large")
                || issue.endsWith("-lunch-time-invalid")
                || issue.endsWith("-dinner-time-invalid")
                || issue.endsWith("-dinner-too-early")
                || issue.endsWith("-theme-park-dinner-too-late")
                || issue.endsWith("-time-sensitive-too-late")
                || issue.endsWith("-duplicate-poi-same-day")
                || issue.endsWith("-duplicate-poi-across-days");
    }

    private boolean hasAnyIssue(List<String> issues, String suffix) {
        if (issues == null || issues.isEmpty() || suffix == null || suffix.isBlank()) {
            return false;
        }
        return issues.stream().anyMatch(issue -> issue != null && issue.endsWith(suffix));
    }

    private String deterministicRepairSnapshot(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return "";
        }
        return draft.daysPlan().stream()
                .filter(Objects::nonNull)
                .map(day -> day.dayIndex() + ":"
                        + (day.stops() == null ? "" : day.stops().stream()
                        .map(stop -> safeStopName(stop) + "@" + nullToEmpty(stop == null ? null : stop.startTime()) + "-" + nullToEmpty(stop == null ? null : stop.endTime()))
                        .collect(Collectors.joining("|"))))
                .collect(Collectors.joining(" || "));
    }

    private String safeStopName(PlanDraft.Place stop) {
        return stop == null || stop.name() == null ? "" : stop.name();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public interface Operations {
        List<String> validateDraft(PlanDraft draft);

        PlanDraft repairTimeSensitiveLateStops(PlanDraft draft);

        PlanDraft clampOversizedGaps(PlanDraft draft);

        PlanDraft bridgeSmallDeterministicGapOverruns(PlanDraft draft);

        PlanDraft normalizeDraftScheduleWithRouteDurations(PlanDraft draft, Set<Integer> targetDayIndexes);

        Set<Integer> detectChangedDayIndexes(PlanDraft before, PlanDraft after);

        void appendStageTiming(StringBuilder timingSummary, String stage, long ms);

        void logPlanStageCounts(StringBuilder stageSummary, String attemptLabel, String stage, PlanDraft draft);
    }
}
