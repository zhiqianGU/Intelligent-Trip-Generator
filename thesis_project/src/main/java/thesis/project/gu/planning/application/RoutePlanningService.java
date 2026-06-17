package thesis.project.gu.planning.application;

import thesis.project.gu.planning.domain.PlanDraft;

import java.util.Set;

public interface RoutePlanningService {
    PlanDraft applyRouteAwareScheduling(
            PlanDraft draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    );

    PlanDraft normalizeScheduleWithRouteDurations(PlanDraft draft);

    PlanDraft normalizeScheduleWithRouteDurations(PlanDraft draft, Set<Integer> targetDayIndexes);

    void clearRouteChoiceCrossRequestCache();

    void clearRouteChoiceLocalCacheOnly();
}
