package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.localfast.LocalPlanGeneratorService;
import thesis.project.gu.planning.quality.LocalPlanQualityDiagnosticService;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;

@Service
public class GenerateInitialPlanUseCase {
    private static final Logger log = LoggerFactory.getLogger(GenerateInitialPlanUseCase.class);

    private final PlanRequestContextBuilder planRequestContextBuilder;
    private final LocalPlanGeneratorService localPlanGeneratorService;
    private final LocalPlanQualityDiagnosticService localPlanQualityDiagnosticService;
    private final AiDraftGenerationService aiDraftGenerationService;

    public GenerateInitialPlanUseCase(
            PlanRequestContextBuilder planRequestContextBuilder,
            LocalPlanGeneratorService localPlanGeneratorService,
            LocalPlanQualityDiagnosticService localPlanQualityDiagnosticService,
            AiDraftGenerationService aiDraftGenerationService
    ) {
        this.planRequestContextBuilder = planRequestContextBuilder;
        this.localPlanGeneratorService = localPlanGeneratorService;
        this.localPlanQualityDiagnosticService = localPlanQualityDiagnosticService;
        this.aiDraftGenerationService = aiDraftGenerationService;
    }

    public PlanDraftResponse execute(
            CreatePlanReq req,
            boolean redisHit,
            boolean deferCopyPolish,
            Operations operations
    ) throws Exception {
        PlanRequestContextBuilder.PlanRequestContext context = planRequestContextBuilder.build(req, redisHit, deferCopyPolish);
        CreatePlanReq normalizedReq = context.request();

        if (context.mode().localFast()) {
            long startedAt = System.currentTimeMillis();
            PlanDraftResponse draft = localPlanGeneratorService.generate(normalizedReq);
            if (context.deferCopyPolish()) {
                draft = operations.withCopyPolishStatus(draft, "deferred");
            }
            LocalPlanQualityReport qualityReport = localPlanQualityDiagnosticService.diagnose(draft);
            if (!qualityReport.warnings().isEmpty()) {
                log.warn("Local fast plan quality score={} errors={} warnings={} details={}",
                        qualityReport.score(),
                        qualityReport.errorCount(),
                        qualityReport.warningCount(),
                        qualityReport.warnings());
            }
            log.info("Local fast plan generation completed city={} requestedDays={} actualDayPlans={} elapsedMs={}",
                    context.city(),
                    context.days(),
                    draft == null || draft.daysPlan() == null ? null : draft.daysPlan().size(),
                    System.currentTimeMillis() - startedAt);
            return draft;
        }

        log.info("Plan generation start city={} requestedDays={} phasedGeneration={} redisHit={}",
                context.city(),
                context.days(),
                context.mode().phasedGeneration(),
                context.redisHit());
        long aiStartedAt = System.currentTimeMillis();
        String raw = aiDraftGenerationService.generateInitialRaw(normalizedReq, context.mode());
        long aiGenerationMs = System.currentTimeMillis() - aiStartedAt;
        return operations.processGeneratedRawDraft(
                normalizedReq,
                raw,
                context.redisHit(),
                aiGenerationMs,
                context.deferCopyPolish()
        );
    }

    public abstract static class Operations {
        abstract PlanDraftResponse withCopyPolishStatus(PlanDraftResponse draft, String status);

        abstract PlanDraftResponse processGeneratedRawDraft(
                CreatePlanReq req,
                String raw,
                boolean redisHit,
                long aiGenerationMs,
                boolean deferCopyPolish
        ) throws Exception;
    }
}
