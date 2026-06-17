package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.domain.PlanDraft;

import java.util.List;
import java.util.Map;

@Service
public class DefaultPlanQualityService {
    private final DraftValidationService draftValidationService;
    private final DeterministicPlanRepairService deterministicPlanRepairService;
    private final PostRoutePlanRepairService postRoutePlanRepairService;

    public DefaultPlanQualityService(
            DraftValidationService draftValidationService,
            DeterministicPlanRepairService deterministicPlanRepairService,
            PostRoutePlanRepairService postRoutePlanRepairService
    ) {
        this.draftValidationService = draftValidationService;
        this.deterministicPlanRepairService = deterministicPlanRepairService;
        this.postRoutePlanRepairService = postRoutePlanRepairService;
    }

    public List<String> validate(PlanDraft draft) {
        return draftValidationService.validate(draft);
    }

    public List<String> validate(PlanDraft draft, CreatePlanReq req) {
        return draftValidationService.validate(draft, req);
    }

    public List<String> validate(PlanDraft draft, CreatePlanReq req, Map<Integer, Integer> effectiveMinByDay) {
        return draftValidationService.validate(draft, req, effectiveMinByDay);
    }

    public boolean isDeterministicRepairIssue(String issue) {
        return deterministicPlanRepairService.isDeterministicRepairIssue(issue);
    }

    public PlanDraft repairDeterministically(
            PlanDraft draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            DeterministicPlanRepairService.Operations operations
    ) {
        return deterministicPlanRepairService.repair(draft, attemptLabel, stageSummary, timingSummary, operations);
    }

    public List<String> collectDeterministicValidationIssues(
            PlanDraft draft,
            DeterministicPlanRepairService.Operations operations
    ) {
        return deterministicPlanRepairService.collectDeterministicValidationIssues(draft, operations);
    }

    public PlanDraft repairPostRoute(
            PlanDraft draft,
            CreatePlanReq req,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            PostRoutePlanRepairService.Operations operations
    ) {
        return postRoutePlanRepairService.repair(draft, req, attemptLabel, stageSummary, timingSummary, operations);
    }

    public PlanDraft pruneFlexibleFoodStops(PlanDraft draft) {
        return postRoutePlanRepairService.pruneFlexibleFoodStops(draft);
    }

    public PlanDraft pruneUnselectedShoppingStops(PlanDraft draft, CreatePlanReq req) {
        return postRoutePlanRepairService.pruneUnselectedShoppingStops(draft, req);
    }

    public PlanDraft pruneAreaInconsistentFlexibleStops(PlanDraft draft, CreatePlanReq req) {
        return postRoutePlanRepairService.pruneAreaInconsistentFlexibleStops(draft, req);
    }

    public PlanDraft pruneExcessNonMealStops(PlanDraft draft, CreatePlanReq req) {
        return postRoutePlanRepairService.pruneExcessNonMealStops(draft, req);
    }
}
