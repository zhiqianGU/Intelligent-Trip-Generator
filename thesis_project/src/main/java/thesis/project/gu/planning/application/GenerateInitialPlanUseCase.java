package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.application.DestinationCatalog;
import thesis.project.gu.catalog.application.DestinationResolver;
import thesis.project.gu.catalog.application.PlanningZoneRetrievalService;
import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.metrics.PlanStageMetrics;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;
import thesis.project.gu.planning.quality.PlanQualityService;

import java.util.ArrayList;
import java.util.List;

@Service
public class GenerateInitialPlanUseCase {
    private static final Logger log = LoggerFactory.getLogger(GenerateInitialPlanUseCase.class);

    private final PlanRequestContextBuilder planRequestContextBuilder;
    private final LightweightRequestPreParser lightweightRequestPreParser;
    private final RetrievalQueryBuilder retrievalQueryBuilder;
    private final DestinationResolver destinationResolver;
    private final PlanningZoneRetrievalService planningZoneRetrievalService;
    private final DestinationCatalog destinationCatalog;
    private final ItineraryGenerator itineraryGenerator;
    private final PlanQualityService planQualityService;
    private final RoutePlanningService routePlanningService;
    private final DefaultPlanQualityService defaultPlanQualityService;
    private final AiDraftGenerationService aiDraftGenerationService;

    public GenerateInitialPlanUseCase(
            PlanRequestContextBuilder planRequestContextBuilder,
            LightweightRequestPreParser lightweightRequestPreParser,
            RetrievalQueryBuilder retrievalQueryBuilder,
            DestinationResolver destinationResolver,
            PlanningZoneRetrievalService planningZoneRetrievalService,
            DestinationCatalog destinationCatalog,
            ItineraryGenerator itineraryGenerator,
            PlanQualityService planQualityService,
            RoutePlanningService routePlanningService,
            DefaultPlanQualityService defaultPlanQualityService,
            AiDraftGenerationService aiDraftGenerationService
    ) {
        this.planRequestContextBuilder = planRequestContextBuilder;
        this.lightweightRequestPreParser = lightweightRequestPreParser;
        this.retrievalQueryBuilder = retrievalQueryBuilder;
        this.destinationResolver = destinationResolver;
        this.planningZoneRetrievalService = planningZoneRetrievalService;
        this.destinationCatalog = destinationCatalog;
        this.itineraryGenerator = itineraryGenerator;
        this.planQualityService = planQualityService;
        this.routePlanningService = routePlanningService;
        this.defaultPlanQualityService = defaultPlanQualityService;
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
            ParsedPlanningRequest parsedRequest = lightweightRequestPreParser.parse(normalizedReq);
            TripPlanningSpecification baseSpecification = TripPlanningSpecification.fromRequest(normalizedReq);
            Destination resolvedDestination = destinationResolver.resolve(parsedRequest.destinationCandidate());
            TripPlanningSpecification specification = baseSpecification.withResolvedDestination(resolvedDestination);
            PlanningZoneRetrievalQuery retrievalQuery = retrievalQueryBuilder.build(parsedRequest, resolvedDestination, specification);
            List<PlanningZoneSummary> availableZones = destinationCatalog.findAvailableZones(specification);
            PlanningZoneRetrievalResult retrievalResult = planningZoneRetrievalService.retrieve(retrievalQuery, availableZones);
            List<PlanningZoneSummary> zones = retrievalResult.orderedZones().isEmpty()
                    ? availableZones
                    : retrievalResult.orderedZones();
            TripSkeleton skeleton = destinationCatalog.buildTripSkeleton(specification, zones);
            PlaceCandidatePool candidatePool = destinationCatalog.buildCandidatePool(specification);
            CoverageResult coverage = destinationCatalog.checkCoverage(specification, skeleton, candidatePool);
            if (!coverage.generatable()) {
                log.warn("Local fast catalog coverage has hard gaps city={} gaps={}", context.city(), coverage.gaps());
            } else if (!coverage.preferredCoverageMet()) {
                log.info("Local fast catalog coverage has reduced fallback margin city={} softGaps={}", context.city(), coverage.softGaps());
            }
            PlanDraft planDraft = itineraryGenerator.generate(specification, candidatePool, skeleton);
            PlanDraftResponse draft = planDraft == null ? null : planDraft.toResponse();
            if (context.deferCopyPolish()) {
                draft = operations.withCopyPolishStatus(draft, "deferred");
                planDraft = PlanDraft.fromResponse(draft);
            }
            LocalPlanQualityReport qualityReport = planQualityService.diagnose(planDraft);
            if (!qualityReport.warnings().isEmpty()) {
                log.warn("Local fast plan quality score={} errors={} warnings={} details={}",
                        qualityReport.score(),
                        qualityReport.errorCount(),
                        qualityReport.warningCount(),
                        qualityReport.warnings());
            }
            log.info("Local fast plan generation completed city={} requestedDays={} actualDayPlans={} elapsedMs={}",
                    specification.destination() == null ? context.city() : specification.destination().city(),
                    context.days(),
                    draft == null || draft.daysPlan() == null ? null : draft.daysPlan().size(),
                    System.currentTimeMillis() - startedAt);
            log.debug("Local fast zone pipeline city={} destinationId={} destinationResolved={} availableZones={} skeletonDays={} coverageGeneratable={} preferredCoverageMet={}",
                    specification.destination() == null ? context.city() : specification.destination().city(),
                    specification.destination() == null ? null : specification.destination().destinationId(),
                    specification.destination() != null && specification.destination().resolved(),
                    zones.size(),
                    skeleton.days().size(),
                    coverage.generatable(),
                    coverage.preferredCoverageMet());
            log.debug("Local fast parsed request destinationCandidate={} daysCandidate={} travellers={} pace={} hints={} lateStartPreferred={} familyFriendly={} indoorWhenRaining={}",
                    parsedRequest.destinationCandidate(),
                    parsedRequest.daysCandidate(),
                    parsedRequest.travellers(),
                    parsedRequest.pace(),
                    parsedRequest.preferenceHints(),
                    parsedRequest.lateStartPreferred(),
                    parsedRequest.familyFriendly(),
                    parsedRequest.preferIndoorWhenRaining());
            log.debug("Local fast retrieval query destinationId={} city={} minimumAllocation={} semanticQuery={} detectedHints={}",
                    retrievalQuery.destinationId(),
                    retrievalQuery.destinationCity(),
                    retrievalQuery.minimumAllocation(),
                    retrievalQuery.semanticQuery(),
                    retrievalQuery.detectedHints());
            log.debug("Local fast structured zone retrieval semanticCandidates={} fallbackCandidates={}",
                    retrievalResult.semanticCandidates().stream()
                            .map(candidate -> candidate.zone().zoneId() + ":" + candidate.score())
                            .toList(),
                    retrievalResult.feasibilityFallbackCandidates().stream()
                            .map(candidate -> candidate.zone().zoneId() + ":" + candidate.score())
                            .toList());
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
        return processGeneratedRawDraft(
                normalizedReq,
                raw,
                context.redisHit(),
                aiGenerationMs,
                context.deferCopyPolish(),
                operations
        );
    }

    public PlanDraftResponse processGeneratedRawDraft(
            CreatePlanReq req,
            String raw,
            boolean redisHit,
            long aiGenerationMs,
            boolean deferCopyPolish,
            Operations operations
    ) throws Exception {
        long totalStartedAt = System.currentTimeMillis() - Math.max(0, aiGenerationMs);
        StringBuilder stageSummary = new StringBuilder();
        StringBuilder timingSummary = new StringBuilder();
        List<PlanStageMetrics> qualityStages = new ArrayList<>();
        operations.appendStageTiming(timingSummary, "initial/ai-generate", aiGenerationMs);

        try {
            PlanRepairPipelineService.ProcessAttemptResult initialAttempt = operations.processAttemptWithJsonRecovery(
                    req,
                    raw,
                    "initial",
                    stageSummary,
                    timingSummary,
                    qualityStages,
                    null
            );
            return operations.validateAndRetry(
                    req,
                    raw,
                    redisHit,
                    totalStartedAt,
                    stageSummary,
                    timingSummary,
                    initialAttempt,
                    qualityStages,
                    deferCopyPolish
            );
        } catch (Exception e) {
            if (timingSummary.indexOf("total=") < 0) {
                operations.appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
                operations.logPlanStageSummary(stageSummary);
                operations.logPlanStageTimingSummary(timingSummary);
            }
            throw e;
        }
    }

    public abstract static class Operations {
        abstract PlanDraftResponse withCopyPolishStatus(PlanDraftResponse draft, String status);

        abstract PlanRepairPipelineService.ProcessAttemptResult processAttemptWithJsonRecovery(
                CreatePlanReq req,
                String raw,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                List<PlanStageMetrics> qualityStages,
                Object recoveryContext
        ) throws Exception;

        abstract PlanDraftResponse validateAndRetry(
                CreatePlanReq req,
                String raw,
                boolean redisHit,
                long totalStartedAt,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                PlanRepairPipelineService.ProcessAttemptResult initialAttempt,
                List<PlanStageMetrics> qualityStages,
                boolean deferCopyPolish
        ) throws Exception;

        abstract void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs);

        abstract void logPlanStageSummary(StringBuilder stageSummary);

        abstract void logPlanStageTimingSummary(StringBuilder timingSummary);
    }
}
