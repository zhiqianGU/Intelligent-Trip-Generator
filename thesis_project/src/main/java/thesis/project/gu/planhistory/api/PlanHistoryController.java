package thesis.project.gu.planhistory.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.observability.application.RuntimeMetricsService;
import thesis.project.gu.planhistory.application.PlanService;
import thesis.project.gu.planhistory.domain.TripPlanSummary;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanHistoryController {
    private final PlanService planService;
    private final RuntimeMetricsService runtimeMetricsService;

    public PlanHistoryController(PlanService planService, RuntimeMetricsService runtimeMetricsService) {
        this.planService = planService;
        this.runtimeMetricsService = runtimeMetricsService;
    }

    @PatchMapping("/{planId}/favorite")
    public Map<String, Object> updateFavorite(
            @PathVariable long planId,
            @RequestParam boolean value,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        if (userId == null) {
            throw new NavigatorException(ErrorCode.UNAUTHORIZED, "Login required");
        }

        planService.setFavorite(userId, planId, value);
        return Map.of("planId", planId, "favorite", value, "status", "ok");
    }

    @GetMapping("/me")
    public PlanService.Paged<TripPlanSummary> myPlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean favorite,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
            PlanService.Paged<TripPlanSummary> result = planService.listMyPlans(userId, page, size, favorite);
            runtimeMetricsService.recordPlanViewRequest("list", System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanViewRequest("list", System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @GetMapping("/me/favorites")
    public PlanService.Paged<TripPlanSummary> myFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
            PlanService.Paged<TripPlanSummary> result = planService.listMyPlans(userId, page, size, true);
            runtimeMetricsService.recordPlanViewRequest("favorites", System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanViewRequest("favorites", System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @GetMapping("/{planId}")
    public PlanService.PlanDetail myPlanDetail(
            @PathVariable long planId,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
            PlanService.PlanDetail result = planService.getMyPlanDetail(userId, planId);
            runtimeMetricsService.recordPlanViewRequest("detail", System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanViewRequest("detail", System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PatchMapping("/{planId}/title")
    public Map<String, Object> renamePlan(
            @PathVariable long planId,
            @RequestParam(required = false) String title,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
        planService.renamePlan(userId, planId, title);
        return Map.of("planId", planId, "title", title, "status", "ok");
    }

    @DeleteMapping("/{planId}")
    public Map<String, Object> deletePlan(
            @PathVariable long planId,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
        planService.deletePlan(userId, planId);
        return Map.of("planId", planId, "status", "deleted");
    }
}
