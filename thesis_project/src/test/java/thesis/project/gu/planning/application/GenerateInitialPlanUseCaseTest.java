package thesis.project.gu.planning.application;

import org.junit.jupiter.api.Test;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.catalog.application.DestinationCatalog;
import thesis.project.gu.catalog.application.CoverageGapResolutionService;
import thesis.project.gu.catalog.application.CoverageInventoryBuilder;
import thesis.project.gu.catalog.application.PlanningZoneRetrievalService;
import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.domain.ZoneCapabilitySummary;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripSlot;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;
import thesis.project.gu.planning.quality.PlanQualityService;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateInitialPlanUseCaseTest {
    @Test
    void structuredRetrievalOrderIsUsedToBuildSkeletonPassedToGenerator() throws Exception {
        PlanningZoneSummary catalogFirst = zone("brisbane-cbd", "CBD");
        PlanningZoneSummary retrievalFirst = zone("brisbane-south-bank", "South Bank");
        CapturingDestinationCatalog catalog = new CapturingDestinationCatalog(List.of(catalogFirst, retrievalFirst));
        CapturingItineraryGenerator generator = new CapturingItineraryGenerator();
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
                new CoverageInventoryBuilder(),
                new CoverageGapResolutionService(new CoverageInventoryBuilder()),
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

        assertThat(catalog.orderedZonesPassedToSkeleton)
                .extracting(PlanningZoneSummary::zoneId)
                .containsExactly("brisbane-south-bank", "brisbane-cbd");
        assertThat(catalog.specificationPassedToSkeleton.dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-south-bank");
        assertThat(generator.receivedSkeleton.days().getFirst().zoneId()).isEqualTo("brisbane-south-bank");
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
    void unresolvedHardGapDoesNotEnterNormalGenerationPath() {
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
        )).isInstanceOf(NavigatorException.class);

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

    private static class CapturingItineraryGenerator implements ItineraryGenerator {
        private TripSkeleton receivedSkeleton;

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
}
