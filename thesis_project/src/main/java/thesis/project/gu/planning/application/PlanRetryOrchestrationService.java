package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.metrics.PlanStageMetrics;

import java.util.List;

@Service
public class PlanRetryOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(PlanRetryOrchestrationService.class);
    private static final int MAX_PLAN_RETRY_ATTEMPTS = 1;

    private final PlanRepairPipelineService planRepairPipelineService;
    private final DeterministicPlanRepairService deterministicPlanRepairService;

    public PlanRetryOrchestrationService(
            PlanRepairPipelineService planRepairPipelineService,
            DeterministicPlanRepairService deterministicPlanRepairService
    ) {
        this.planRepairPipelineService = planRepairPipelineService;
        this.deterministicPlanRepairService = deterministicPlanRepairService;
    }

    public PlanDraftResponse validateAndRetry(
            CreatePlanReq req,
            String raw,
            boolean redisHit,
            long totalStartedAt,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            PlanRepairPipelineService.ProcessAttemptResult initialAttempt,
            List<PlanStageMetrics> qualityStages,
            boolean deferCopyPolish,
            Operations operations
    ) throws Exception {
        PlanDraft draft = initialAttempt.draft();
        List<String> validationIssues = initialAttempt.validationIssues();

        if (validationIssues.isEmpty()) {
            return operations.finishSuccessfulAttempt(
                    req,
                    draft,
                    timingSummary,
                    stageSummary,
                    totalStartedAt,
                    "initial/copy-polish",
                    deferCopyPolish,
                    false,
                    false,
                    qualityStages
            );
        }

        log.warn("Initial generated itinerary failed validation. req={}, issues={}, maxRetryAttempts={}, rawPreview={}, rawLength={}",
                req,
                validationIssues,
                MAX_PLAN_RETRY_ATTEMPTS,
                operations.shortRawPreview(raw),
                raw == null ? 0 : raw.length());

        PlanRepairPipelineService.Operations repairOperations = operations.repairOperations();
        PlanDraft localRescue = planRepairPipelineService.localRescueBeforeRetryIfValid(
                req,
                draft,
                validationIssues,
                stageSummary,
                timingSummary,
                qualityStages,
                repairOperations
        );
        if (localRescue != null) {
            log.warn("Initial itinerary accepted with local rescue before AI retry. req={}, originalIssues={}",
                    req,
                    validationIssues);
            return operations.finishSuccessfulAttempt(
                    req,
                    localRescue,
                    timingSummary,
                    stageSummary,
                    totalStartedAt,
                    "initial/copy-polish",
                    deferCopyPolish,
                    false,
                    false,
                    qualityStages
            );
        }

        if (isDuplicateDominatedDeterministicFailure(validationIssues)) {
            PlanRepairPipelineService.DeterministicFallbackResult deterministicEarlyStop = planRepairPipelineService.deterministicRepairIfValid(
                    req,
                    draft,
                    validationIssues,
                    "initial",
                    stageSummary,
                    timingSummary,
                    qualityStages,
                    repairOperations
            );
            if (deterministicEarlyStop != null && deterministicEarlyStop.accepted()) {
                log.warn("Initial itinerary accepted with deterministic duplicate-first fallback. req={}, originalIssues={}",
                        req,
                        validationIssues);
                return operations.finishSuccessfulAttempt(
                        req,
                        deterministicEarlyStop.draft(),
                        timingSummary,
                        stageSummary,
                        totalStartedAt,
                        "initial/copy-polish",
                        deferCopyPolish,
                        false,
                        false,
                        qualityStages
                );
            }
        }

        if (redisHit) {
            operations.evictAiPlanRaw(req);
        }

        long stageStartedAt = System.currentTimeMillis();
        Object retrySkeletonContext = operations.skeletonContext(req, draft);
        String retryRaw = operations.regenerateRetryAttempt(req, draft, validationIssues, retrySkeletonContext);
        operations.appendStageTiming(timingSummary, "retry-1/ai-regenerate", System.currentTimeMillis() - stageStartedAt);

        PlanRepairPipelineService.ProcessAttemptResult retryAttempt = operations.processAttemptWithJsonRecovery(
                req,
                retryRaw,
                "retry-1",
                stageSummary,
                timingSummary,
                qualityStages,
                retrySkeletonContext
        );
        PlanDraft retried = retryAttempt.draft();
        List<String> retryValidationIssues = retryAttempt.validationIssues();

        if (retryValidationIssues.isEmpty()) {
            return operations.finishSuccessfulAttempt(
                    req,
                    retried,
                    timingSummary,
                    stageSummary,
                    totalStartedAt,
                    "retry-1/copy-polish",
                    deferCopyPolish,
                    true,
                    true,
                    qualityStages
            );
        }

        PlanRepairPipelineService.DeterministicFallbackResult deterministicFallback = planRepairPipelineService.deterministicRetryFallbackIfValid(
                req,
                retried,
                retryValidationIssues,
                stageSummary,
                timingSummary,
                qualityStages,
                repairOperations
        );
        if (deterministicFallback != null && deterministicFallback.accepted()) {
            log.warn("Retry itinerary accepted with deterministic fallback. req={}, originalIssues={}", req, retryValidationIssues);
            return operations.finishSuccessfulAttempt(
                    req,
                    deterministicFallback.draft(),
                    timingSummary,
                    stageSummary,
                    totalStartedAt,
                    "retry-1/copy-polish",
                    deferCopyPolish,
                    true,
                    true,
                    qualityStages
            );
        }

        PlanDraft relaxedFallback = planRepairPipelineService.relaxedPaceFallbackIfValid(
                retried,
                req,
                retryValidationIssues,
                repairOperations
        );
        if (relaxedFallback != null) {
            log.warn("Retry itinerary accepted with relaxed pace fallback. req={}, originalIssues={}", req, retryValidationIssues);
            return operations.finishSuccessfulAttempt(
                    req,
                    relaxedFallback,
                    timingSummary,
                    stageSummary,
                    totalStartedAt,
                    "retry-1/copy-polish",
                    deferCopyPolish,
                    true,
                    true,
                    qualityStages
            );
        }

        operations.logPlanStageSummary(stageSummary);
        operations.appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
        operations.logPlanStageTimingSummary(timingSummary);
        List<String> finalRetryIssues = deterministicFallback != null && deterministicFallback.validationIssues() != null
                && !deterministicFallback.validationIssues().isEmpty()
                ? deterministicFallback.validationIssues()
                : retryValidationIssues;
        log.error("Retried generated itinerary failed validation. issues={}, retryRawPreview={}, retryRawLength={}",
                finalRetryIssues,
                operations.shortRawPreview(retryRaw),
                retryRaw == null ? 0 : retryRaw.length());
        if (operations.isRelaxedValidationBenchmarkEnabled() && !hasRequestedDayCountIssue(finalRetryIssues)) {
            log.warn("Accepting retried itinerary despite validation issues because relaxed validation benchmark is enabled. req={}, issues={}",
                    req,
                    finalRetryIssues);
            return operations.finishSuccessfulAttempt(
                    req,
                    retried,
                    timingSummary,
                    stageSummary,
                    totalStartedAt,
                    "retry-1/relaxed-validation-benchmark-copy-polish",
                    deferCopyPolish,
                    true,
                    false,
                    qualityStages
            );
        }
        throw ErrorCode.INTERNAL_ERROR.ex(
                "Retried generated itinerary failed validation: " + finalRetryIssues
        );
    }

    private boolean isDuplicateDominatedDeterministicFailure(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return false;
        }
        int duplicateCount = 0;
        int otherDeterministicCount = 0;
        for (String issue : validationIssues) {
            if (issue == null || issue.isBlank()) {
                return false;
            }
            if (issue.endsWith("-duplicate-poi-across-days") || issue.endsWith("-duplicate-poi-same-day")) {
                duplicateCount++;
                continue;
            }
            if (!deterministicPlanRepairService.isDeterministicRepairIssue(issue)) {
                return false;
            }
            otherDeterministicCount++;
        }
        return duplicateCount > 0 && duplicateCount > otherDeterministicCount;
    }

    private boolean hasRequestedDayCountIssue(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return false;
        }
        return issues.stream().anyMatch(issue -> issue != null
                && (issue.matches("expected-\\d+-days-but-got-\\d+")
                || issue.matches("declared-days-\\d+-does-not-match-request-\\d+")));
    }

    public interface Operations {
        PlanRepairPipelineService.Operations repairOperations();

        PlanDraftResponse finishSuccessfulAttempt(
                CreatePlanReq req,
                PlanDraft verifiedDraft,
                StringBuilder timingSummary,
                StringBuilder stageSummary,
                long totalStartedAt,
                String copyPolishTimingLabel,
                boolean deferCopyPolish,
                boolean retryUsed,
                boolean retryRescued,
                List<PlanStageMetrics> qualityStages
        );

        Object skeletonContext(CreatePlanReq req, PlanDraft draft);

        String regenerateRetryAttempt(
                CreatePlanReq req,
                PlanDraft draft,
                List<String> validationIssues,
                Object retrySkeletonContext
        ) throws Exception;

        PlanRepairPipelineService.ProcessAttemptResult processAttemptWithJsonRecovery(
                CreatePlanReq req,
                String raw,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                List<PlanStageMetrics> qualityStages,
                Object retrySkeletonContext
        ) throws Exception;

        void evictAiPlanRaw(CreatePlanReq req);

        void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs);

        void logPlanStageSummary(StringBuilder stageSummary);

        void logPlanStageTimingSummary(StringBuilder timingSummary);

        String shortRawPreview(String raw);

        boolean isRelaxedValidationBenchmarkEnabled();
    }
}
