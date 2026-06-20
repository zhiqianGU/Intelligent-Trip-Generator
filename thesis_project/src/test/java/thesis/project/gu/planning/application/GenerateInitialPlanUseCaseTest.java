package thesis.project.gu.planning.application;

import org.junit.jupiter.api.Test;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.catalog.application.DestinationCatalog;
import thesis.project.gu.catalog.application.CoverageGapResolutionService;
import thesis.project.gu.catalog.application.CoverageInventoryBuilder;
import thesis.project.gu.catalog.application.ExternalPoiEnrichmentRequest;
import thesis.project.gu.catalog.application.ExternalPoiEnrichmentResult;
import thesis.project.gu.catalog.application.ExternalPoiEnrichmentService;
import thesis.project.gu.catalog.application.PlanningZoneRetrievalService;
import thesis.project.gu.planning.domain.PlaceCandidate;
import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.domain.ZoneCapabilitySummary;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlaceCandidateType;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripSlot;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.metrics.PlanningPipelineTrace;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;
import thesis.project.gu.planning.quality.PlanQualityService;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateInitialPlanUseCaseTest {
    @Test
    void structuredRetrievalOrderIsUsedToBuildSkeletonPassedToGenerator() throws Exception {
        PlanningZoneSummary catalogFirst = zone("brisbane-cbd", "CBD");
        PlanningZoneSummary retrievalFirst = zone("brisbane-south-bank", "South Bank");
        CapturingDestinationCatalog catalog = new CapturingDestinationCatalog(List.of(catalogFirst, retrievalFirst));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        CapturingPlanningTraceRecorder traceRecorder = new CapturingPlanningTraceRecorder();
        CoverageInventoryBuilder coverageInventoryBuilder = new CoverageInventoryBuilder();
        PlanningZoneRetrievalService retrievalService = (query, availableZones) -> new PlanningZoneRetrievalResult(
                List.of(new PlanningZoneRetrievalResult.ScoredZone(retrievalFirst, 100, List.of("style-match"))),
                List.of(new PlanningZoneRetrievalResult.ScoredZone(catalogFirst, 80, List.of("meal-support")))
        );
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                retrievalService,
                new ZoneContextBuilder(),
                new LocalFallbackPlanningAgent(),
                new PlanningSpecificationValidator(),
                coverageInventoryBuilder,
                new CoverageGapResolutionService(coverageInventoryBuilder),
                request -> ExternalPoiEnrichmentResult.unchanged(request.candidatePool()),
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null,
                traceRecorder
        );

        PlanDraftResponse result = useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        );

        assertThat(result.planStatus()).isEqualTo("READY");
        assertThat(result.planningMode()).isEqualTo("ZONE_GUIDED_LOCAL_FIRST");
        assertThat(result.routeStatus()).isEqualTo("ESTIMATED");
        assertThat(result.catalogStatus()).isEqualTo("SUFFICIENT");
        assertThat(result.copyStatus()).isEqualTo("BASIC");
        assertThat(result.enhancementStatus()).isEqualTo("PENDING");
        assertThat(result.contextVersion().catalogVersion()).isEqualTo("local-poi-v1");
        assertThat(result.contextVersion().zoneSnapshotVersion()).isEqualTo("local-poi-v1");
        assertThat(result.contextVersion().embeddingVersion()).isEqualTo("none");
        assertThat(result.planVersion()).isEqualTo("plan-v1");
        assertThat(result.basePlanVersion()).isEmpty();
        assertThat(catalog.orderedZonesPassedToSkeleton)
                .extracting(PlanningZoneSummary::zoneId)
                .containsExactly("brisbane-south-bank", "brisbane-cbd");
        assertThat(catalog.specificationPassedToSkeleton.dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-south-bank");
        assertThat(generator.receivedSkeleton.days().getFirst().zoneId()).isEqualTo("brisbane-south-bank");
        assertThat(traceRecorder.trace).isNotNull();
        assertThat(traceRecorder.trace.planningMode()).isEqualTo("ZONE_GUIDED_LOCAL_FIRST");
        assertThat(traceRecorder.trace.retrievalCandidates())
                .extracting(PlanningPipelineTrace.RetrievalCandidate::zoneId)
                .containsExactly("brisbane-south-bank", "brisbane-cbd");
        assertThat(traceRecorder.trace.selectedZones()).containsExactly("brisbane-south-bank", "brisbane-cbd");
        assertThat(traceRecorder.trace.planningFallbackUsed()).isTrue();
        assertThat(traceRecorder.trace.coverageHardGapCount()).isZero();
        assertThat(traceRecorder.trace.totalLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(traceRecorder.trace.snapshots())
                .extracting(PlanningPipelineTrace.Snapshot::stage)
                .contains("raw-request", "pre-parser", "destination-resolver", "retrieval-query",
                        "top-k-zone", "zone-context", "agent-output", "specification-validator",
                        "trip-skeleton", "coverage-check", "final-candidate-pool", "final-plan");
    }

    @Test
    void planningAgentFailureFallsBackToLocalStrategyAndStillBuildsSkeleton() throws Exception {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        CapturingDestinationCatalog catalog = new CapturingDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        PlanningZoneRetrievalService retrievalService = (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of());
        PlanningAgent failingAgent = input -> {
            throw new IllegalStateException("agent timeout");
        };
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                retrievalService,
                new ZoneContextBuilder(),
                failingAgent,
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                request -> ExternalPoiEnrichmentResult.unchanged(request.candidatePool()),
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null
        );

        useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        );

        assertThat(catalog.specificationPassedToSkeleton.dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-south-bank");
        assertThat(generator.receivedSkeleton).isNotNull();
    }

    @Test
    void traceRecorderFailureDoesNotBreakLocalFastResponse() throws Exception {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        CapturingDestinationCatalog catalog = new CapturingDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of()),
                new ZoneContextBuilder(),
                new LocalFallbackPlanningAgent(),
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                request -> ExternalPoiEnrichmentResult.unchanged(request.candidatePool()),
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null,
                trace -> {
                    throw new IllegalStateException("trace db down");
                }
        );

        PlanDraftResponse result = useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        );

        assertThat(result.planStatus()).isEqualTo("READY");
        assertThat(result.planningMode()).isEqualTo("ZONE_GUIDED_LOCAL_FIRST");
    }

    @Test
    void nullPlanningAgentOutputFallsBackToLocalStrategy() throws Exception {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        CapturingDestinationCatalog catalog = new CapturingDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of()),
                new ZoneContextBuilder(),
                input -> null,
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                request -> ExternalPoiEnrichmentResult.unchanged(request.candidatePool()),
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null
        );

        useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        );

        assertThat(catalog.specificationPassedToSkeleton.dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-south-bank");
    }

    @Test
    void localFastGenerationRunsSkeletonAwareQualityValidation() throws Exception {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        CapturingDestinationCatalog catalog = new CapturingDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        DefaultPlanQualityService defaultQualityService = mock(DefaultPlanQualityService.class);
        when(defaultQualityService.validateSkeleton(any(PlanDraft.class), any(TripSkeleton.class))).thenReturn(List.of());
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of()),
                new ZoneContextBuilder(),
                new LocalFallbackPlanningAgent(),
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                request -> ExternalPoiEnrichmentResult.unchanged(request.candidatePool()),
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                defaultQualityService,
                null
        );

        useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        );

        verify(defaultQualityService).validateSkeleton(any(PlanDraft.class), any(TripSkeleton.class));
    }

    @Test
    void unresolvedHardGapDoesNotEnterNormalGenerationPath() {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        HardGapDestinationCatalog catalog = new HardGapDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        CapturingPlanningTraceRecorder traceRecorder = new CapturingPlanningTraceRecorder();
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of()),
                new ZoneContextBuilder(),
                new LocalFallbackPlanningAgent(),
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                request -> ExternalPoiEnrichmentResult.unchanged(request.candidatePool()),
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null,
                traceRecorder
        );

        assertThatThrownBy(() -> useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        )).isInstanceOf(NavigatorException.class);

        assertThat(generator.receivedSkeleton).isNull();
        assertThat(traceRecorder.trace).isNotNull();
        assertThat(traceRecorder.trace.coverageHardGapCount()).isGreaterThan(0);
        assertThat(traceRecorder.trace.snapshots())
                .extracting(PlanningPipelineTrace.Snapshot::stage)
                .contains("coverage-check", "coverage-gap-resolution", "failure");
    }

    @Test
    void unresolvedHardGapInvokesExternalPoiEnrichmentBeforeSlotReduction() throws Exception {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        EnrichableHardGapDestinationCatalog catalog = new EnrichableHardGapDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        CapturingExternalPoiEnrichmentService enrichmentService = new CapturingExternalPoiEnrichmentService();
        CapturingPlanningTraceRecorder traceRecorder = new CapturingPlanningTraceRecorder();
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of()),
                new ZoneContextBuilder(),
                new LocalFallbackPlanningAgent(),
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                enrichmentService,
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null,
                traceRecorder
        );

        useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        );

        assertThat(enrichmentService.request).isNotNull();
        assertThat(enrichmentService.request.hardGaps()).singleElement().satisfies(gap ->
                assertThat(gap.slotType()).isEqualTo("DINNER"));
        assertThat(generator.receivedSkeleton).isNotNull();
        assertThat(generator.receivedCandidatePool.restaurants()).hasSize(1);
        assertThat(traceRecorder.trace).isNotNull();
        assertThat(traceRecorder.trace.coverageHardGapCount()).isEqualTo(1);
        assertThat(traceRecorder.trace.externalPoiApiCalls()).isEqualTo(1);
    }

    @Test
    void externalPoiEnrichmentFailureFallsBackToLocalCoverageFailure() {
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");
        HardGapDestinationCatalog catalog = new HardGapDestinationCatalog(List.of(southBank));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
        GenerateInitialPlanUseCase useCase = new GenerateInitialPlanUseCase(
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new LightweightRequestPreParser(),
                new RetrievalQueryBuilder(),
                destinationCandidate -> new Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                (query, availableZones) -> new PlanningZoneRetrievalResult(List.of(), List.of()),
                new ZoneContextBuilder(),
                new LocalFallbackPlanningAgent(),
                new PlanningSpecificationValidator(),
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
                request -> {
                    throw new IllegalStateException("amap timeout");
                },
                new FinalCandidatePoolBuilder(),
                catalog,
                generator,
                passingQualityService(),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> useCase.execute(
                new CreatePlanReq(
                        "Brisbane",
                        1,
                        1200,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture"),
                        "normal",
                        "local-fast",
                        null
                ),
                false,
                false,
                new NoopOperations()
        ))
                .isInstanceOf(NavigatorException.class)
                .hasMessageContaining("No feasible local plan coverage");

        assertThat(generator.receivedSkeleton).isNull();
    }

    private static PlanQualityService passingQualityService() {
        return draft -> new LocalPlanQualityReport(100, 0, 0, List.of());
    }

    private static PlanningZoneSummary zone(String zoneId, String name) {
        return new PlanningZoneSummary(
                zoneId,
                name,
                "Brisbane",
                "URBAN_DISTRICT",
                List.of("culture"),
                new ZoneCapabilitySummary(3, 4, 0, 2, 2, 1, java.util.Map.of("museum", 1), java.util.Map.of("culture", 2)),
                "FULL_DAY"
        );
    }

    private static class CapturingDestinationCatalog implements DestinationCatalog {
        private final List<PlanningZoneSummary> availableZones;
        private List<PlanningZoneSummary> orderedZonesPassedToSkeleton = List.of();
        private TripPlanningSpecification specificationPassedToSkeleton;

        protected CapturingDestinationCatalog(List<PlanningZoneSummary> availableZones) {
            this.availableZones = availableZones;
        }

        @Override
        public List<PlanningZoneSummary> findAvailableZones(TripPlanningSpecification specification) {
            return availableZones;
        }

        @Override
        public TripSkeleton buildTripSkeleton(TripPlanningSpecification specification) {
            return buildTripSkeleton(specification, availableZones);
        }

        @Override
        public TripSkeleton buildTripSkeleton(TripPlanningSpecification specification, List<PlanningZoneSummary> orderedZones) {
            specificationPassedToSkeleton = specification;
            orderedZonesPassedToSkeleton = orderedZones == null ? List.of() : List.copyOf(orderedZones);
            String zoneId = orderedZonesPassedToSkeleton.isEmpty() ? null : orderedZonesPassedToSkeleton.getFirst().zoneId();
            return new TripSkeleton(List.of(new TripSkeleton.DaySkeleton(
                    1,
                    "Retrieved zone day",
                    zoneId,
                    "09:00",
                    List.of(new TripSlot("day1-activity-1", TripSlot.SlotType.ACTIVITY, zoneId, List.of(), 90, null))
            )));
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool
        ) {
            return CoverageResult.sufficient();
        }

        @Override
        public PlaceCandidatePool buildCandidatePool(TripPlanningSpecification specification) {
            return PlaceCandidatePool.empty("Brisbane");
        }
    }

    private static class HardGapDestinationCatalog extends CapturingDestinationCatalog {
        private HardGapDestinationCatalog(List<PlanningZoneSummary> availableZones) {
            super(availableZones);
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool
        ) {
            return hardGap();
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool,
                List<thesis.project.gu.catalog.domain.AvailableZoneSummary> availableZoneSummaries
        ) {
            return hardGap();
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool,
                List<thesis.project.gu.catalog.domain.AvailableZoneSummary> availableZoneSummaries,
                List<PlanningZoneSummary> planningZones
        ) {
            return hardGap();
        }

        private CoverageResult hardGap() {
            return new CoverageResult(
                    false,
                    false,
                    List.of(new thesis.project.gu.catalog.domain.CoverageGap(
                            1,
                            "brisbane-south-bank",
                            "DINNER",
                            List.of(),
                            1,
                            2,
                            0
                    )),
                    List.of()
            );
        }
    }

    private static class EnrichableHardGapDestinationCatalog extends HardGapDestinationCatalog {
        private EnrichableHardGapDestinationCatalog(List<PlanningZoneSummary> availableZones) {
            super(availableZones);
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool
        ) {
            return candidatePool != null && candidatePool.restaurants() != null && !candidatePool.restaurants().isEmpty()
                    ? CoverageResult.sufficient()
                    : super.checkCoverage(specification, skeleton, candidatePool);
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool,
                List<thesis.project.gu.catalog.domain.AvailableZoneSummary> availableZoneSummaries
        ) {
            return checkCoverage(specification, skeleton, candidatePool);
        }

        @Override
        public CoverageResult checkCoverage(
                TripPlanningSpecification specification,
                TripSkeleton skeleton,
                PlaceCandidatePool candidatePool,
                List<thesis.project.gu.catalog.domain.AvailableZoneSummary> availableZoneSummaries,
                List<PlanningZoneSummary> planningZones
        ) {
            return checkCoverage(specification, skeleton, candidatePool);
        }
    }

    private static class CapturingExternalPoiEnrichmentService implements ExternalPoiEnrichmentService {
        private ExternalPoiEnrichmentRequest request;

        @Override
        public ExternalPoiEnrichmentResult enrich(ExternalPoiEnrichmentRequest request) {
            this.request = request;
            PlaceCandidate externalDinner = new PlaceCandidate(
                    "External Dinner",
                    PlaceCandidateType.RESTAURANT,
                    "restaurant",
                    "Brisbane",
                    "South Bank",
                    "1 External St",
                    -27.47,
                    153.02,
                    60,
                    List.of("dining"),
                    List.of("evening"),
                    70,
                    true,
                    "medium",
                    List.of("dinner"),
                    null,
                    "EXTERNAL_TEST",
                    "ENRICHED"
            );
            return new ExternalPoiEnrichmentResult(
                    request.candidatePool().withAdditionalCandidates(List.of(externalDinner)),
                    1,
                    List.of()
            );
        }
    }

    private static class CapturingItineraryGenerator implements ItineraryGenerator {
        private TripSkeleton receivedSkeleton;
        private PlaceCandidatePool receivedCandidatePool;

        @Override
        public PlanDraft generate(TripPlanningSpecification specification, PlaceCandidatePool candidatePool) {
            return generate(specification, candidatePool, null);
        }

        @Override
        public PlanDraft generate(
                TripPlanningSpecification specification,
                PlaceCandidatePool candidatePool,
                TripSkeleton skeleton
        ) {
            receivedSkeleton = skeleton;
            receivedCandidatePool = candidatePool;
            return new PlanDraft(
                    "Brisbane",
                    "Australia",
                    1,
                    "AUD",
                    new PlanDraft.Party(2, 0),
                    "normal",
                    "Title",
                    "Overview",
                    new ArrayList<>(),
                    "local-fast"
            );
        }
    }

    private static class NoopOperations extends GenerateInitialPlanUseCase.Operations {
        @Override
        PlanDraftResponse withCopyPolishStatus(PlanDraftResponse draft, String status) {
            return draft;
        }

        @Override
        PlanRepairPipelineService.ProcessAttemptResult processAttemptWithJsonRecovery(
                CreatePlanReq req,
                String raw,
                String attemptLabel,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                List<thesis.project.gu.planning.metrics.PlanStageMetrics> qualityStages,
                Object recoveryContext
        ) {
            return null;
        }

        @Override
        PlanDraftResponse validateAndRetry(
                CreatePlanReq req,
                String raw,
                boolean redisHit,
                long totalStartedAt,
                StringBuilder stageSummary,
                StringBuilder timingSummary,
                PlanRepairPipelineService.ProcessAttemptResult initialAttempt,
                List<thesis.project.gu.planning.metrics.PlanStageMetrics> qualityStages,
                boolean deferCopyPolish
        ) {
            return null;
        }

        @Override
        void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs) {
        }

        @Override
        void logPlanStageSummary(StringBuilder stageSummary) {
        }

        @Override
        void logPlanStageTimingSummary(StringBuilder timingSummary) {
        }
    }

    private static class CapturingPlanningTraceRecorder implements PlanningTraceRecorder {
        private PlanningPipelineTrace trace;

        @Override
        public void record(PlanningPipelineTrace trace) {
            this.trace = trace;
        }
    }
}
