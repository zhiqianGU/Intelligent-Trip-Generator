package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.catalog.application.DestinationCatalog;
import thesis.project.gu.catalog.application.DestinationResolver;
import thesis.project.gu.catalog.application.CoverageGapResolutionService;
import thesis.project.gu.catalog.application.CoverageInventoryBuilder;
import thesis.project.gu.catalog.application.ExternalPoiEnrichmentRequest;
import thesis.project.gu.catalog.application.ExternalPoiEnrichmentResult;
import thesis.project.gu.catalog.application.ExternalPoiEnrichmentService;
import thesis.project.gu.catalog.application.NoopExternalPoiEnrichmentService;
import thesis.project.gu.catalog.application.PlanningZoneRetrievalService;
import thesis.project.gu.catalog.domain.AvailableZoneSummary;
import thesis.project.gu.catalog.domain.CatalogInventory;
import thesis.project.gu.catalog.domain.CoverageGap;
import thesis.project.gu.catalog.domain.CoverageInventory;
import thesis.project.gu.catalog.domain.CoverageResolutionResult;
import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSnapshot;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.PlanningAgentInput;
import thesis.project.gu.planning.domain.PlanningAgentOutput;
import thesis.project.gu.planning.domain.PlanningContextVersion;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;
import thesis.project.gu.planning.domain.SpecificationValidationResult;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripSlot;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.domain.ZoneContext;
import thesis.project.gu.planning.metrics.PlanStageMetrics;
import thesis.project.gu.planning.metrics.PlanningPipelineTrace;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;
import thesis.project.gu.planning.quality.PlanQualityService;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GenerateInitialPlanUseCase {
    private static final Logger log = LoggerFactory.getLogger(GenerateInitialPlanUseCase.class);

    private final PlanRequestContextBuilder planRequestContextBuilder;
    private final LightweightRequestPreParser lightweightRequestPreParser;
    private final RetrievalQueryBuilder retrievalQueryBuilder;
    private final DestinationResolver destinationResolver;
    private final PlanningZoneRetrievalService planningZoneRetrievalService;
    private final ZoneContextBuilder zoneContextBuilder;
    private final PlanningAgent planningAgent;
    private final PlanningAgent fallbackPlanningAgent = new LocalFallbackPlanningAgent();
    private final PlanningSpecificationValidator planningSpecificationValidator;
    private final CoverageInventoryBuilder coverageInventoryBuilder;
    private final CoverageGapResolutionService coverageGapResolutionService;
    private final ExternalPoiEnrichmentService externalPoiEnrichmentService;
    private final FinalCandidatePoolBuilder finalCandidatePoolBuilder;
    private final DestinationCatalog destinationCatalog;
    private final ItineraryGenerator itineraryGenerator;
    private final PlanQualityService planQualityService;
    private final RoutePlanningService routePlanningService;
    private final DefaultPlanQualityService defaultPlanQualityService;
    private final AiDraftGenerationService aiDraftGenerationService;
    private final PlanningTraceRecorder planningTraceRecorder;

    public GenerateInitialPlanUseCase(
            PlanRequestContextBuilder planRequestContextBuilder,
            LightweightRequestPreParser lightweightRequestPreParser,
            RetrievalQueryBuilder retrievalQueryBuilder,
            DestinationResolver destinationResolver,
            PlanningZoneRetrievalService planningZoneRetrievalService,
            ZoneContextBuilder zoneContextBuilder,
            PlanningAgent planningAgent,
            PlanningSpecificationValidator planningSpecificationValidator,
            CoverageInventoryBuilder coverageInventoryBuilder,
            CoverageGapResolutionService coverageGapResolutionService,
            DestinationCatalog destinationCatalog,
            ItineraryGenerator itineraryGenerator,
            PlanQualityService planQualityService,
            RoutePlanningService routePlanningService,
            DefaultPlanQualityService defaultPlanQualityService,
            AiDraftGenerationService aiDraftGenerationService
    ) {
        this(
                planRequestContextBuilder,
                lightweightRequestPreParser,
                retrievalQueryBuilder,
                destinationResolver,
                planningZoneRetrievalService,
                zoneContextBuilder,
                planningAgent,
                planningSpecificationValidator,
                coverageInventoryBuilder,
                coverageGapResolutionService,
                new NoopExternalPoiEnrichmentService(),
                new FinalCandidatePoolBuilder(),
                destinationCatalog,
                itineraryGenerator,
                planQualityService,
                routePlanningService,
                defaultPlanQualityService,
                aiDraftGenerationService,
                new LoggingPlanningTraceRecorder()
        );
    }

    public GenerateInitialPlanUseCase(
            PlanRequestContextBuilder planRequestContextBuilder,
            LightweightRequestPreParser lightweightRequestPreParser,
            RetrievalQueryBuilder retrievalQueryBuilder,
            DestinationResolver destinationResolver,
            PlanningZoneRetrievalService planningZoneRetrievalService,
            ZoneContextBuilder zoneContextBuilder,
            PlanningAgent planningAgent,
            PlanningSpecificationValidator planningSpecificationValidator,
            CoverageInventoryBuilder coverageInventoryBuilder,
            CoverageGapResolutionService coverageGapResolutionService,
            ExternalPoiEnrichmentService externalPoiEnrichmentService,
            FinalCandidatePoolBuilder finalCandidatePoolBuilder,
            DestinationCatalog destinationCatalog,
            ItineraryGenerator itineraryGenerator,
            PlanQualityService planQualityService,
            RoutePlanningService routePlanningService,
            DefaultPlanQualityService defaultPlanQualityService,
            AiDraftGenerationService aiDraftGenerationService
    ) {
        this(
                planRequestContextBuilder,
                lightweightRequestPreParser,
                retrievalQueryBuilder,
                destinationResolver,
                planningZoneRetrievalService,
                zoneContextBuilder,
                planningAgent,
                planningSpecificationValidator,
                coverageInventoryBuilder,
                coverageGapResolutionService,
                externalPoiEnrichmentService,
                finalCandidatePoolBuilder,
                destinationCatalog,
                itineraryGenerator,
                planQualityService,
                routePlanningService,
                defaultPlanQualityService,
                aiDraftGenerationService,
                new LoggingPlanningTraceRecorder()
        );
    }

    @Autowired
    public GenerateInitialPlanUseCase(
            PlanRequestContextBuilder planRequestContextBuilder,
            LightweightRequestPreParser lightweightRequestPreParser,
            RetrievalQueryBuilder retrievalQueryBuilder,
            DestinationResolver destinationResolver,
            PlanningZoneRetrievalService planningZoneRetrievalService,
            ZoneContextBuilder zoneContextBuilder,
            PlanningAgent planningAgent,
            PlanningSpecificationValidator planningSpecificationValidator,
            CoverageInventoryBuilder coverageInventoryBuilder,
            CoverageGapResolutionService coverageGapResolutionService,
            ExternalPoiEnrichmentService externalPoiEnrichmentService,
            FinalCandidatePoolBuilder finalCandidatePoolBuilder,
            DestinationCatalog destinationCatalog,
            ItineraryGenerator itineraryGenerator,
            PlanQualityService planQualityService,
            RoutePlanningService routePlanningService,
            DefaultPlanQualityService defaultPlanQualityService,
            AiDraftGenerationService aiDraftGenerationService,
            PlanningTraceRecorder planningTraceRecorder
    ) {
        this.planRequestContextBuilder = planRequestContextBuilder;
        this.lightweightRequestPreParser = lightweightRequestPreParser;
        this.retrievalQueryBuilder = retrievalQueryBuilder;
        this.destinationResolver = destinationResolver;
        this.planningZoneRetrievalService = planningZoneRetrievalService;
        this.zoneContextBuilder = zoneContextBuilder;
        this.planningAgent = planningAgent;
        this.planningSpecificationValidator = planningSpecificationValidator;
        this.coverageInventoryBuilder = coverageInventoryBuilder;
        this.coverageGapResolutionService = coverageGapResolutionService;
        this.externalPoiEnrichmentService = externalPoiEnrichmentService == null
                ? new NoopExternalPoiEnrichmentService()
                : externalPoiEnrichmentService;
        this.finalCandidatePoolBuilder = finalCandidatePoolBuilder == null
                ? new FinalCandidatePoolBuilder()
                : finalCandidatePoolBuilder;
        this.destinationCatalog = destinationCatalog;
        this.itineraryGenerator = itineraryGenerator;
        this.planQualityService = planQualityService;
        this.routePlanningService = routePlanningService;
        this.defaultPlanQualityService = defaultPlanQualityService;
        this.aiDraftGenerationService = aiDraftGenerationService;
        this.planningTraceRecorder = planningTraceRecorder == null
                ? new LoggingPlanningTraceRecorder()
                : planningTraceRecorder;
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
            String requestId = UUID.randomUUID().toString();
            List<PlanningPipelineTrace.Snapshot> traceSnapshots = new ArrayList<>();
            int externalPoiApiCalls = 0;
            int repairRounds = 0;
            ParsedPlanningRequest parsedRequest = lightweightRequestPreParser.parse(normalizedReq);
            traceSnapshots.add(snapshot("raw-request", "city=" + normalizedReq.city()
                    + ",days=" + normalizedReq.days()
                    + ",pace=" + normalizedReq.pace()
                    + ",styles=" + normalizedReq.style()));
            traceSnapshots.add(snapshot("pre-parser", "destinationCandidate=" + parsedRequest.destinationCandidate()
                    + ",daysCandidate=" + parsedRequest.daysCandidate()
                    + ",hints=" + parsedRequest.preferenceHints()));
            TripPlanningSpecification baseSpecification = TripPlanningSpecification.fromRequest(normalizedReq);
            Destination resolvedDestination = destinationResolver.resolve(parsedRequest.destinationCandidate());
            traceSnapshots.add(snapshot("destination-resolver", "destinationId=" + resolvedDestination.destinationId()
                    + ",city=" + resolvedDestination.city()
                    + ",resolved=" + resolvedDestination.resolved()));
            TripPlanningSpecification specification = baseSpecification.withResolvedDestination(resolvedDestination);
            PlanningZoneRetrievalQuery retrievalQuery = retrievalQueryBuilder.build(parsedRequest, resolvedDestination, specification);
            traceSnapshots.add(snapshot("retrieval-query", "destinationId=" + retrievalQuery.destinationId()
                    + ",minimumAllocation=" + retrievalQuery.minimumAllocation()
                    + ",semanticQuery=" + retrievalQuery.semanticQuery()));
            List<PlanningZoneSummary> availableZones = destinationCatalog.findAvailableZones(specification);
            PlanningZoneRetrievalResult retrievalResult = planningZoneRetrievalService.retrieve(retrievalQuery, availableZones);
            List<PlanningZoneSummary> zones = retrievalResult.orderedZones().isEmpty()
                    ? availableZones
                    : retrievalResult.orderedZones();
            traceSnapshots.add(snapshot("top-k-zone", "orderedZones=" + zones.stream().map(PlanningZoneSummary::zoneId).toList()));
            List<AvailableZoneSummary> availableZoneSummaries = destinationCatalog.findAvailableZoneSummaries(specification, zones);
            List<PlanningZoneSnapshot> zoneSnapshots = destinationCatalog.findZoneSnapshots(specification);
            PlanningContextVersion contextVersion = PlanningContextVersion.fromZoneSnapshots(zoneSnapshots);
            List<ZoneContext> zoneContexts = zoneContextBuilder.build(zones, zoneSnapshots, availableZoneSummaries);
            traceSnapshots.add(snapshot("zone-context", "contexts=" + zoneContexts.stream()
                    .map(zone -> zone.zoneId() + ":capacity=" + zone.capacity())
                    .toList()));
            PlanningAgentInput planningAgentInput = new PlanningAgentInput(specification, parsedRequest, zoneContexts, contextVersion);
            long agentStartedAt = System.currentTimeMillis();
            PlanningAgentOutput planningOutput = planWithFallback(planningAgentInput);
            long planningAgentLatencyMs = System.currentTimeMillis() - agentStartedAt;
            traceSnapshots.add(snapshot("agent-output", "plannerType=" + planningOutput.plannerType()
                    + ",fallbackUsed=" + planningOutput.fallbackUsed()
                    + ",dayStrategies=" + planningOutput.dayStrategies().stream()
                    .map(strategy -> "day" + strategy.day() + ":" + strategy.primaryZoneId())
                    .toList()));
            specification = specification.withAgentOutput(planningOutput);
            SpecificationValidationResult specificationValidation = planningSpecificationValidator.validate(specification, zoneContexts, parsedRequest);
            specification = specificationValidation.specification();
            traceSnapshots.add(snapshot("specification-validator", "valid=" + specificationValidation.valid()
                    + ",inputValid=" + specificationValidation.inputValid()
                    + ",repaired=" + specificationValidation.repaired()
                    + ",issues=" + specificationValidation.issues()));
            TripSkeleton skeleton = destinationCatalog.buildTripSkeleton(specification, zones);
            traceSnapshots.add(snapshot("trip-skeleton", "days=" + skeleton.days().size()
                    + ",zones=" + skeleton.days().stream().map(TripSkeleton.DaySkeleton::zoneId).toList()));
            PlaceCandidatePool preliminaryCandidatePool = destinationCatalog.buildCandidatePool(specification);
            CoverageResult coverage = destinationCatalog.checkCoverage(specification, skeleton, preliminaryCandidatePool, availableZoneSummaries, zones);
            int initialHardGapCount = coverage == null || coverage.gaps() == null ? 0 : coverage.gaps().size();
            int initialSoftGapCount = coverage == null || coverage.softGaps() == null ? 0 : coverage.softGaps().size();
            traceSnapshots.add(snapshot("coverage-check", coverageSummary(coverage)));
            CoverageResolutionResult coverageResolution = null;
            if (!coverage.generatable()) {
                CoverageInventory coverageInventory = coverageInventoryBuilder.build(
                        specification,
                        skeleton,
                        CatalogInventory.fromCandidatePool(preliminaryCandidatePool),
                        availableZoneSummaries,
                        zones
                );
                coverageResolution = coverageGapResolutionService.resolve(
                        skeleton,
                        coverageInventory,
                        CatalogInventory.fromCandidatePool(preliminaryCandidatePool),
                        availableZoneSummaries,
                        zones
                );
                if (coverageResolution.modified()) {
                    repairRounds++;
                    skeleton = coverageResolution.skeleton();
                    coverage = destinationCatalog.checkCoverage(specification, skeleton, preliminaryCandidatePool, availableZoneSummaries, zones);
                    traceSnapshots.add(snapshot("coverage-gap-resolution", "modified=true,actions=" + coverageResolution.actions()
                            + ",coverage=" + coverageSummary(coverage)));
                } else {
                    traceSnapshots.add(snapshot("coverage-gap-resolution", "modified=false,unresolvedHardGaps=" + coverageResolution.unresolvedHardGaps()));
                }
            }
            if (!coverage.generatable()) {
                externalPoiApiCalls++;
                ExternalPoiEnrichmentResult enrichmentResult = enrichExternalPoisWithFallback(new ExternalPoiEnrichmentRequest(
                        specification,
                        skeleton,
                        coverage.gaps(),
                        preliminaryCandidatePool,
                        availableZoneSummaries,
                        zones
                ));
                if (enrichmentResult != null && enrichmentResult.candidatePool() != null && enrichmentResult.addedCandidateCount() > 0) {
                    preliminaryCandidatePool = enrichmentResult.candidatePool();
                    coverage = destinationCatalog.checkCoverage(specification, skeleton, preliminaryCandidatePool, availableZoneSummaries, zones);
                    traceSnapshots.add(snapshot("external-poi-enrichment", "addedCandidates=" + enrichmentResult.addedCandidateCount()
                            + ",warnings=" + enrichmentResult.warnings()
                            + ",coverage=" + coverageSummary(coverage)));
                    log.info("Local fast external POI enrichment addedCandidates={} warnings={} coverageGeneratable={}",
                            enrichmentResult.addedCandidateCount(),
                            enrichmentResult.warnings(),
                            coverage.generatable());
                }
            }
            if (!coverage.generatable()) {
                TripSkeleton reducedSkeleton = reduceNonEssentialActivitySlots(skeleton, coverage);
                if (reducedSkeleton != skeleton) {
                    repairRounds++;
                    skeleton = reducedSkeleton;
                    coverage = destinationCatalog.checkCoverage(specification, skeleton, preliminaryCandidatePool, availableZoneSummaries, zones);
                    traceSnapshots.add(snapshot("slot-reduction", "coverage=" + coverageSummary(coverage)));
                }
            }
            if (!coverage.generatable()) {
                log.warn("Local fast catalog coverage has hard gaps city={} gaps={}", context.city(), coverage.gaps());
                traceSnapshots.add(snapshot("failure", "reason=coverage-hard-gap,gaps=" + coverage.gaps()));
                safeRecordTrace(buildLocalFastTrace(
                        requestId,
                        specification,
                        contextVersion,
                        retrievalResult,
                        zones,
                        planningAgentLatencyMs,
                        planningOutput,
                        coverage,
                        initialHardGapCount,
                        initialSoftGapCount,
                        externalPoiApiCalls,
                        0,
                        repairRounds,
                        null,
                        System.currentTimeMillis() - startedAt,
                        traceSnapshots
                ));
                throw ErrorCode.ROUTE_FAIL.ex("No feasible local plan coverage for requested itinerary: " + coverage.gaps());
            } else if (!coverage.preferredCoverageMet()) {
                log.info("Local fast catalog coverage has reduced fallback margin city={} softGaps={}", context.city(), coverage.softGaps());
            }
            PlaceCandidatePool finalCandidatePool = finalCandidatePoolBuilder.build(specification, skeleton, preliminaryCandidatePool);
            traceSnapshots.add(snapshot("final-candidate-pool", "hotels=" + finalCandidatePool.hotels().size()
                    + ",attractions=" + finalCandidatePool.attractions().size()
                    + ",restaurants=" + finalCandidatePool.restaurants().size()));
            long schedulerStartedAt = System.currentTimeMillis();
            PlanDraft planDraft = itineraryGenerator.generate(specification, finalCandidatePool, skeleton);
            long schedulerLatencyMs = System.currentTimeMillis() - schedulerStartedAt;
            PlanDraftResponse draft = planDraft == null ? null : planDraft.toResponse();
            if (context.deferCopyPolish()) {
                draft = operations.withCopyPolishStatus(draft, "deferred");
                planDraft = PlanDraft.fromResponse(draft);
            }
            LocalPlanQualityReport qualityReport = planQualityService.diagnose(planDraft);
            qualityReport = mergeQualityReport(qualityReport, validateSkeletonQuality(planDraft, skeleton));
            draft = withFirstScreenStatus(draft, coverage, qualityReport, context.deferCopyPolish(), contextVersion);
            planDraft = PlanDraft.fromResponse(draft);
            traceSnapshots.add(snapshot("final-plan", "days=" + (draft == null || draft.daysPlan() == null ? 0 : draft.daysPlan().size())
                    + ",planStatus=" + (draft == null ? null : draft.planStatus())
                    + ",warnings=" + (draft == null ? List.of() : draft.warnings())));
            safeRecordTrace(buildLocalFastTrace(
                    requestId,
                    specification,
                    contextVersion,
                    retrievalResult,
                    zones,
                    planningAgentLatencyMs,
                    planningOutput,
                    coverage,
                    initialHardGapCount,
                    initialSoftGapCount,
                    externalPoiApiCalls,
                    schedulerLatencyMs,
                    repairRounds,
                    qualityReport,
                    System.currentTimeMillis() - startedAt,
                    traceSnapshots
            ));
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
            log.debug("Local fast request-scoped zone summaries={}",
                    availableZoneSummaries.stream()
                            .map(summary -> summary.zoneId() + ":activities=" + summary.availableAttractionCount()
                                    + ",capacity=" + summary.requestScopedCapacity()
                                    + ",freshness=" + summary.freshnessStatus())
                            .toList());
            log.debug("Local fast compact zone contexts={}",
                    zoneContexts.stream()
                            .map(zone -> zone.zoneId() + ":weather=" + zone.weatherSuitability()
                                    + ",capacity=" + zone.capacity()
                                    + ",snapshot=" + zone.snapshotVersion())
                            .toList());
            log.debug("Local fast planning context version catalog={} zoneSnapshot={} semanticProfile={} embedding={} prompt={} specification={} planVersion={}",
                    contextVersion.catalogVersion(),
                    contextVersion.zoneSnapshotVersion(),
                    contextVersion.semanticProfileVersion(),
                    contextVersion.embeddingVersion(),
                    contextVersion.planningPromptVersion(),
                    contextVersion.planningSpecificationVersion(),
                    draft == null ? null : draft.planVersion());
            log.debug("Local fast planning agent plannerType={} fallbackUsed={} dayStrategies={}",
                    planningOutput.plannerType(),
                    planningOutput.fallbackUsed(),
                    planningOutput.dayStrategies().stream()
                            .map(strategy -> "day" + strategy.day() + ":" + strategy.primaryZoneId())
                            .toList());
            log.debug("Local fast planning specification validation valid={} inputValid={} repaired={} issues={}",
                    specificationValidation.valid(),
                    specificationValidation.inputValid(),
                    specificationValidation.repaired(),
                    specificationValidation.issues());
            if (coverageResolution != null) {
                log.debug("Local fast coverage gap resolution resolved={} modified={} actions={} unresolvedHardGaps={}",
                        coverageResolution.resolved(),
                        coverageResolution.modified(),
                        coverageResolution.actions(),
                        coverageResolution.unresolvedHardGaps());
            }
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

    private List<LocalPlanQualityReport.Warning> validateSkeletonQuality(PlanDraft planDraft, TripSkeleton skeleton) {
        if (defaultPlanQualityService == null) {
            return List.of();
        }
        return defaultPlanQualityService.validateSkeleton(planDraft, skeleton);
    }

    private PlanningPipelineTrace buildLocalFastTrace(
            String requestId,
            TripPlanningSpecification specification,
            PlanningContextVersion contextVersion,
            PlanningZoneRetrievalResult retrievalResult,
            List<PlanningZoneSummary> selectedZones,
            long planningAgentLatencyMs,
            PlanningAgentOutput planningOutput,
            CoverageResult coverage,
            int initialHardGapCount,
            int initialSoftGapCount,
            int externalPoiApiCalls,
            long schedulerLatencyMs,
            int repairRounds,
            LocalPlanQualityReport qualityReport,
            long totalLatencyMs,
            List<PlanningPipelineTrace.Snapshot> snapshots
    ) {
        return new PlanningPipelineTrace(
                requestId,
                "ZONE_GUIDED_LOCAL_FIRST",
                specification == null || specification.destination() == null ? "" : specification.destination().city(),
                contextVersion,
                retrievalCandidates(retrievalResult),
                selectedZones == null ? List.of() : selectedZones.stream().map(PlanningZoneSummary::zoneId).toList(),
                Math.max(0, planningAgentLatencyMs),
                0,
                planningOutput != null && planningOutput.fallbackUsed(),
                Math.max(0, initialHardGapCount),
                Math.max(0, initialSoftGapCount),
                Math.max(0, externalPoiApiCalls),
                Math.max(0, schedulerLatencyMs),
                Math.max(0, repairRounds),
                qualityReport == null ? 100 : qualityReport.score(),
                Math.max(0, totalLatencyMs),
                snapshots
        );
    }

    private void safeRecordTrace(PlanningPipelineTrace trace) {
        try {
            planningTraceRecorder.record(trace);
        } catch (Exception e) {
            log.warn("Planning pipeline trace recording failed; continuing without trace persistence. reason={}", e.toString());
        }
    }

    private List<PlanningPipelineTrace.RetrievalCandidate> retrievalCandidates(PlanningZoneRetrievalResult retrievalResult) {
        if (retrievalResult == null) {
            return List.of();
        }
        List<PlanningPipelineTrace.RetrievalCandidate> candidates = new ArrayList<>();
        retrievalResult.semanticCandidates().stream()
                .map(candidate -> retrievalCandidate(candidate, "semantic"))
                .forEach(candidates::add);
        retrievalResult.feasibilityFallbackCandidates().stream()
                .map(candidate -> retrievalCandidate(candidate, "fallback"))
                .forEach(candidates::add);
        return candidates;
    }

    private PlanningPipelineTrace.RetrievalCandidate retrievalCandidate(
            PlanningZoneRetrievalResult.ScoredZone candidate,
            String source
    ) {
        return new PlanningPipelineTrace.RetrievalCandidate(
                candidate == null || candidate.zone() == null ? "" : candidate.zone().zoneId(),
                candidate == null ? 0 : candidate.score(),
                source,
                candidate == null ? List.of() : candidate.reasons()
        );
    }

    private PlanningPipelineTrace.Snapshot snapshot(String stage, String summary) {
        return new PlanningPipelineTrace.Snapshot(stage, summary);
    }

    private String coverageSummary(CoverageResult coverage) {
        if (coverage == null) {
            return "coverage=null";
        }
        return "generatable=" + coverage.generatable()
                + ",preferredCoverageMet=" + coverage.preferredCoverageMet()
                + ",hardGaps=" + (coverage.gaps() == null ? 0 : coverage.gaps().size())
                + ",softGaps=" + (coverage.softGaps() == null ? 0 : coverage.softGaps().size());
    }

    private LocalPlanQualityReport mergeQualityReport(
            LocalPlanQualityReport base,
            List<LocalPlanQualityReport.Warning> additionalWarnings
    ) {
        if (additionalWarnings == null || additionalWarnings.isEmpty()) {
            return base;
        }
        List<LocalPlanQualityReport.Warning> merged = new ArrayList<>();
        if (base != null && base.warnings() != null) {
            merged.addAll(base.warnings());
        }
        merged.addAll(additionalWarnings);
        int errors = (int) merged.stream()
                .filter(warning -> warning.severity() == LocalPlanQualityReport.Severity.ERROR)
                .count();
        int warnings = merged.size() - errors;
        int baseScore = base == null ? 100 : base.score();
        int penalty = additionalWarnings.stream()
                .mapToInt(warning -> warning.severity() == LocalPlanQualityReport.Severity.ERROR ? 15 : 2)
                .sum();
        return new LocalPlanQualityReport(Math.max(0, baseScore - penalty), errors, warnings, List.copyOf(merged));
    }

    private ExternalPoiEnrichmentResult enrichExternalPoisWithFallback(ExternalPoiEnrichmentRequest request) {
        try {
            ExternalPoiEnrichmentResult result = externalPoiEnrichmentService.enrich(request);
            return result == null ? ExternalPoiEnrichmentResult.unchanged(request == null ? null : request.candidatePool()) : result;
        } catch (Exception e) {
            log.warn("External POI enrichment failed; continuing with local candidate pool. reason={}", e.toString());
            return ExternalPoiEnrichmentResult.unchanged(request == null ? null : request.candidatePool());
        }
    }

    private PlanningAgentOutput planWithFallback(PlanningAgentInput input) {
        try {
            PlanningAgentOutput output = planningAgent == null ? null : planningAgent.plan(input);
            if (output != null) {
                return output;
            }
            log.warn("Planning agent returned null output; falling back to local planner.");
        } catch (Exception e) {
            log.warn("Planning agent failed; falling back to local planner. reason={}", e.toString());
        }
        return fallbackPlanningAgent.plan(input);
    }

    private PlanDraftResponse withFirstScreenStatus(
            PlanDraftResponse draft,
            CoverageResult coverage,
            LocalPlanQualityReport qualityReport,
            boolean copyDeferred,
            PlanningContextVersion contextVersion
    ) {
        if (draft == null) {
            return null;
        }
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                draft.daysPlan(),
                draft.copyPolishStatus(),
                safeStatus(draft.routeStatus(), "ESTIMATED"),
                "READY",
                "ZONE_GUIDED_LOCAL_FIRST",
                catalogStatus(coverage),
                copyDeferred ? "PENDING" : safeStatus(draft.copyStatus(), "BASIC"),
                "PENDING",
                buildWarnings(coverage, qualityReport),
                contextVersion,
                safeStatus(draft.planVersion(), PlanningContextVersion.INITIAL_PLAN_VERSION),
                draft.basePlanVersion()
        );
    }

    private String catalogStatus(CoverageResult coverage) {
        if (coverage == null) {
            return "UNKNOWN";
        }
        return coverage.preferredCoverageMet() ? "SUFFICIENT" : "REDUCED_FALLBACK";
    }

    private List<String> buildWarnings(CoverageResult coverage, LocalPlanQualityReport qualityReport) {
        List<String> warnings = new ArrayList<>();
        if (coverage != null && coverage.softGaps() != null) {
            coverage.softGaps().stream()
                    .map(gap -> "coverage-soft-gap:day" + gap.day() + ":" + gap.slotType()
                            + ":missing=" + gap.missingCount())
                    .forEach(warnings::add);
        }
        if (qualityReport != null && qualityReport.warnings() != null) {
            qualityReport.warnings().stream()
                    .map(warning -> warning.code()
                            + (warning.dayIndex() == null ? "" : ":day" + warning.dayIndex())
                            + (warning.message() == null || warning.message().isBlank() ? "" : ":" + warning.message()))
                    .forEach(warnings::add);
        }
        return warnings;
    }

    private String safeStatus(String value, String fallback) {
        return value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value.trim()) ? fallback : value;
    }

    private TripSkeleton reduceNonEssentialActivitySlots(TripSkeleton skeleton, CoverageResult coverage) {
        if (skeleton == null || skeleton.days() == null || coverage == null || coverage.gaps() == null || coverage.gaps().isEmpty()) {
            return skeleton;
        }
        List<TripSkeleton.DaySkeleton> days = new ArrayList<>();
        boolean changed = false;
        for (TripSkeleton.DaySkeleton day : skeleton.days()) {
            int removableActivities = coverage.gaps().stream()
                    .filter(gap -> gap.day() == day.day())
                    .filter(gap -> "ACTIVITY".equals(gap.slotType()))
                    .mapToInt(CoverageGap::missingCount)
                    .sum();
            if (removableActivities <= 0) {
                days.add(day);
                continue;
            }
            List<TripSlot> slots = new ArrayList<>(day.slots());
            int activityCount = (int) slots.stream()
                    .filter(slot -> slot.slotType() == TripSlot.SlotType.ACTIVITY)
                    .count();
            int maxRemovable = Math.max(0, activityCount - 1);
            int removeCount = Math.min(removableActivities, maxRemovable);
            for (int index = slots.size() - 1; index >= 0 && removeCount > 0; index--) {
                if (slots.get(index).slotType() == TripSlot.SlotType.ACTIVITY) {
                    slots.remove(index);
                    removeCount--;
                    changed = true;
                }
            }
            days.add(new TripSkeleton.DaySkeleton(
                    day.day(),
                    day.theme(),
                    day.zoneId(),
                    day.startTime(),
                    slots,
                    day.fallbackZoneIds()
            ));
        }
        return changed ? new TripSkeleton(days) : skeleton;
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
