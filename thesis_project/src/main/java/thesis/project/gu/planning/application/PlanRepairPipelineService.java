package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.metrics.PlanStageMetrics;
import thesis.project.gu.catalog.verification.RestaurantVerificationService;

import java.util.List;

@Service
public class PlanRepairPipelineService {
    private final RestaurantVerificationService restaurantVerificationService;

    public PlanRepairPipelineService(RestaurantVerificationService restaurantVerificationService) {
        this.restaurantVerificationService = restaurantVerificationService;
    }

    public ProcessAttemptResult processAttempt(
            CreatePlanReq req,
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages,
            Operations operations
    ) throws Exception {
        ParseNormalizeResult parsed = operations.parseAndNormalize(raw, attemptLabel, stageSummary, timingSummary);
        qualityStages.add(operations.captureStageMetrics(attemptLabel + "/raw_parsed", parsed.draft(), req, null));

        EntityVerificationResult verified = operations.verifyAndRepairEntities(parsed.draft(), attemptLabel, stageSummary, timingSummary);
        PlanDraftResponse draft = verified.draft();
        List<String> validationIssues = verified.validationIssues();
        qualityStages.add(operations.captureStageMetrics(attemptLabel + "/entity_verified", draft, req, validationIssues));

        draft = operations.applySemanticPruning(draft, req, attemptLabel, stageSummary, timingSummary);

        long stageStartedAt = System.currentTimeMillis();
        draft = restaurantVerificationService.ensureRequiredMeals(draft);
        draft = restaurantVerificationService.verifyAndNormalize(draft).draft();
        operations.appendStageTiming(timingSummary, attemptLabel + "/ensure-required-meals", System.currentTimeMillis() - stageStartedAt);

        draft = operations.applyThemeParkGovernance(draft, req, attemptLabel, stageSummary, timingSummary);
        draft = operations.applyRouteAwareScheduling(draft, attemptLabel, stageSummary, timingSummary);
        draft = operations.applyPostRouteRepair(draft, req, attemptLabel, stageSummary, timingSummary);
        qualityStages.add(operations.captureStageMetrics(attemptLabel + "/post_route_repaired", draft, req, validationIssues));

        validationIssues.addAll(operations.validateRepairedDraft(draft, req, attemptLabel, timingSummary));

        return new ProcessAttemptResult(draft, validationIssues);
    }

    public PlanDraftResponse localRescueBeforeRetryIfValid(
            CreatePlanReq req,
            PlanDraftResponse draft,
            List<String> validationIssues,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages,
            Operations operations
    ) {
        return operations.localRescueBeforeRetryIfValid(req, draft, validationIssues, stageSummary, timingSummary, qualityStages);
    }

    public DeterministicFallbackResult deterministicRepairIfValid(
            CreatePlanReq req,
            PlanDraftResponse draft,
            List<String> validationIssues,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages,
            Operations operations
    ) {
        return operations.deterministicRepairIfValid(req, draft, validationIssues, attemptLabel, stageSummary, timingSummary, qualityStages);
    }

    public DeterministicFallbackResult deterministicRetryFallbackIfValid(
            CreatePlanReq req,
            PlanDraftResponse draft,
            List<String> validationIssues,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages,
            Operations operations
    ) {
        return operations.deterministicRetryFallbackIfValid(req, draft, validationIssues, stageSummary, timingSummary, qualityStages);
    }

    public PlanDraftResponse relaxedPaceFallbackIfValid(
            PlanDraftResponse draft,
            CreatePlanReq req,
            List<String> validationIssues,
            Operations operations
    ) {
        return operations.relaxedPaceFallbackIfValid(draft, req, validationIssues);
    }

    public abstract static class Operations {
        abstract ParseNormalizeResult parseAndNormalize(
                String raw,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary
        ) throws Exception;

        abstract EntityVerificationResult verifyAndRepairEntities(
                PlanDraftResponse draft,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary
        );

        abstract PlanDraftResponse applySemanticPruning(
                PlanDraftResponse draft,
                CreatePlanReq req,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary
        );

        abstract PlanDraftResponse applyThemeParkGovernance(
                PlanDraftResponse draft,
                CreatePlanReq req,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary
        );

        abstract PlanDraftResponse applyRouteAwareScheduling(
                PlanDraftResponse draft,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary
        );

        abstract PlanDraftResponse applyPostRouteRepair(
                PlanDraftResponse draft,
                CreatePlanReq req,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary
        );

        abstract List<String> validateRepairedDraft(
                PlanDraftResponse draft,
                CreatePlanReq req,
                String attemptLabel,
                StringBuilder timingSummary
        );

        abstract PlanStageMetrics captureStageMetrics(
                String stage,
                PlanDraftResponse draft,
                CreatePlanReq req,
                List<String> issues
        );

        abstract void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs);

        abstract PlanDraftResponse localRescueBeforeRetryIfValid(
                CreatePlanReq req,
                PlanDraftResponse draft,
                List<String> validationIssues,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                List<PlanStageMetrics> qualityStages
        );

        abstract DeterministicFallbackResult deterministicRepairIfValid(
                CreatePlanReq req,
                PlanDraftResponse draft,
                List<String> validationIssues,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                List<PlanStageMetrics> qualityStages
        );

        abstract DeterministicFallbackResult deterministicRetryFallbackIfValid(
                CreatePlanReq req,
                PlanDraftResponse draft,
                List<String> validationIssues,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                List<PlanStageMetrics> qualityStages
        );

        abstract PlanDraftResponse relaxedPaceFallbackIfValid(
                PlanDraftResponse draft,
                CreatePlanReq req,
                List<String> validationIssues
        );
    }

    public record ProcessAttemptResult(PlanDraftResponse draft, List<String> validationIssues) {
    }

    public record ParseNormalizeResult(PlanDraftResponse draft) {
    }

    public record EntityVerificationResult(PlanDraftResponse draft, List<String> validationIssues) {
    }

    public record DeterministicFallbackResult(
            PlanDraftResponse draft,
            List<String> validationIssues,
            boolean accepted
    ) {
    }
}
