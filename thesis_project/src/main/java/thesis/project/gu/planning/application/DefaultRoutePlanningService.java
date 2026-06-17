package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.domain.PlanDraft;

import java.util.Set;

@Service
public class DefaultRoutePlanningService implements RoutePlanningService {
    private final RouteAwareScheduleRepairService routeAwareScheduleRepairService;

    public DefaultRoutePlanningService(RouteAwareScheduleRepairService routeAwareScheduleRepairService) {
        this.routeAwareScheduleRepairService = routeAwareScheduleRepairService;
    }

    @Override
    public PlanDraft applyRouteAwareScheduling(
            PlanDraft draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        return routeAwareScheduleRepairService.applyRouteAwareScheduling(draft, attemptLabel, stageSummary, timingSummary);
    }

    @Override
    public PlanDraft normalizeScheduleWithRouteDurations(PlanDraft draft) {
        return routeAwareScheduleRepairService.normalizeDraftScheduleWithRouteDurations(draft);
    }

    @Override
    public PlanDraft normalizeScheduleWithRouteDurations(PlanDraft draft, Set<Integer> targetDayIndexes) {
        return routeAwareScheduleRepairService.normalizeDraftScheduleWithRouteDurations(draft, targetDayIndexes);
    }

    @Override
    public void clearRouteChoiceCrossRequestCache() {
        routeAwareScheduleRepairService.clearRouteChoiceCrossRequestCache();
    }

    @Override
    public void clearRouteChoiceLocalCacheOnly() {
        routeAwareScheduleRepairService.clearRouteChoiceLocalCacheOnly();
    }
}
