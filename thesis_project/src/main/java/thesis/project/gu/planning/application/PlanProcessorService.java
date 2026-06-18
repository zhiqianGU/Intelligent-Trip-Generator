package thesis.project.gu.planning.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.routing.domain.RouteRecommendationContext;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.routing.application.MapService;
import thesis.project.gu.planning.ai.TripAiService;
import thesis.project.gu.infrastructure.cache.CacheSerive;
import thesis.project.gu.catalog.verification.HotelVerificationService;
import thesis.project.gu.catalog.verification.RestaurantVerificationService;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.planning.metrics.PlanStageMetrics;
import thesis.project.gu.planning.localfast.LocalPlanGeneratorService;
import thesis.project.gu.planning.quality.LocalPlanQualityDiagnosticService;
import thesis.project.gu.planning.quality.PlanQualityMetricsService;
import thesis.project.gu.planning.quality.PlanQualityReport;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.time.LocalTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Legacy facade for the original AI-first planning path.
 * New top-level generation flow is owned by {@link GenerateInitialPlanUseCase};
 * this class keeps compatibility entry points and supplies operations for legacy repair/retry internals.
 */
@Service
public class PlanProcessorService {
    private static final Logger log = LoggerFactory.getLogger(PlanProcessorService.class);
    private static final String RELAXED_VALIDATION_BENCHMARK_FLAG = "itrip.plan.validation.relaxedForBenchmark";
    private static final int AUTO_PHASED_RETRY_MIN_DAYS = 3;
    private static final int MAX_INVALID_JSON_REPAIR_ATTEMPTS = 2;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY_START_MINUTES = 9 * 60;
    private static final int DEFAULT_STAY_MINUTES = 60;
    public static final int LUNCH_EARLIEST_START_MINUTES = 11 * 60 + 15;
    private static final int LUNCH_PREFERRED_EARLIEST_START_MINUTES = 11 * 60 + 30;
    public static final int LUNCH_LATEST_START_MINUTES = 13 * 60;
    private static final int THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES = 14 * 60 + 30;
    public static final int DINNER_EARLIEST_START_MINUTES = 17 * 60 + 30;
    private static final int DINNER_PREFERRED_EARLIEST_START_MINUTES = 18 * 60;
    private static final int DINNER_PREFERRED_LATEST_START_MINUTES = 19 * 60 + 30;
    public static final int DINNER_LATEST_START_MINUTES = 20 * 60;
    public static final int THEME_PARK_DAY_DINNER_LATEST_START_MINUTES = 20 * 60 + 30;
    private static final int THEME_PARK_AFTERNOON_CONTINUATION_MINUTES = 60;
    private static final int CULTURAL_POI_LATEST_END_MINUTES = 17 * 60;
    private static final Duration STOP_LOCATION_CACHE_TTL = Duration.ofMinutes(10);
    private static final int COPY_POLISH_LONG_PLAN_DAY_THRESHOLD = 7;
    private static final long COPY_POLISH_LONG_PLAN_MAX_ELAPSED_MS = Duration.ofSeconds(120).toMillis();
    private static final int DETERMINISTIC_SMALL_GAP_OVERRUN_MAX_MINUTES = 30;
    private static final int GEOCODE_BULKHEAD_CONCURRENCY = 6;
    private static final Cache<String, StopLocation> STOP_LOCATION_L1_CACHE = Caffeine.newBuilder()
            .maximumSize(40_000)
            .expireAfterWrite(STOP_LOCATION_CACHE_TTL)
            .build();
    private static final Semaphore GEOCODE_BULKHEAD = new Semaphore(GEOCODE_BULKHEAD_CONCURRENCY, true);

    private final TripAiService aiService;
    private final CacheSerive cacheSerive;
    private final ObjectMapper objectMapper;
    private final HotelVerificationService hotelVerificationService;
    private final MealRepairService mealRepairService;
    private final MapService mapService;
    private final GooglePlacesClient googlePlacesClient;
    private final PlanQualityMetricsService planQualityMetricsService;
    private final DaySkeletonService daySkeletonService;
    private final PlaceHeuristicService placeHeuristicService;
    private final StringRedisTemplate stringRedisTemplate;
    private final PlanRequestContextBuilder planRequestContextBuilder;
    private final AiDraftGenerationService aiDraftGenerationService;
    private final GenerateInitialPlanUseCase generateInitialPlanUseCase;
    private final PlanRepairPipelineService planRepairPipelineService;
    private final PlanResponseAssembler planResponseAssembler;
    private final PlanRetryInstructionService planRetryInstructionService;
    private final ThemeParkGovernanceService themeParkGovernanceService;
    private final DuplicatePoiRepairService duplicatePoiRepairService;
    private final RoutePlanningService routePlanningService;
    private final CopyPolishService copyPolishService;
    private final MealTimeWindowRepairService mealTimeWindowRepairService;
    private final PlanRetryOrchestrationService planRetryOrchestrationService;
    private final DefaultPlanQualityService planQualityService;

    @Autowired
    public PlanProcessorService(
            TripAiService aiService,
            CacheSerive cacheSerive,
            ObjectMapper objectMapper,
            HotelVerificationService hotelVerificationService,
            MealRepairService mealRepairService,
            MapService mapService,
            GooglePlacesClient googlePlacesClient,
            PlanQualityMetricsService planQualityMetricsService,
            DaySkeletonService daySkeletonService,
            PlaceHeuristicService placeHeuristicService,
            StringRedisTemplate stringRedisTemplate,
            PlanRequestContextBuilder planRequestContextBuilder,
            AiDraftGenerationService aiDraftGenerationService,
            GenerateInitialPlanUseCase generateInitialPlanUseCase,
            PlanRepairPipelineService planRepairPipelineService,
            PlanResponseAssembler planResponseAssembler,
            PlanRetryInstructionService planRetryInstructionService,
            ThemeParkGovernanceService themeParkGovernanceService,
            DuplicatePoiRepairService duplicatePoiRepairService,
            RoutePlanningService routePlanningService,
            CopyPolishService copyPolishService,
            MealTimeWindowRepairService mealTimeWindowRepairService,
            PlanRetryOrchestrationService planRetryOrchestrationService,
            DefaultPlanQualityService planQualityService
    ) {
        this.aiService = aiService;
        this.cacheSerive = cacheSerive;
        this.objectMapper = objectMapper;
        this.hotelVerificationService = hotelVerificationService;
        this.mealRepairService = mealRepairService;
        this.mapService = mapService;
        this.googlePlacesClient = googlePlacesClient;
        this.planQualityMetricsService = planQualityMetricsService;
        this.daySkeletonService = daySkeletonService;
        this.placeHeuristicService = placeHeuristicService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.planRequestContextBuilder = planRequestContextBuilder;
        this.aiDraftGenerationService = aiDraftGenerationService;
        this.generateInitialPlanUseCase = generateInitialPlanUseCase;
        this.planRepairPipelineService = planRepairPipelineService;
        this.planResponseAssembler = planResponseAssembler;
        this.planRetryInstructionService = planRetryInstructionService;
        this.themeParkGovernanceService = themeParkGovernanceService;
        this.duplicatePoiRepairService = duplicatePoiRepairService;
        this.routePlanningService = routePlanningService;
        this.copyPolishService = copyPolishService;
        this.mealTimeWindowRepairService = mealTimeWindowRepairService;
        this.planRetryOrchestrationService = planRetryOrchestrationService;
        this.planQualityService = planQualityService;
    }

    PlanProcessorService(
            TripAiService aiService,
            CacheSerive cacheSerive,
            ObjectMapper objectMapper,
            HotelVerificationService hotelVerificationService,
            RestaurantVerificationService restaurantVerificationService,
            MapService mapService,
            GooglePlacesClient googlePlacesClient,
            PlanQualityMetricsService planQualityMetricsService,
            DaySkeletonService daySkeletonService,
            PlaceHeuristicService placeHeuristicService,
            StringRedisTemplate stringRedisTemplate,
            LocalPlanGeneratorService localPlanGeneratorService,
            LocalPlanQualityDiagnosticService localPlanQualityDiagnosticService,
            DuplicatePoiRepairService duplicatePoiRepairService
    ) {
        this(
                aiService,
                cacheSerive,
                objectMapper,
                hotelVerificationService,
                new MealRepairService(restaurantVerificationService, new PlanResponseAssembler()),
                mapService,
                googlePlacesClient,
                planQualityMetricsService,
                daySkeletonService,
                placeHeuristicService,
                stringRedisTemplate,
                new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                new AiDraftGenerationService(aiService, objectMapper),
                new GenerateInitialPlanUseCase(
                        new PlanRequestContextBuilder(new PlanRequestNormalizer(), new PlanGenerationModeResolver()),
                        new LightweightRequestPreParser(),
                        new RetrievalQueryBuilder(),
                        new thesis.project.gu.catalog.local.StaticDestinationResolver(),
                        new thesis.project.gu.catalog.application.LocalPlanningZoneRetrievalService(),
                        new ZoneContextBuilder(),
                        new LocalFallbackPlanningAgent(),
                        new PlanningSpecificationValidator(),
                        new thesis.project.gu.catalog.local.LocalPoiCatalogService(objectMapper),
                        localPlanGeneratorService,
                        localPlanQualityDiagnosticService,
                        new DefaultRoutePlanningService(
                                new RouteAwareScheduleRepairService(mapService, objectMapper, stringRedisTemplate, placeHeuristicService, daySkeletonService)
                        ),
                        new DefaultPlanQualityService(
                                new DraftValidationService(
                                        daySkeletonService,
                                        placeHeuristicService,
                                        new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService)
                                ),
                                new DeterministicPlanRepairService(
                                        duplicatePoiRepairService,
                                        new MealTimeWindowRepairService(
                                                daySkeletonService,
                                                new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService)
                                        )
                                ),
                                new PostRoutePlanRepairService(new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService), daySkeletonService)
                        ),
                        new AiDraftGenerationService(aiService, objectMapper)
                ),
                new PlanRepairPipelineService(restaurantVerificationService),
                new PlanResponseAssembler(),
                new PlanRetryInstructionService(),
                new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService),
                duplicatePoiRepairService,
                new DefaultRoutePlanningService(
                        new RouteAwareScheduleRepairService(mapService, objectMapper, stringRedisTemplate, placeHeuristicService, daySkeletonService)
                ),
                new CopyPolishService(aiService, new PlanFinalizationService()),
                new MealTimeWindowRepairService(
                        daySkeletonService,
                        new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService)
                ),
                new PlanRetryOrchestrationService(
                        new PlanRepairPipelineService(restaurantVerificationService),
                        new DeterministicPlanRepairService(
                                duplicatePoiRepairService,
                                new MealTimeWindowRepairService(
                                        daySkeletonService,
                                        new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService)
                                )
                        )
                ),
                new DefaultPlanQualityService(
                        new DraftValidationService(
                                daySkeletonService,
                                placeHeuristicService,
                                new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService)
                        ),
                        new DeterministicPlanRepairService(
                                duplicatePoiRepairService,
                                new MealTimeWindowRepairService(
                                        daySkeletonService,
                                        new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService)
                                )
                        ),
                        new PostRoutePlanRepairService(new ThemeParkGovernanceService(googlePlacesClient, placeHeuristicService), daySkeletonService)
                )
        );
    }

    PlanDraftResponse processExistingRawDraft(CreatePlanReq req, String raw, boolean redisHit, long aiGenerationMs) throws Exception {
        PlanRequestContextBuilder.PlanRequestContext context = planRequestContextBuilder.build(req, redisHit, false);
        return generateInitialPlanUseCase.processGeneratedRawDraft(
                context.request(),
                raw,
                context.redisHit(),
                aiGenerationMs,
                context.deferCopyPolish(),
                initialPlanOperations()
        );
    }

    public PlanDraftResponse generateDraft(CreatePlanReq req, boolean redisHit) throws Exception {
        return generateDraft(req, redisHit, false);
    }

    public PlanDraftResponse generateDraft(CreatePlanReq req, boolean redisHit, boolean deferCopyPolish) throws Exception {
        return generateInitialPlanUseCase.execute(req, redisHit, deferCopyPolish, initialPlanOperations());
    }

    private GenerateInitialPlanUseCase.Operations initialPlanOperations() {
        return new GenerateInitialPlanUseCase.Operations() {
            @Override
            PlanDraftResponse withCopyPolishStatus(PlanDraftResponse draft, String status) {
                return PlanProcessorService.this.withCopyPolishStatus(draft, status);
            }

            @Override
            PlanRepairPipelineService.ProcessAttemptResult processAttemptWithJsonRecovery(
                    CreatePlanReq req,
                    String raw,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary,
                    List<PlanStageMetrics> qualityStages,
                    Object recoveryContext
            ) throws Exception {
                return PlanProcessorService.this.processAttemptWithJsonRecovery(
                        req,
                        raw,
                        attemptLabel,
                        stageSummary,
                        timingSummary,
                        qualityStages,
                        recoveryContext instanceof DaySkeletonContext daySkeletonContext ? daySkeletonContext : null
                );
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
                    List<PlanStageMetrics> qualityStages,
                    boolean deferCopyPolish
            ) throws Exception {
                return PlanProcessorService.this.validateAndRetry(
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
            }

            @Override
            void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs) {
                PlanProcessorService.this.appendStageTiming(timingSummary, stage, elapsedMs);
            }

            @Override
            void logPlanStageSummary(StringBuilder stageSummary) {
                PlanProcessorService.this.logPlanStageSummary(stageSummary);
            }

            @Override
            void logPlanStageTimingSummary(StringBuilder timingSummary) {
                PlanProcessorService.this.logPlanStageTimingSummary(timingSummary);
            }
        };
    }

    private boolean isPhasedGenerationEnabled(CreatePlanReq req) {
        return planRequestContextBuilder.build(req, false, false).mode().phasedGeneration();
    }

    private PlanDraftResponse validateAndRetry(
            CreatePlanReq req,
            String raw,
            boolean redisHit,
            long totalStartedAt,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            PlanRepairPipelineService.ProcessAttemptResult initialAttempt,
            List<PlanStageMetrics> qualityStages,
            boolean deferCopyPolish
    ) throws Exception {
        return planRetryOrchestrationService.validateAndRetry(
                req,
                raw,
                redisHit,
                totalStartedAt,
                stageSummary,
                timingSummary,
                initialAttempt,
                qualityStages,
                deferCopyPolish,
                retryOrchestrationOperations()
        );
    }

    private PlanRetryOrchestrationService.Operations retryOrchestrationOperations() {
        return new PlanRetryOrchestrationService.Operations() {
            @Override
            public PlanRepairPipelineService.Operations repairOperations() {
                return PlanProcessorService.this.repairOperations();
            }

            @Override
            public PlanDraftResponse finishSuccessfulAttempt(
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
            ) {
                return PlanProcessorService.this.finishSuccessfulAttempt(
                        req,
                        verifiedDraft == null ? null : verifiedDraft.toResponse(),
                        timingSummary,
                        stageSummary,
                        totalStartedAt,
                        copyPolishTimingLabel,
                        deferCopyPolish,
                        retryUsed,
                        retryRescued,
                        qualityStages
                );
            }

            @Override
            public Object skeletonContext(CreatePlanReq req, PlanDraft draft) {
                return PlanProcessorService.this.skeletonContext(req, draft == null ? null : draft.toResponse());
            }

            @Override
            public String regenerateRetryAttempt(
                    CreatePlanReq req,
                    PlanDraft draft,
                    List<String> validationIssues,
                    Object retrySkeletonContext
            ) throws Exception {
                return PlanProcessorService.this.regenerateRetryAttempt(
                        req,
                        draft == null ? null : draft.toResponse(),
                        validationIssues,
                        (DaySkeletonContext) retrySkeletonContext
                );
            }

            @Override
            public PlanRepairPipelineService.ProcessAttemptResult processAttemptWithJsonRecovery(
                    CreatePlanReq req,
                    String raw,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary,
                    List<PlanStageMetrics> qualityStages,
                    Object retrySkeletonContext
            ) throws Exception {
                return PlanProcessorService.this.processAttemptWithJsonRecovery(
                        req,
                        raw,
                        attemptLabel,
                        stageSummary,
                        timingSummary,
                        qualityStages,
                        (DaySkeletonContext) retrySkeletonContext
                );
            }

            @Override
            public void evictAiPlanRaw(CreatePlanReq req) {
                cacheSerive.evictAiPlanRaw(req);
            }

            @Override
            public void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs) {
                PlanProcessorService.this.appendStageTiming(timingSummary, stage, elapsedMs);
            }

            @Override
            public void logPlanStageSummary(StringBuilder stageSummary) {
                PlanProcessorService.this.logPlanStageSummary(stageSummary);
            }

            @Override
            public void logPlanStageTimingSummary(StringBuilder timingSummary) {
                PlanProcessorService.this.logPlanStageTimingSummary(timingSummary);
            }

            @Override
            public String shortRawPreview(String raw) {
                return aiDraftGenerationService.shortRawPreview(raw);
            }

            @Override
            public boolean isRelaxedValidationBenchmarkEnabled() {
                return PlanProcessorService.this.isRelaxedValidationBenchmarkEnabled();
            }
        };
    }

    private boolean isRelaxedValidationBenchmarkEnabled() {
        return Boolean.parseBoolean(System.getProperty(RELAXED_VALIDATION_BENCHMARK_FLAG, "false"));
    }

    private String regenerateRetryAttempt(
            CreatePlanReq req,
            PlanDraftResponse draft,
            List<String> validationIssues,
            DaySkeletonContext retrySkeletonContext
    ) throws Exception {
        if (shouldUseDayLevelPhasedRetry(req, validationIssues)) {
            PlanDraftResponse phasedRetried = retryFailedDaysPhased(req, draft, validationIssues, retrySkeletonContext);
            if (phasedRetried != null) {
                return aiDraftGenerationService.serializeDraft(phasedRetried);
            }
        }
        if (shouldUsePhasedWholePlanRetry(req, draft, validationIssues)) {
            PlanDraftResponse phasedRetried = retryWholePlanPhased(req, draft, validationIssues, retrySkeletonContext);
            if (phasedRetried != null) {
                return aiDraftGenerationService.serializeDraft(phasedRetried);
            }
        }
        return regenerateWholePlanRetry(req, draft, validationIssues, retrySkeletonContext);
    }

    private boolean shouldUseDayLevelPhasedRetry(CreatePlanReq req, List<String> validationIssues) {
        if (!isEligibleForDayLevelRetry(validationIssues)) {
            return false;
        }
        if (isPhasedGenerationEnabled(req)) {
            return true;
        }
        int requestedDays = req == null ? 0 : req.days();
        if (requestedDays < AUTO_PHASED_RETRY_MIN_DAYS) {
            return false;
        }
        return !extractRetryDayIndexes(validationIssues).isEmpty();
    }

    private String regenerateWholePlanRetry(
            CreatePlanReq req,
            PlanDraftResponse failedDraft,
            List<String> validationIssues,
            DaySkeletonContext retrySkeletonContext
    ) throws Exception {
        String compactSkeletonHints = planRetryInstructionService.compactSkeletonHints(
                retrySkeletonHints(retrySkeletonContext),
                validationIssues
        );
        return aiDraftGenerationService.regenerateWholePlanRaw(
                req,
                planRetryInstructionService.wholePlanInstruction(
                        req,
                        validationIssues,
                        retrySkeletonHints(retrySkeletonContext),
                        failedDraft,
                        retryInstructionOperations()
                ),
                compactSkeletonHints
        );
    }

    private PlanRetryInstructionService.SkeletonHints retrySkeletonHints(DaySkeletonContext context) {
        if (context == null) {
            return null;
        }
        return new PlanRetryInstructionService.SkeletonHints(
                context.effectiveMinByDay(),
                context.promptHints(),
                context.promptHintsByDay()
        );
    }

    private PlanRetryInstructionService.Operations retryInstructionOperations() {
        return new PlanRetryInstructionService.Operations() {
            @Override
            boolean isStrictMealStop(Place stop) {
                return PlanProcessorService.this.isStrictMealStop(stop);
            }

            @Override
            boolean hasVerifiedMealStop(Place stop, String mealType) {
                return PlanProcessorService.this.hasVerifiedMealStop(stop, mealType);
            }

            @Override
            boolean hasMealSlot(Place stop, String slot) {
                return PlanProcessorService.this.hasMealSlot(stop, slot);
            }

            @Override
            boolean isCountedNonMealStop(Place stop) {
                return PlanProcessorService.this.isCountedNonMealStop(stop);
            }

            @Override
            int countNonMealStops(List<Place> stops) {
                return PlanProcessorService.this.countNonMealStops(stops);
            }

            @Override
            String normalizeSlot(String value) {
                return PlanProcessorService.this.normalizeSlot(value);
            }

            @Override
            String safeStopName(Place stop) {
                return PlanProcessorService.this.safeStopName(stop);
            }

            @Override
            String dayDuplicateRetryInstruction(int dayIndex, PlanDraftResponse failedDraft) {
                return PlanProcessorService.this.buildDayDuplicateRetryInstruction(dayIndex, failedDraft);
            }
        };
    }

    private boolean shouldUsePhasedWholePlanRetry(
            CreatePlanReq req,
            PlanDraftResponse failedDraft,
            List<String> validationIssues
    ) {
        if (validationIssues == null || validationIssues.isEmpty()
                || failedDraft == null
                || failedDraft.daysPlan() == null
                || failedDraft.daysPlan().isEmpty()) {
            return false;
        }
        int requestedDays = req == null ? 0 : req.days();
        if (requestedDays < 1) {
            return false;
        }
        if (requestedDays < AUTO_PHASED_RETRY_MIN_DAYS) {
            return false;
        }
        if (hasRequestedDayCountIssue(validationIssues) || failedDraft.daysPlan().size() != requestedDays) {
            return false;
        }
        return failedDraft.daysPlan().stream().allMatch(day -> day != null && day.dayIndex() > 0);
    }

    private PlanDraftResponse retryWholePlanPhased(
            CreatePlanReq req,
            PlanDraftResponse failedDraft,
            List<String> validationIssues,
            DaySkeletonContext skeletonContext
    ) throws Exception {
        if (!shouldUsePhasedWholePlanRetry(req, failedDraft, validationIssues)) {
            return null;
        }
        Map<Integer, List<String>> issuesByDay = groupIssuesByDay(validationIssues);
        List<Integer> targetDayIndexes = collectWholePlanPhasedRetryDayIndexes(failedDraft, validationIssues);
        if (targetDayIndexes.isEmpty()) {
            return null;
        }
        String nonDayIssueInstruction = planRetryInstructionService.wholePlanNonDayIssueInstruction(validationIssues);
        Map<Integer, String> retryInstructionsByDay = new java.util.LinkedHashMap<>();
        for (Integer dayIndex : targetDayIndexes) {
            List<String> dayIssues = issuesByDay.getOrDefault(dayIndex, List.of());
            retryInstructionsByDay.put(
                    dayIndex,
                    planRetryInstructionService.wholePlanDayInstruction(
                            dayIndex,
                            dayIssues,
                            failedDraft,
                            retrySkeletonHints(skeletonContext),
                            nonDayIssueInstruction,
                            retryInstructionOperations()
                    )
            );
        }
        return aiDraftGenerationService.regeneratePlanDaysPhased(req, failedDraft, targetDayIndexes, retryInstructionsByDay);
    }

    private List<Integer> collectWholePlanPhasedRetryDayIndexes(
            PlanDraftResponse failedDraft,
            List<String> validationIssues
    ) {
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()) {
            return List.of();
        }
        java.util.Set<Integer> targetDays = new java.util.LinkedHashSet<>(extractRetryDayIndexes(validationIssues));
        targetDays.addAll(collectConflictingDuplicateAnchorDays(failedDraft, validationIssues));
        if (targetDays.isEmpty()) {
            return failedDraft.daysPlan().stream()
                    .map(DayPlan::dayIndex)
                    .filter(dayIndex -> dayIndex > 0)
                    .distinct()
                    .sorted()
                    .toList();
        }
        return targetDays.stream()
                .filter(dayIndex -> dayIndex != null && dayIndex > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private List<Integer> collectConflictingDuplicateAnchorDays(
            PlanDraftResponse failedDraft,
            List<String> validationIssues
    ) {
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()
                || validationIssues == null || validationIssues.isEmpty()) {
            return List.of();
        }
        Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> anchorDays = new java.util.LinkedHashSet<>();
        java.util.Set<String> duplicateIssues = validationIssues.stream()
                .filter(issue -> issue != null && issue.endsWith("-duplicate-poi-across-days"))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (duplicateIssues.isEmpty()) {
            return List.of();
        }
        for (DayPlan day : failedDraft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                continue;
            }
            for (int i = 0; i < day.stops().size(); i++) {
                Place stop = day.stops().get(i);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    continue;
                }
                SeenPoiStop firstSeen = findCrossDaySeenPoi(duplicateKeys, day.dayIndex(), seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), i + 1, safeStopName(stop)), seenStops);
                    continue;
                }
                String issueCode = "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-duplicate-poi-across-days";
                if (duplicateIssues.contains(issueCode) && firstSeen.dayIndex() > 0) {
                    anchorDays.add(firstSeen.dayIndex());
                }
            }
        }
        return anchorDays.stream().sorted().toList();
    }

    private boolean hasRequestedDayCountIssue(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return false;
        }
        return validationIssues.stream().anyMatch(issue -> issue != null
                && (issue.startsWith("expected-") || issue.startsWith("declared-days-")));
    }

    private PlanDraftResponse finishSuccessfulAttempt(
            CreatePlanReq req,
            PlanDraftResponse draft,
            StringBuilder timingSummary,
            StringBuilder stageSummary,
            long totalStartedAt,
            String copyPolishTimingLabel,
            boolean deferCopyPolish,
            boolean retryUsed,
            boolean retryRescued,
            List<PlanStageMetrics> qualityStages
    ) {
        logPlanStageSummary(stageSummary);
        long elapsedBeforeCopyPolishMs = System.currentTimeMillis() - totalStartedAt;
        PlanDraftResponse polished = deferCopyPolish
                ? deferCopyPolish(draft, timingSummary, copyPolishTimingLabel)
                : polishCopySafely(
                        draft,
                        timingSummary,
                        copyPolishTimingLabel,
                        retryUsed,
                        elapsedBeforeCopyPolishMs
                );
        log.info("Plan generation completed city={} requestedDays={} declaredDays={} actualDayPlans={} copyPolishStatus={}",
                req == null ? null : req.city(),
                req == null ? null : req.days(),
                polished == null ? null : polished.days(),
                polished == null || polished.daysPlan() == null ? null : polished.daysPlan().size(),
                polished == null ? null : polished.copyPolishStatus());
        qualityStages.add(captureStageMetrics(copyPolishTimingLabel.replace("/copy-polish", "/final_output"), polished, req, null));
        logPlanQualityReport(planQualityMetricsService.buildReport(req, polished, retryUsed, retryRescued, qualityStages));
        appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
        logPlanStageTimingSummary(timingSummary);
        return polished;
    }

    private PlanRepairPipelineService.ProcessAttemptResult processAttempt(
            CreatePlanReq req,
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages
    ) throws Exception {
        return planRepairPipelineService.processAttempt(
                req,
                raw,
                attemptLabel,
                stageSummary,
                timingSummary,
                qualityStages,
                repairOperations()
        );
    }

    private PlanRepairPipelineService.Operations repairOperations() {
        return new PlanRepairPipelineService.Operations() {
            @Override
            PlanRepairPipelineService.ParseNormalizeResult parseAndNormalize(
                    String raw,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary
            ) throws Exception {
                return PlanProcessorService.this.parseAndNormalize(raw, attemptLabel, stageSummary, timingSummary);
            }

            @Override
            PlanRepairPipelineService.EntityVerificationResult verifyAndRepairEntities(
                    PlanDraft draft,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary
            ) {
                PlanRepairPipelineService.EntityVerificationResult verified =
                        PlanProcessorService.this.verifyAndRepairEntities(draft == null ? null : draft.toResponse(), attemptLabel, stageSummary, timingSummary);
                return new PlanRepairPipelineService.EntityVerificationResult(
                        verified.draft(),
                        verified.validationIssues()
                );
            }

            @Override
            PlanDraft applySemanticPruning(
                    PlanDraft draft,
                    CreatePlanReq req,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary
            ) {
                return PlanDraft.fromResponse(PlanProcessorService.this.applySemanticPruning(
                        draft == null ? null : draft.toResponse(), req, attemptLabel, stageSummary, timingSummary));
            }

            @Override
            PlanDraft applyThemeParkGovernance(
                    PlanDraft draft,
                    CreatePlanReq req,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary
            ) {
                return PlanDraft.fromResponse(PlanProcessorService.this.applyThemeParkGovernance(
                        draft == null ? null : draft.toResponse(), req, attemptLabel, stageSummary, timingSummary));
            }

            @Override
            PlanDraft applyRouteAwareScheduling(
                    PlanDraft draft,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary
            ) {
                return routePlanningService.applyRouteAwareScheduling(draft, attemptLabel, stageSummary, timingSummary);
            }

            @Override
            PlanDraft applyPostRouteRepair(
                    PlanDraft draft,
                    CreatePlanReq req,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary
            ) {
                return planQualityService.repairPostRoute(
                        draft,
                        req,
                        attemptLabel,
                        stageSummary,
                        timingSummary,
                        postRouteRepairOperations()
                );
            }

            @Override
            List<String> validateRepairedDraft(
                    PlanDraft draft,
                    CreatePlanReq req,
                    String attemptLabel,
                    StringBuilder timingSummary
            ) {
                long stageStartedAt = System.currentTimeMillis();
                PlanDraftResponse responseDraft = draft == null ? null : draft.toResponse();
                DaySkeletonContext skeletonContext = skeletonContext(req, responseDraft);
                List<String> validationIssues = validateDraft(responseDraft, req, skeletonContext);
                appendStageTiming(timingSummary, attemptLabel + "/validate", System.currentTimeMillis() - stageStartedAt);
                return validationIssues;
            }

            @Override
            PlanStageMetrics captureStageMetrics(
                    String stage,
                    PlanDraft draft,
                    CreatePlanReq req,
                    List<String> issues
            ) {
                return PlanProcessorService.this.captureStageMetrics(stage, draft == null ? null : draft.toResponse(), req, issues);
            }

            @Override
            void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs) {
                PlanProcessorService.this.appendStageTiming(timingSummary, stage, elapsedMs);
            }

            @Override
            PlanDraft localRescueBeforeRetryIfValid(
                    CreatePlanReq req,
                    PlanDraft draft,
                    List<String> validationIssues,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary,
                    List<PlanStageMetrics> qualityStages
            ) {
                return PlanDraft.fromResponse(PlanProcessorService.this.localRescueBeforeRetryIfValid(
                        req,
                        draft == null ? null : draft.toResponse(),
                        validationIssues,
                        stageSummary,
                        timingSummary,
                        qualityStages
                ));
            }

            @Override
            PlanRepairPipelineService.DeterministicFallbackResult deterministicRepairIfValid(
                    CreatePlanReq req,
                    PlanDraft draft,
                    List<String> validationIssues,
                    String attemptLabel,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary,
                    List<PlanStageMetrics> qualityStages
            ) {
                PlanRepairPipelineService.DeterministicFallbackResult result = PlanProcessorService.this.deterministicRepairIfValid(
                        req,
                        draft == null ? null : draft.toResponse(),
                        validationIssues,
                        attemptLabel,
                        stageSummary,
                        timingSummary,
                        qualityStages
                );
                return new PlanRepairPipelineService.DeterministicFallbackResult(
                        PlanDraft.fromResponse(result.draft().toResponse()),
                        result.validationIssues(),
                        result.accepted()
                );
            }

            @Override
            PlanRepairPipelineService.DeterministicFallbackResult deterministicRetryFallbackIfValid(
                    CreatePlanReq req,
                    PlanDraft draft,
                    List<String> validationIssues,
                    StringBuilder stageSummary,
                    StringBuilder timingSummary,
                    List<PlanStageMetrics> qualityStages
            ) {
                PlanRepairPipelineService.DeterministicFallbackResult result = PlanProcessorService.this.deterministicRetryFallbackIfValid(
                        req,
                        draft == null ? null : draft.toResponse(),
                        validationIssues,
                        stageSummary,
                        timingSummary,
                        qualityStages
                );
                return new PlanRepairPipelineService.DeterministicFallbackResult(
                        PlanDraft.fromResponse(result.draft().toResponse()),
                        result.validationIssues(),
                        result.accepted()
                );
            }

            @Override
            PlanDraft relaxedPaceFallbackIfValid(
                    PlanDraft draft,
                    CreatePlanReq req,
                    List<String> validationIssues
            ) {
                return PlanDraft.fromResponse(PlanProcessorService.this.relaxedPaceFallbackIfValid(
                        draft == null ? null : draft.toResponse(), req, validationIssues));
            }
        };
    }

    private PlanRepairPipelineService.ProcessAttemptResult processAttemptWithJsonRecovery(
            CreatePlanReq req,
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages,
            DaySkeletonContext skeletonContext
    ) throws Exception {
        String currentRaw = raw;
        JsonProcessingException lastParseFailure = null;
        DaySkeletonContext repairSkeletonContext = skeletonContext == null ? skeletonContext(req, null) : skeletonContext;
        for (int attempt = 0; attempt <= MAX_INVALID_JSON_REPAIR_ATTEMPTS; attempt++) {
            try {
                return processAttempt(req, currentRaw, attemptLabel, stageSummary, timingSummary, qualityStages);
            } catch (JsonProcessingException parseFailure) {
                lastParseFailure = parseFailure;
                if (attempt >= MAX_INVALID_JSON_REPAIR_ATTEMPTS) {
                    break;
                }
                log.warn("{} generated itinerary returned invalid JSON on parse attempt {}. req={} error={} rawPreview={}",
                        attemptLabel,
                        attempt + 1,
                        req,
                        parseFailure.getOriginalMessage(),
                        aiDraftGenerationService.shortRawPreview(currentRaw));
                long stageStartedAt = System.currentTimeMillis();
                currentRaw = aiDraftGenerationService.regenerateInvalidJsonRaw(
                        req,
                        isPhasedGenerationEnabled(req),
                        parseFailure,
                        repairSkeletonContext.promptHintsText()
                );
                appendStageTiming(
                        timingSummary,
                        attemptLabel + "/ai-regenerate-invalid-json-" + (attempt + 1),
                        System.currentTimeMillis() - stageStartedAt
                );
            }
        }
        throw lastParseFailure;
    }

    private PlanDraftResponse applySemanticPruning(
            PlanDraftResponse draft,
            CreatePlanReq req,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        long stageStartedAt = System.currentTimeMillis();
        PlanDraft pruned = planQualityService.pruneFlexibleFoodStops(PlanDraft.fromResponse(draft));
        draft = pruned == null ? null : pruned.toResponse();
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-flexible-prune", draft);
        pruned = planQualityService.pruneUnselectedShoppingStops(PlanDraft.fromResponse(draft), req);
        draft = pruned == null ? null : pruned.toResponse();
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-shopping-prune", draft);
        draft = normalizeDraftCoordinates(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-coordinate", draft);
        draft = pruneOutOfRangeThemeParkStops(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-theme-range-prune", draft);
        draft = pruneThemeParkDayTrips(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-theme-day-prune", draft);
        appendStageTiming(timingSummary, attemptLabel + "/pre-meal-prune-coordinate", System.currentTimeMillis() - stageStartedAt);
        return draft;
    }

    private PlanDraftResponse applyThemeParkGovernance(
            PlanDraftResponse draft,
            CreatePlanReq req,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        long stageStartedAt = System.currentTimeMillis();
        draft = verifyThemeParkStopsWithPlaces(draft);
        appendStageTiming(timingSummary, attemptLabel + "/theme-park-verify", System.currentTimeMillis() - stageStartedAt);

        stageStartedAt = System.currentTimeMillis();
        draft = expandThemeParkDiningBreaks(draft);
        PlanDraft areaPruned = planQualityService.pruneAreaInconsistentFlexibleStops(PlanDraft.fromResponse(draft), req);
        draft = areaPruned == null ? null : areaPruned.toResponse();
        appendStageTiming(timingSummary, attemptLabel + "/theme-park-area-prune", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-prune", draft);
        return draft;
    }

    private PostRoutePlanRepairService.Operations postRouteRepairOperations() {
        return new PostRoutePlanRepairService.Operations() {
            @Override
            public java.util.Set<Integer> detectChangedDayIndexes(PlanDraft before, PlanDraft after) {
                return PlanProcessorService.this.detectChangedDayIndexes(
                        before == null ? null : before.toResponse(),
                        after == null ? null : after.toResponse()
                );
            }

            @Override
            public PlanDraft normalizeDraftScheduleWithRouteDurations(PlanDraft draft, java.util.Set<Integer> targetDayIndexes) {
                return routePlanningService.normalizeScheduleWithRouteDurations(draft, targetDayIndexes);
            }

            @Override
            public PlanDraft repairCrossDayDuplicatePois(PlanDraft draft) {
                return duplicatePoiRepairService.repairCrossDayDuplicatePois(draft);
            }

            @Override
            public PlanDraft repairTimeSensitiveLateStops(PlanDraft draft) {
                return PlanDraft.fromResponse(PlanProcessorService.this.repairTimeSensitiveLateStops(draft == null ? null : draft.toResponse()));
            }

            @Override
            public PlanDraft clampOversizedGaps(PlanDraft draft) {
                return PlanDraft.fromResponse(PlanProcessorService.this.clampOversizedGaps(draft == null ? null : draft.toResponse()));
            }

            @Override
            public void appendStageTiming(StringBuilder timingSummary, String stage, long ms) {
                PlanProcessorService.this.appendStageTiming(timingSummary, stage, ms);
            }

            @Override
            public void logPlanStageCounts(StringBuilder stageSummary, String attemptLabel, String stage, PlanDraft draft) {
                PlanProcessorService.this.logPlanStageCounts(stageSummary, attemptLabel, stage, draft == null ? null : draft.toResponse());
            }
        };
    }

    private PlanRepairPipelineService.ParseNormalizeResult parseAndNormalize(
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) throws Exception {
        long stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse draft = aiDraftGenerationService.parseRawDraft(raw);
        appendStageTiming(timingSummary, attemptLabel + "/parse-json", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "raw", draft);

        stageStartedAt = System.currentTimeMillis();
        draft = normalizeDraftSchedule(draft);
        appendStageTiming(timingSummary, attemptLabel + "/normalize-schedule", System.currentTimeMillis() - stageStartedAt);

        return new PlanRepairPipelineService.ParseNormalizeResult(PlanDraft.fromResponse(draft));
    }

    private PlanRepairPipelineService.EntityVerificationResult verifyAndRepairEntities(
            PlanDraftResponse draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        long stageStartedAt = System.currentTimeMillis();
        var hotelVerification = hotelVerificationService.verifyAndNormalize(draft);
        draft = hotelVerification.draft();
        appendStageTiming(timingSummary, attemptLabel + "/hotel-verify", System.currentTimeMillis() - stageStartedAt);
        List<String> validationIssues = new ArrayList<>(hotelVerification.issues());

        MealRepairService.Result mealRepairResult = mealRepairService.verifyAndRepairMeals(
                draft,
                validationIssues,
                attemptLabel,
                stageSummary,
                timingSummary,
                mealRepairOperations()
        );
        return new PlanRepairPipelineService.EntityVerificationResult(
                PlanDraft.fromResponse(mealRepairResult.draft()),
                mealRepairResult.validationIssues()
        );
    }

    private MealRepairService.Operations mealRepairOperations() {
        return new MealRepairService.Operations() {
            @Override
            public PlanDraftResponse normalizeDraftSchedule(PlanDraftResponse draft) {
                return PlanProcessorService.this.normalizeDraftSchedule(draft);
            }

            @Override
            public void appendStageTiming(StringBuilder timingSummary, String stage, long elapsedMs) {
                PlanProcessorService.this.appendStageTiming(timingSummary, stage, elapsedMs);
            }

            @Override
            public void logPlanStageCounts(StringBuilder stageSummary, String attemptLabel, String stage, PlanDraftResponse draft) {
                PlanProcessorService.this.logPlanStageCounts(stageSummary, attemptLabel, stage, draft);
            }
        };
    }

    private PlanDraftResponse normalizeDraftSchedule(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            days.add(normalizeDaySchedule(day));
        }
        return planResponseAssembler.withDays(draft, days);
    }

    private DayPlan normalizeDaySchedule(DayPlan day) {
        List<Place> stops = day.stops() == null ? List.of() : new ArrayList<>(day.stops());
        if (stops.isEmpty()) return day;
        stops = reorderStopsByTimeSlotIfMealOrderInvalid(stops);
        List<Place> normalized = new ArrayList<>();
        int previousEnd = DAY_START_MINUTES;
        for (int i = 0; i < stops.size(); i++) {
            Place stop = stops.get(i);
            int stay = resolveStayMinutes(stop);
            int rollingStart = previousEnd + transitionMinutes(i == 0);
            int preferredStart = preferredStartMinutes(stop.timeSlot(), i == 0);
            int start = chooseScheduledStart(rollingStart, preferredStart, stop);
            int end = start + stay;
            normalized.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
            previousEnd = end;
        }
        return new DayPlan(day.dayIndex(), day.hotel(), normalized, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
    }

    public PlanDraftResponse normalizeDraftScheduleWithRouteDurations(PlanDraftResponse draft) {
        PlanDraft normalized = routePlanningService.normalizeScheduleWithRouteDurations(PlanDraft.fromResponse(draft));
        return normalized == null ? null : normalized.toResponse();
    }

    public PlanDraftResponse normalizeDraftScheduleWithRouteDurations(PlanDraftResponse draft, java.util.Set<Integer> targetDayIndexes) {
        PlanDraft normalized = routePlanningService.normalizeScheduleWithRouteDurations(PlanDraft.fromResponse(draft), targetDayIndexes);
        return normalized == null ? null : normalized.toResponse();
    }

    public PlanDraftResponse repairMealStops(PlanDraftResponse draft, List<String> issues) {
        return mealRepairService.repairMealStops(draft, issues, mealRepairOperations());
    }

    private List<String> crossDayDuplicatePoiKeys(Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank() || stop.mealType() != null || isFoodStop(stop)) {
            return List.of();
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        String category = normalizeCoordinateCategory(stop);
        String city = normalizeSlot(stop.city());
        String mapRef = stableMapReference(stop.googleMapsUri());
        if (mapRef.isBlank()) {
            mapRef = stableMapReference(stop.url());
        }
        if (!mapRef.isBlank()) {
            keys.add("map|" + mapRef);
        }
        String normalizedName = normalizedPoiIdentity(stop.name());
        if (!normalizedName.isBlank() && normalizedName.length() >= 4) {
            keys.add("name|" + category + "|" + city + "|" + normalizedName);
        }
        String addressKey = duplicateAddressKey(stop);
        if (!addressKey.isBlank()) {
            keys.add("addr|" + category + "|" + city + "|" + addressKey);
        }
        String coordinateKey = duplicateCoordinateKey(stop);
        if (!coordinateKey.isBlank()) {
            keys.add("geo|" + category + "|" + city + "|" + coordinateKey);
        }
        return new ArrayList<>(keys);
    }

    private SeenPoiStop findCrossDaySeenPoi(List<String> duplicateKeys, int dayIndex, Map<String, SeenPoiStop> seenStops) {
        if (duplicateKeys == null || duplicateKeys.isEmpty() || seenStops == null || seenStops.isEmpty()) {
            return null;
        }
        for (String key : duplicateKeys) {
            SeenPoiStop seen = seenStops.get(key);
            if (seen != null && seen.dayIndex() != dayIndex) {
                return seen;
            }
        }
        return null;
    }

    private void registerSeenPoiKeys(List<String> duplicateKeys, SeenPoiStop seenStop, Map<String, SeenPoiStop> seenStops) {
        if (duplicateKeys == null || duplicateKeys.isEmpty() || seenStop == null || seenStops == null) {
            return;
        }
        for (String key : duplicateKeys) {
            if (key != null && !key.isBlank()) {
                seenStops.putIfAbsent(key, seenStop);
            }
        }
    }

    private String stableMapReference(String uri) {
        String value = uri == null ? "" : uri.trim();
        if (value.isBlank()) {
            return "";
        }
        Matcher cidMatcher = Pattern.compile("(?i)(?:cid|place_id)=([^&?#/]+)").matcher(value);
        if (cidMatcher.find()) {
            return normalizeSlot(cidMatcher.group(1));
        }
        String normalized = normalizeNameForNarrativeMatch(value);
        return normalized.length() >= 12 ? normalized : "";
    }

    private String duplicateAddressKey(Place stop) {
        String address = normalizeNameForNarrativeMatch(String.join(" ",
                nullToEmpty(stop.addressLine()),
                nullToEmpty(stop.suburb()),
                nullToEmpty(stop.postcode())));
        return address.length() >= 10 ? address : "";
    }

    private String duplicateCoordinateKey(Place stop) {
        if (stop.latitude() == null || stop.longitude() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.4f,%.4f", stop.latitude(), stop.longitude());
    }

    private String safeStopName(Place stop) {
        return stop == null || stop.name() == null ? "" : stop.name();
    }

    private String joinText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private PlanDraftResponse retryFailedDaysPhased(
            CreatePlanReq req,
            PlanDraftResponse failedDraft,
            List<String> validationIssues,
            DaySkeletonContext skeletonContext
    ) throws Exception {
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()
                || validationIssues == null || validationIssues.isEmpty()) {
            return null;
        }
        if (!isEligibleForDayLevelRetry(validationIssues)) {
            return null;
        }
        List<Integer> targetDayIndexes = collectScopedRetryDayIndexesWithDuplicateAnchors(failedDraft, validationIssues);
        if (targetDayIndexes.isEmpty()) {
            return null;
        }
        Map<Integer, List<String>> issuesByDay = groupRetryIssuesByDay(failedDraft, validationIssues);
        if (issuesByDay.isEmpty()) {
            return null;
        }
        Map<Integer, String> retryInstructionsByDay = new java.util.LinkedHashMap<>();
        for (Integer dayIndex : targetDayIndexes) {
            List<String> dayIssues = issuesByDay.get(dayIndex);
            if (dayIssues == null || dayIssues.isEmpty()) {
                continue;
            }
            retryInstructionsByDay.put(dayIndex, planRetryInstructionService.dayInstruction(
                    dayIndex,
                    dayIssues,
                    failedDraft,
                    retrySkeletonHints(skeletonContext),
                    retryInstructionOperations()
            ));
        }
        if (retryInstructionsByDay.isEmpty()) {
            return null;
        }
        return aiDraftGenerationService.regeneratePlanDaysPhased(req, failedDraft, targetDayIndexes, retryInstructionsByDay);
    }

    private List<Integer> collectScopedRetryDayIndexesWithDuplicateAnchors(
            PlanDraftResponse failedDraft,
            List<String> validationIssues
    ) {
        java.util.Set<Integer> targetDays = new java.util.LinkedHashSet<>(extractRetryDayIndexes(validationIssues));
        targetDays.addAll(collectConflictingDuplicateAnchorDays(failedDraft, validationIssues));
        return targetDays.stream()
                .filter(dayIndex -> dayIndex != null && dayIndex > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private Map<Integer, List<String>> groupRetryIssuesByDay(
            PlanDraftResponse failedDraft,
            List<String> validationIssues
    ) {
        Map<Integer, List<String>> issuesByDay = new java.util.LinkedHashMap<>(groupIssuesByDay(validationIssues));
        Map<Integer, List<String>> anchorIssues = collectDuplicateAnchorIssuesByDay(failedDraft, validationIssues);
        anchorIssues.forEach((dayIndex, issues) -> issuesByDay.merge(
                dayIndex,
                new ArrayList<>(issues),
                (left, right) -> {
                    java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(left);
                    merged.addAll(right);
                    return new ArrayList<>(merged);
                }
        ));
        return issuesByDay;
    }

    private Map<Integer, List<String>> collectDuplicateAnchorIssuesByDay(
            PlanDraftResponse failedDraft,
            List<String> validationIssues
    ) {
        Map<Integer, List<String>> anchorIssues = new java.util.LinkedHashMap<>();
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()
                || validationIssues == null || validationIssues.isEmpty()) {
            return anchorIssues;
        }
        java.util.Set<String> duplicateIssues = validationIssues.stream()
                .filter(issue -> issue != null && issue.endsWith("-duplicate-poi-across-days"))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (duplicateIssues.isEmpty()) {
            return anchorIssues;
        }
        Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
        for (DayPlan day : failedDraft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                continue;
            }
            for (int i = 0; i < day.stops().size(); i++) {
                Place stop = day.stops().get(i);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    continue;
                }
                SeenPoiStop firstSeen = findCrossDaySeenPoi(duplicateKeys, day.dayIndex(), seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), i + 1, safeStopName(stop)), seenStops);
                    continue;
                }
                String duplicateIssue = "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-duplicate-poi-across-days";
                if (!duplicateIssues.contains(duplicateIssue) || firstSeen.dayIndex() <= 0) {
                    continue;
                }
                String anchorIssue = "day-" + firstSeen.dayIndex() + "-stop-" + firstSeen.stopIndex() + "-duplicate-poi-across-days";
                anchorIssues.computeIfAbsent(firstSeen.dayIndex(), ignored -> new ArrayList<>()).add(anchorIssue);
            }
        }
        return anchorIssues;
    }

    private boolean isEligibleForDayLevelRetry(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return false;
        }
        return validationIssues.stream().allMatch(this::isDayLevelRetryIssue);
    }

    private boolean isDayLevelRetryIssue(String issue) {
        if (issue == null || issue.isBlank()) {
            return false;
        }
        Integer dayIndex = extractDayIndex(issue);
        if (dayIndex == null || dayIndex < 1) {
            return false;
        }
        return issue.endsWith("-missing-lunch")
                || issue.endsWith("-missing-dinner")
                || issue.endsWith("-gap-too-large")
                || issue.endsWith("-google-places-low-confidence")
                || issue.endsWith("-google-places-no-match")
                || issue.endsWith("-time-sensitive-too-early")
                || issue.endsWith("-time-sensitive-too-late")
                || issue.endsWith("-time-sensitive-slot-mismatch")
                || issue.endsWith("-lunch-time-invalid")
                || issue.endsWith("-dinner-time-invalid")
                || issue.endsWith("-too-few-non-meal-stops")
                || issue.endsWith("-duplicate-poi-same-day")
                || issue.endsWith("-duplicate-poi-across-days")
                || issue.endsWith("-theme-park-cross-city");
    }

    private List<Integer> extractRetryDayIndexes(List<String> validationIssues) {
        return planRetryInstructionService.extractRetryDayIndexes(validationIssues);
    }

    private Map<Integer, List<String>> groupIssuesByDay(List<String> validationIssues) {
        return planRetryInstructionService.groupIssuesByDay(validationIssues);
    }

    private Integer extractDayIndex(String issue) {
        return planRetryInstructionService.extractDayIndex(issue);
    }

    private String buildDayDuplicateRetryInstruction(int dayIndex, PlanDraftResponse failedDraft) {
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()) {
            return "";
        }
        Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (DayPlan day : failedDraft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                continue;
            }
            for (int i = 0; i < day.stops().size(); i++) {
                Place stop = day.stops().get(i);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    continue;
                }
                SeenPoiStop firstSeen = findCrossDaySeenPoi(duplicateKeys, day.dayIndex(), seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), i + 1, safeStopName(stop)), seenStops);
                    continue;
                }
                if (day.dayIndex() != dayIndex) {
                    continue;
                }
                duplicates.add(safeStopName(stop) + " duplicates day " + firstSeen.dayIndex() + " stop " + firstSeen.stopIndex() + " (" + firstSeen.stopName() + ")");
            }
        }
        if (duplicates.isEmpty()) {
            return "";
        }
        return " Specifically replace these duplicates on day " + dayIndex + ": " + String.join("; ", duplicates) + ".";
    }

    private PlanDraftResponse relaxedPaceFallbackIfValid(
            PlanDraftResponse draft,
            CreatePlanReq req,
            List<String> validationIssues
    ) {
        if (draft == null || validationIssues == null || validationIssues.isEmpty()) {
            return null;
        }
        if (!"normal".equals(normalizePaceLabel(draft.pace())) && (req == null || !"normal".equals(normalizePaceLabel(req.pace())))) {
            return null;
        }
        boolean onlyTooFewNonMealStops = validationIssues.stream()
                .allMatch(issue -> issue != null && issue.matches("day-\\d+-too-few-non-meal-stops"));
        if (!onlyTooFewNonMealStops) {
            return null;
        }
        PlanDraftResponse relaxedDraft = planResponseAssembler.withPace(draft, "relaxed");
        CreatePlanReq relaxedReq = planResponseAssembler.withPace(req, "relaxed");
        List<String> relaxedIssues = validateDraft(relaxedDraft, relaxedReq);
        return relaxedIssues.isEmpty() ? relaxedDraft : null;
    }

    private int maxAllowedGapMinutes(Place previous, Place current, boolean finalStopOfDay) {
        return maxAllowedGapMinutes(previous, current, finalStopOfDay, -1);
    }

    private int maxAllowedGapMinutes(Place previous, Place current, boolean finalStopOfDay, int previousEndMinutes) {
        String previousSlot = normalizeSlot(previous == null ? null : previous.timeSlot());
        String currentSlot = normalizeSlot(current == null ? null : current.timeSlot());
        if (isThemeParkLikeStop(previous) || isThemeParkLikeStop(current)) {
            if (finalStopOfDay && ("dinner".equals(currentSlot) || "evening".equals(currentSlot))) {
                return THEME_PARK_DAY_DINNER_LATEST_START_MINUTES - (13 * 60 + THEME_PARK_AFTERNOON_CONTINUATION_MINUTES);
            }
            return 180;
        }
        if ("morning".equals(previousSlot) && "lunch".equals(currentSlot)) {
            return 90;
        }
        if ("morning".equals(previousSlot) && "afternoon".equals(currentSlot) && isMarketShoppingLikeStop(current)) {
            return 90;
        }
        if ("lunch".equals(previousSlot) && "sunset".equals(currentSlot)) {
            return 180;
        }
        if ("lunch".equals(previousSlot) && isLateDayViewStop(current)) {
            return 180;
        }
        if (finalStopOfDay && "lunch".equals(previousSlot) && ("dinner".equals(currentSlot) || "evening".equals(currentSlot))) {
            return 240;
        }
        if (finalStopOfDay && ("dinner".equals(currentSlot) || "evening".equals(currentSlot) || "night".equals(currentSlot))) {
            int waitUntilDinnerWindow = previousEndMinutes >= 0
                    ? Math.max(120, DINNER_EARLIEST_START_MINUTES - previousEndMinutes)
                    : 120;
            return Math.min(240, waitUntilDinnerWindow);
        }
        if ("lunch".equals(previousSlot) && "afternoon".equals(currentSlot)) {
            return isCulturalOpeningHoursConstrained(current) ? 90 : 75;
        }
        if (("afternoon".equals(previousSlot) || "sunset".equals(previousSlot))
                && ("dinner".equals(currentSlot) || "evening".equals(currentSlot))) {
            return 120;
        }
        return 60;
    }

    private boolean isLateDayViewStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String slot = normalizeSlot(stop.timeSlot());
        String category = normalizeSlot(stop.category());
        String text = joinText(stop.name(), stop.reason(), stop.tip());
        boolean viewLike = "lookout".equals(category)
                || "viewpoint".equals(category)
                || "landmark".equals(category)
                || text.contains("lookout")
                || text.contains("sunset")
                || text.contains("golden hour")
                || text.contains("harbour view")
                || text.contains("skyline view");
        return viewLike && ("sunset".equals(slot) || "afternoon".equals(slot) || "evening".equals(slot) || slot.isBlank());
    }

    private PlanDraftResponse polishCopySafely(
            PlanDraftResponse verifiedDraft,
            StringBuilder timingSummary,
            String timingLabel,
            boolean retryUsed,
            long elapsedBeforeCopyPolishMs
    ) {
        long startedAt = System.currentTimeMillis();
        String skipStatus = copyPolishSkipStatus(verifiedDraft, retryUsed, elapsedBeforeCopyPolishMs);
        if (skipStatus != null) {
            appendStageTiming(timingSummary, timingLabel, System.currentTimeMillis() - startedAt);
            return withCopyPolishStatus(verifiedDraft, skipStatus);
        }
        try {
            TripAiService.CopyPolishResult result = aiService.polishPlanCopy(verifiedDraft);
            appendStageTiming(timingSummary, timingLabel, System.currentTimeMillis() - startedAt);
            if (result.completed()) {
                return withCopyPolishStatus(copyPolishService.mergeAllowedCopyFields(verifiedDraft, result.draft()), "completed");
            }
            return withCopyPolishStatus(verifiedDraft, "fallback-" + result.status());
        } catch (Exception e) {
            appendStageTiming(timingSummary, timingLabel, System.currentTimeMillis() - startedAt);
            log.debug("Copy polish fallback to verified draft", e);
            return withCopyPolishStatus(verifiedDraft, "error");
        }
    }

    private PlanDraftResponse deferCopyPolish(
            PlanDraftResponse verifiedDraft,
            StringBuilder timingSummary,
            String timingLabel
    ) {
        long startedAt = System.currentTimeMillis();
        appendStageTiming(timingSummary, timingLabel, System.currentTimeMillis() - startedAt);
        return withCopyPolishStatus(verifiedDraft, "deferred");
    }

    private String copyPolishSkipStatus(
            PlanDraftResponse verifiedDraft,
            boolean retryUsed,
            long elapsedBeforeCopyPolishMs
    ) {
        if (retryUsed) {
            return "skipped-retry-used";
        }
        if (containsFallbackStroll(verifiedDraft)) {
            return "skipped-fallback-stroll";
        }
        if (isLongPlanCopyPolishTimeoutCandidate(verifiedDraft, elapsedBeforeCopyPolishMs)) {
            return "skipped-long-plan-over-budget";
        }
        return null;
    }

    private boolean isLongPlanCopyPolishTimeoutCandidate(PlanDraftResponse draft, long elapsedBeforeCopyPolishMs) {
        if (draft == null || draft.days() <= COPY_POLISH_LONG_PLAN_DAY_THRESHOLD) {
            return false;
        }
        return elapsedBeforeCopyPolishMs > COPY_POLISH_LONG_PLAN_MAX_ELAPSED_MS;
    }

    private boolean containsFallbackStroll(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return false;
        }
        for (DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                continue;
            }
            for (Place stop : day.stops()) {
                if (isFallbackStrollStop(stop)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFallbackStrollStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String name = safeStopName(stop).toLowerCase(Locale.ROOT);
        return name.endsWith(" heritage stroll")
                || name.contains(" heritage stroll alt ")
                || name.endsWith(" garden stroll")
                || name.contains(" garden stroll alt ")
                || name.endsWith(" scenic stroll")
                || name.contains(" scenic stroll alt ")
                || name.endsWith(" local stroll")
                || name.contains(" local stroll alt ")
                || name.endsWith(" neighborhood stroll")
                || name.contains(" neighborhood stroll alt ");
    }

    private PlanDraftResponse withCopyPolishStatus(PlanDraftResponse draft, String status) {
        return copyPolishService.withCopyPolishStatus(draft, status);
    }

    private String normalizeNameForNarrativeMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private StopCoordinate cityCenterCoordinate(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return switch (city.trim().toLowerCase(Locale.ROOT)) {
            case "brisbane" -> new StopCoordinate(-27.4705, 153.0260);
            case "sydney" -> new StopCoordinate(-33.8688, 151.2093);
            case "melbourne" -> new StopCoordinate(-37.8136, 144.9631);
            default -> null;
        };
    }

    private StopCoordinate coordinateOf(Place stop) {
        if (stop == null || stop.latitude() == null || stop.longitude() == null) {
            return null;
        }
        return new StopCoordinate(stop.latitude(), stop.longitude());
    }

    private boolean hasVerifiedMealStop(Place stop, String mealSlot) {
        if (!hasMealSlot(stop, mealSlot) || stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if (!isMealCategory(category)) {
            return false;
        }
        String status = stop.businessStatus() == null ? "" : stop.businessStatus().trim().toUpperCase(Locale.ROOT);
        return status.isBlank() || "OPERATIONAL".equals(status);
    }

    public PlanDraftResponse pruneFlexibleFoodStops(PlanDraftResponse draft) {
        PlanDraft pruned = planQualityService.pruneFlexibleFoodStops(PlanDraft.fromResponse(draft));
        return pruned == null ? null : pruned.toResponse();
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "bar".equals(category)
                || "bakery".equals(category);
    }

    private boolean shouldCreateCulturalClosingProblem(Place stop) {
        if (stop == null || !isCulturalOpeningHoursConstrained(stop)) {
            return false;
        }
        int endMinutes = parseTimeMinutes(stop.endTime());
        return endMinutes > CULTURAL_POI_LATEST_END_MINUTES;
    }

    private boolean isCulturalOpeningHoursConstrained(Place stop) {
        if (stop == null) return false;
        String category = normalizeSlot(stop.category());
        return "museum".equals(category) || "gallery".equals(category) || "zoo".equals(category);
    }

    private int removableFlexibleIndexBefore(List<Place> stops, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (isFlexibleStop(stops.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFlexibleStop(Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "park".equals(category) || "nature".equals(category) || "shop".equals(category) || "market".equals(category);
    }

    public PlanDraftResponse pruneUnselectedShoppingStops(PlanDraftResponse draft, CreatePlanReq req) {
        PlanDraft pruned = planQualityService.pruneUnselectedShoppingStops(PlanDraft.fromResponse(draft), req);
        return pruned == null ? null : pruned.toResponse();
    }

    private boolean isMarketShoppingLikeStop(Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if ("shop".equals(category) || "shopping".equals(category) || "market".equals(category)) {
            return true;
        }
        String text = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase(Locale.ROOT);
        return text.contains("market")
                || text.contains("arcade")
                || text.contains("shopping")
                || text.contains("retail")
                || text.contains("food hall")
                || text.contains("bazaar");
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private PlanDraftResponse verifyThemeParkStopsWithPlaces(PlanDraftResponse draft) {
        return themeParkGovernanceService.verifyStopsWithPlaces(draft);
    }

    private ParsedAddress parseAustralianAddress(String formattedAddress, Place fallback) {
        String address = formattedAddress == null ? "" : formattedAddress.trim();
        String fallbackAddressLine = fallback == null ? "" : nullToEmpty(fallback.addressLine()).trim();
        String fallbackSuburb = fallback == null ? "" : nullToEmpty(fallback.suburb()).trim();
        String fallbackState = fallback == null ? "" : nullToEmpty(fallback.state()).trim();
        String fallbackPostcode = fallback == null ? "" : nullToEmpty(fallback.postcode()).trim();
        String fallbackCountry = fallback == null ? "" : nullToEmpty(fallback.country()).trim();
        if (address.isBlank()) {
            return new ParsedAddress(fallbackAddressLine, fallbackSuburb, fallbackState, fallbackPostcode, fallbackCountry);
        }

        String[] parts = address.split(",");
        String addressLine = parts.length > 0 ? parts[0].trim() : address;
        String suburb = "";
        String state = "";
        String postcode = "";
        String country = parseCountryFromAddressParts(parts, fallbackCountry);
        Pattern statePostcodePattern = Pattern.compile("\\b([A-Z]{2,3})\\s+(\\d{4})\\b");
        for (String part : parts) {
            String trimmed = part.trim();
            Matcher matcher = statePostcodePattern.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            state = matcher.group(1);
            postcode = matcher.group(2);
            String beforeState = trimmed.substring(0, matcher.start()).trim();
            if (!beforeState.isBlank() && !looksLikeStreetAddress(beforeState)) {
                suburb = beforeState;
            }
        }
        return new ParsedAddress(
                addressLine.isBlank() ? fallbackAddressLine : addressLine,
                suburb.isBlank() ? fallbackSuburb : suburb,
                state.isBlank() ? fallbackState : state,
                postcode.isBlank() ? fallbackPostcode : postcode,
                country.isBlank() ? fallbackCountry : country
        );
    }

    private boolean looksLikeStreetAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.matches("(?i).*\\b(street|st|road|rd|avenue|ave|drive|dr|lane|ln|way|terrace|tce|place|pl|promenade|highway|hwy|parade|pde|circuit|crt)\\b.*");
    }

    private String parseCountryFromAddressParts(String[] parts, String fallbackCountry) {
        if (parts != null) {
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i] == null ? "" : parts[i].trim();
                if (part.equalsIgnoreCase("Australia")) {
                    return "Australia";
                }
            }
        }
        return fallbackCountry == null || fallbackCountry.isBlank() ? "Australia" : fallbackCountry;
    }

    private String sanitizeThemeParkReason(Place stop) {
        String area = displayArea(stop);
        String name = stop == null || stop.name() == null || stop.name().isBlank() ? "This theme park" : stop.name();
        return name + " works as the main theme park focus for the day around " + area + ".";
    }

    private String sanitizeThemeParkTip() {
        return "Check current opening hours and ticket details before committing to the day.";
    }

    private String sanitizeThemeParkCopy(String value) {
        return themeParkGovernanceService.sanitizeCopy(value);
    }

    private PlanDraftResponse pruneOutOfRangeThemeParkStops(PlanDraftResponse draft) {
        return themeParkGovernanceService.pruneOutOfRangeStops(draft);
    }

    private PlanDraftResponse pruneThemeParkDayTrips(PlanDraftResponse draft) {
        return themeParkGovernanceService.pruneDayTrips(draft);
    }

    public PlanDraftResponse expandThemeParkDiningBreaks(PlanDraftResponse draft) {
        return themeParkGovernanceService.expandDiningBreaks(draft);
    }

    private boolean isThemeParkLikeStop(Place stop) {
        return themeParkGovernanceService.isThemeParkLikeStop(stop);
    }

    private boolean isSameThemeParkCluster(Place themePark, Place stop) {
        return themeParkGovernanceService.isSameThemeParkCluster(themePark, stop);
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private int firstMealIndex(List<Place> stops, String mealType) {
        for (int i = 0; i < stops.size(); i++) {
            if (hasMealSlot(stops.get(i), mealType)) return i;
        }
        return -1;
    }

    private String normalizedAreaLabel(Place stop) {
        if (stop == null) return "";
        String area = normalizeSlot(stop.preferredArea());
        if (area.isEmpty()) area = normalizeSlot(stop.suburb());
        return area;
    }

    private boolean areasEquivalent(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) return true;
        return left.contains(right) || right.contains(left);
    }

    private boolean wouldDropBelowMinNonMealStops(List<Place> stops, Place stop, int minNonMealStops) {
        return isCountedNonMealStop(stop) && countNonMealStops(stops) <= minNonMealStops;
    }

    private int countNonMealStops(List<Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Place stop : stops) {
            if (isCountedNonMealStop(stop)) {
                count++;
            }
        }
        return count;
    }

    private boolean isCountedNonMealStop(Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        return !isMealCategory(normalizeSlot(stop.category()));
    }

    private boolean isStrongPoiCandidate(Place stop) {
        if (stop == null) return false;
        if (stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank()) return true;
        String category = normalizeSlot(stop.category());
        if ("museum".equals(category) || "gallery".equals(category) || "theme_park".equals(category) || "zoo".equals(category)) {
            return true;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
        return name.contains("cathedral") || name.contains("parliament") || name.contains("opera house") || name.contains("bridge") && name.contains("harbour");
    }

    private List<Place> reorderStopsByTimeSlotIfMealOrderInvalid(List<Place> stops) {
        if (stops == null || stops.size() < 2) {
            return stops == null ? List.of() : stops;
        }
        int lunchIndex = firstMealIndex(stops, "lunch");
        int dinnerIndex = firstMealIndex(stops, "dinner");
        boolean lunchAfterDinner = lunchIndex >= 0 && dinnerIndex >= 0 && lunchIndex > dinnerIndex;
        boolean lunchAfterLateDayStop = lunchIndex >= 0 && hasLateDayNonMealBefore(stops, lunchIndex);
        boolean dinnerBeforeMiddayStop = dinnerIndex >= 0 && hasMiddayOrAfternoonStopAfter(stops, dinnerIndex);
        if (!lunchAfterDinner && !lunchAfterLateDayStop && !dinnerBeforeMiddayStop) {
            return stops;
        }
        List<Place> reordered = new ArrayList<>(stops);
        reordered.sort((left, right) -> Integer.compare(slotSortOrder(left), slotSortOrder(right)));
        return reordered;
    }

    private boolean hasLateDayNonMealBefore(List<Place> stops, int index) {
        for (int i = 0; i < index; i++) {
            Place stop = stops.get(i);
            if (isStrictMealStop(stop)) {
                continue;
            }
            String slot = normalizeSlot(stop.timeSlot());
            if ("afternoon".equals(slot) || "sunset".equals(slot) || "evening".equals(slot) || "night".equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMiddayOrAfternoonStopAfter(List<Place> stops, int index) {
        for (int i = index + 1; i < stops.size(); i++) {
            Place stop = stops.get(i);
            if (isStrictMealStop(stop)) {
                continue;
            }
            String slot = normalizeSlot(stop.timeSlot());
            if ("morning".equals(slot) || "brunch".equals(slot) || "afternoon".equals(slot) || "sunset".equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private int slotSortOrder(Place stop) {
        String mealType = normalizeSlot(stop == null ? null : stop.mealType());
        String slot = normalizeSlot(stop == null ? null : stop.timeSlot());
        if ("lunch".equals(mealType) || "lunch".equals(slot)) {
            return 30;
        }
        if ("dinner".equals(mealType) || "dinner".equals(slot)) {
            return 70;
        }
        return switch (slot) {
            case "morning" -> 10;
            case "brunch" -> 20;
            case "afternoon" -> 40;
            case "sunset" -> 50;
            case "evening" -> 60;
            case "night" -> 80;
            default -> 100;
        };
    }

    private boolean isStrictMealStop(Place s) {
        String cat = normalizeSlot(s.category());
        return "restaurant".equals(cat) || "cafe".equals(cat) || "food".equals(cat);
    }

    private boolean isFoodStop(Place s) {
        if (s == null) {
            return false;
        }
        String cat = normalizeSlot(s.category());
        return "restaurant".equals(cat)
                || "cafe".equals(cat)
                || "food".equals(cat)
                || "dining".equals(cat);
    }

    private boolean hasMealSlot(Place s, String slot) {
        return s != null && (slot.equals(normalizeSlot(s.mealType())) || slot.equals(normalizeSlot(s.timeSlot())));
    }

    private String normalizeSlot(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private String normalizePaceLabel(String pace) {
        return daySkeletonService.normalizePace(pace);
    }

    private PaceNonMealRange paceNonMealRange(String pace) {
        DaySkeletonService.NonMealRange range = daySkeletonService.nonMealRangeForPace(pace);
        return new PaceNonMealRange(range.min(), range.max());
    }

    private DaySkeletonService.DaySkeletonBatch buildDaySkeletonBatch(CreatePlanReq req, PlanDraftResponse draft) {
        return daySkeletonService.build(req, draft);
    }

    private Map<Integer, Integer> effectiveMinNonMealStopsByDay(DaySkeletonContext skeletonContext, int fallbackMin) {
        return skeletonContext.effectiveMinByDay(fallbackMin);
    }

    private DaySkeletonContext skeletonContext(CreatePlanReq req, PlanDraftResponse draft) {
        return DaySkeletonContext.from(buildDaySkeletonBatch(req, draft), daySkeletonService);
    }

    private PlanDraftResponse normalizeDraftCoordinates(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> sourceDays = draft.daysPlan();
        List<DayPlan> days = new ArrayList<>(sourceDays.size());
        List<Place> allStops = new ArrayList<>();
        for (DayPlan day : sourceDays) {
            if (day == null) {
                continue;
            }
            if (day.hotel() != null) {
                allStops.add(day.hotel());
            }
            if (day.stops() != null && !day.stops().isEmpty()) {
                allStops.addAll(day.stops());
            }
        }
        Map<String, CompletableFuture<StopLocation>> deduplicatedFutures = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(4, allStops.size())));
        try {
            for (Place stop : allStops) {
                String key = stopCoordinateDedupeKey(stop);
                deduplicatedFutures.computeIfAbsent(
                        key,
                        ignored -> CompletableFuture.supplyAsync(() -> resolveStopLocationSafely(stop), executor)
                );
            }
            for (DayPlan day : sourceDays) {
                if (day == null) {
                    continue;
                }
                Place hotel = withResolvedCoordinate(day.hotel(), deduplicatedFutures);
                List<Place> stops = day.stops() == null
                        ? List.of()
                        : day.stops().stream().map(stop -> withResolvedCoordinate(stop, deduplicatedFutures)).toList();
                days.add(new DayPlan(day.dayIndex(), hotel, stops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
            }
        } finally {
            executor.shutdownNow();
        }
        return planResponseAssembler.withDays(draft, days);
    }

    private Place withResolvedCoordinate(Place stop) {
        return withResolvedCoordinate(stop, null);
    }

    private Place withResolvedCoordinate(Place stop, Map<String, CompletableFuture<StopLocation>> deduplicatedFutures) {
        if (stop == null) return null;
        if (stop.latitude() != null && stop.longitude() != null && !shouldRefreshCoordinate(stop)) {
            StopLocation existing = existingStopLocation(stop);
            if (existing != null) {
                STOP_LOCATION_L1_CACHE.put(stopCoordinateDedupeKey(stop), existing);
            }
            return stop;
        }
        StopLocation location = deduplicatedFutures == null
                ? resolveStopLocationSafely(stop)
                : joinStopLocationFuture(deduplicatedFutures.get(stopCoordinateDedupeKey(stop)));
        return location == null ? stop : copyPlaceWithLocation(stop, location);
    }

    private boolean shouldRefreshCoordinate(Place stop) {
        if (stop == null) {
            return false;
        }
        if (isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        if (isThemeParkLikeStop(stop)) {
            return true;
        }
        return placeHeuristicService.isNavigationAnchorCandidate(stop.name())
                || placeHeuristicService.isParkStopForCoordinateRefresh(stop)
                || isStrongPoiCandidate(stop);
    }

    public List<StopCoordinate> resolveStopCoordinatesInParallel(List<Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, stops.size()));
        try {
            Map<String, CompletableFuture<StopCoordinate>> deduplicatedFutures = new ConcurrentHashMap<>();
            List<CompletableFuture<StopCoordinate>> futures = new ArrayList<>(stops.size());
            for (Place stop : stops) {
                String key = stopCoordinateDedupeKey(stop);
                CompletableFuture<StopCoordinate> future = deduplicatedFutures.computeIfAbsent(
                        key,
                        ignored -> CompletableFuture.supplyAsync(() -> resolveStopCoordinateSafely(stop), executor)
                );
                futures.add(future);
            }
            return futures.stream().map(this::joinNullable).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private String stopCoordinateDedupeKey(Place stop) {
        if (stop == null) {
            return "null-stop";
        }
        return normalizeSlot(stop.name())
                + "|" + normalizeSlot(stop.addressLine())
                + "|" + normalizeSlot(stop.suburb())
                + "|" + normalizeSlot(stop.city())
                + "|" + normalizeSlot(stop.state())
                + "|" + normalizeSlot(stop.postcode())
                + "|" + normalizeSlot(stop.country())
                + "|" + normalizeSlot(stop.preferredArea())
                + "|" + normalizeSlot(stop.category())
                + "|" + normalizeSlot(stop.timeSlot())
                + "|" + normalizeSlot(stop.mealType())
                + "|" + (stop.latitude() == null ? "" : stop.latitude())
                + "|" + (stop.longitude() == null ? "" : stop.longitude());
    }

    private String stopCoordinateAliasKey(Place stop) {
        if (stop == null) {
            return "null-stop-alias";
        }
        return normalizedPoiIdentity(stop.name())
                + "|" + normalizeSlot(stop.city())
                + "|" + normalizeSlot(displayArea(stop))
                + "|" + normalizeCoordinateCategory(stop);
    }

    private String normalizeCoordinateCategory(Place stop) {
        if (stop == null) {
            return "";
        }
        String category = normalizeSlot(stop.category());
        return switch (category) {
            case "museum", "gallery", "cultural" -> "cultural";
            case "park", "nature", "outdoor" -> "park";
            case "lookout", "viewpoint", "landmark", "attraction" -> "attraction";
            case "restaurant", "cafe", "food", "dining" -> "food";
            default -> category;
        };
    }

    public StopCoordinate resolveStopCoordinateSafely(Place stop) {
        StopLocation location = resolveStopLocationSafely(stop);
        return location == null ? null : new StopCoordinate(location.lat(), location.lon());
    }

    private StopLocation resolveStopLocationSafely(Place stop) {
        StopLocation existingLocation = existingStopLocation(stop);
        try {
            if (stop != null && stop.latitude() != null && stop.longitude() != null && !shouldRefreshCoordinate(stop)) {
                if (existingLocation != null) {
                    cacheResolvedStopLocation(stop, existingLocation);
                }
                return existingLocation;
            }
            if (stop == null) {
                return null;
            }
            String cacheKey = stopCoordinateDedupeKey(stop);
            StopLocation cached = getCachedStopLocation(stop);
            if (cached != null) {
                return cached;
            }
            if (isRouteSuggestionOptional(stop) && stop.name() != null && !stop.name().isBlank()) {
                GeoResponse response = withBulkhead(
                        GEOCODE_BULKHEAD,
                        () -> mapService.geocodeWithoutBackfill(stop.name(), stop.city()),
                        null
                );
                StopCoordinate coordinate = placeHeuristicService.coordinateFromGeocode(response);
                if (coordinate != null && placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    StopLocation resolved = new StopLocation(coordinate.lat(), coordinate.lon(), null);
                    cacheResolvedStopLocation(stop, resolved);
                    return resolved;
                }
            }
            boolean navigationAnchorCandidate = placeHeuristicService.isNavigationAnchorCandidate(stop.name())
                    || placeHeuristicService.isParkStopForCoordinateRefresh(stop);
            for (String candidate : placeHeuristicService.geocodeCandidates(stop, isStrongPoiCandidate(stop), navigationAnchorCandidate)) {
                GeoResponse response = withBulkhead(
                        GEOCODE_BULKHEAD,
                        () -> mapService.geocode(candidate, stop.city()),
                        null
                );
                StopCoordinate coordinate = placeHeuristicService.coordinateFromGeocode(response);
                if (coordinate != null && placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    StopLocation resolved = new StopLocation(coordinate.lat(), coordinate.lon(), null);
                    cacheResolvedStopLocation(stop, resolved);
                    return resolved;
                }
            }
        } catch (RuntimeException e) {
            return existingLocation;
        }
        return existingLocation;
    }

    private StopLocation getCachedStopLocation(Place stop) {
        if (stop == null) {
            return null;
        }
        StopLocation exact = STOP_LOCATION_L1_CACHE.getIfPresent(stopCoordinateDedupeKey(stop));
        if (exact != null) {
            return exact;
        }
        String aliasKey = stopCoordinateAliasKey(stop);
        if (aliasKey.isBlank() || aliasKey.startsWith("|")) {
            return null;
        }
        return STOP_LOCATION_L1_CACHE.getIfPresent(aliasKey);
    }

    private void cacheResolvedStopLocation(Place stop, StopLocation location) {
        if (stop == null || location == null) {
            return;
        }
        STOP_LOCATION_L1_CACHE.put(stopCoordinateDedupeKey(stop), location);
        String aliasKey = stopCoordinateAliasKey(stop);
        if (!aliasKey.isBlank() && !aliasKey.startsWith("|")) {
            STOP_LOCATION_L1_CACHE.put(aliasKey, location);
        }
    }

    private StopLocation joinStopLocationFuture(CompletableFuture<StopLocation> future) {
        if (future == null) {
            return null;
        }
        try {
            return future.join();
        } catch (CompletionException ex) {
            return null;
        }
    }

    private <T> T withBulkhead(Semaphore semaphore, SupplierWithRuntimeException<T> supplier, T fallback) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return supplier.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (RuntimeException ex) {
            return fallback;
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    @FunctionalInterface
    private interface SupplierWithRuntimeException<T> {
        T get();
    }

    private StopLocation existingStopLocation(Place stop) {
        if (stop == null || stop.latitude() == null || stop.longitude() == null) {
            return null;
        }
        StopCoordinate coordinate = new StopCoordinate(stop.latitude(), stop.longitude());
        if (!placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
            return null;
        }
        return new StopLocation(stop.latitude(), stop.longitude(), null);
    }

    private record GapClampPair(Place previous, Place current) {}

    void clearRouteChoiceCrossRequestCache() {
        routePlanningService.clearRouteChoiceCrossRequestCache();
    }

    void clearRouteChoiceLocalCacheOnly() {
        routePlanningService.clearRouteChoiceLocalCacheOnly();
    }

    private StopCoordinate joinNullable(CompletableFuture<StopCoordinate> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return null;
        }
    }

    private PlanRepairPipelineService.DeterministicFallbackResult deterministicRetryFallbackIfValid(
            CreatePlanReq req,
            PlanDraftResponse retried,
            List<String> retryValidationIssues,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages
    ) {
        return deterministicRepairIfValid(
                req,
                retried,
                retryValidationIssues,
                "retry-1",
                stageSummary,
                timingSummary,
                qualityStages
        );
    }

    private PlanRepairPipelineService.DeterministicFallbackResult deterministicRepairIfValid(
            CreatePlanReq req,
            PlanDraftResponse draft,
            List<String> validationIssues,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages
    ) {
        if (draft == null || validationIssues == null || validationIssues.isEmpty()) {
            return null;
        }
        boolean deterministicRepairOnly = validationIssues.stream().allMatch(planQualityService::isDeterministicRepairIssue);
        if (!deterministicRepairOnly) {
            return null;
        }
        PlanDraftResponse repaired = applyDeterministicValidationRepair(draft, attemptLabel, stageSummary, timingSummary);
        List<String> repairedIssues = validateDraft(repaired, req);
        if (!repairedIssues.isEmpty()) {
            log.warn("Deterministic {} fallback still failed validation. originalIssues={} repairedIssues={}",
                    attemptLabel,
                    validationIssues,
                    repairedIssues);
        }
        qualityStages.add(captureStageMetrics(attemptLabel + "/deterministic_fallback", repaired, req, repairedIssues));
        return new PlanRepairPipelineService.DeterministicFallbackResult(PlanDraft.fromResponse(repaired), repairedIssues, repairedIssues.isEmpty());
    }

    private PlanDraftResponse localRescueBeforeRetryIfValid(
            CreatePlanReq req,
            PlanDraftResponse draft,
            List<String> validationIssues,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages
    ) {
        if (!isLocalRescueCandidate(validationIssues) || draft == null) {
            return null;
        }

        long stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse rescued = repairMealStops(draft, validationIssues);
        appendStageTiming(timingSummary, "initial/local-rescue-meals", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, "initial", "local-rescue-meals", rescued);

        List<String> deterministicIssuesAfterMealRepair = collectDeterministicValidationIssues(rescued);
        if (!deterministicIssuesAfterMealRepair.isEmpty()) {
            rescued = applyDeterministicValidationRepair(rescued, "initial-local-rescue", stageSummary, timingSummary);
        } else {
            appendStageTiming(timingSummary, "initial-local-rescue/deterministic-repair", 0);
            logPlanStageCounts(stageSummary, "initial-local-rescue", "deterministic-repair", rescued);
        }
        List<String> rescuedIssues = validateDraft(rescued, req);
        if (!rescuedIssues.isEmpty()) {
            log.warn("Initial local rescue still failed validation. originalIssues={} rescuedIssues={}",
                    validationIssues,
                    rescuedIssues);
        }
        qualityStages.add(captureStageMetrics("initial/local_rescue", rescued, req, rescuedIssues));
        return rescuedIssues.isEmpty() ? rescued : null;
    }

    private boolean isLocalRescueCandidate(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return false;
        }
        return validationIssues.stream().allMatch(this::isLocalRescueIssue);
    }

    private boolean isLocalRescueIssue(String issue) {
        if (issue == null || issue.isBlank()) {
            return false;
        }
        if (planQualityService.isDeterministicRepairIssue(issue)) {
            return true;
        }
        return issue.endsWith("-missing-lunch")
                || issue.endsWith("-missing-dinner")
                || issue.endsWith("-missing-real-lunch")
                || issue.endsWith("-missing-real-dinner")
                || issue.endsWith("-google-places-no-match")
                || issue.endsWith("-google-places-low-confidence");
    }

    private PlanDraftResponse applyDeterministicValidationRepair(
            PlanDraftResponse draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        PlanDraft repaired = planQualityService.repairDeterministically(
                PlanDraft.fromResponse(draft),
                attemptLabel,
                stageSummary,
                timingSummary,
                deterministicRepairOperations()
        );
        return repaired == null ? null : repaired.toResponse();
    }

    private List<String> collectDeterministicValidationIssues(PlanDraftResponse draft) {
        return planQualityService.collectDeterministicValidationIssues(PlanDraft.fromResponse(draft), deterministicRepairOperations());
    }

    private DeterministicPlanRepairService.Operations deterministicRepairOperations() {
        return new DeterministicPlanRepairService.Operations() {
            @Override
            public List<String> validateDraft(PlanDraft draft) {
                return PlanProcessorService.this.validateDraft(draft == null ? null : draft.toResponse());
            }

            @Override
            public PlanDraft repairTimeSensitiveLateStops(PlanDraft draft) {
                PlanDraftResponse repaired = PlanProcessorService.this.repairTimeSensitiveLateStops(draft == null ? null : draft.toResponse());
                return PlanDraft.fromResponse(repaired);
            }

            @Override
            public PlanDraft clampOversizedGaps(PlanDraft draft) {
                PlanDraftResponse repaired = PlanProcessorService.this.clampOversizedGaps(draft == null ? null : draft.toResponse());
                return PlanDraft.fromResponse(repaired);
            }

            @Override
            public PlanDraft bridgeSmallDeterministicGapOverruns(PlanDraft draft) {
                PlanDraftResponse repaired = PlanProcessorService.this.bridgeSmallDeterministicGapOverruns(draft == null ? null : draft.toResponse());
                return PlanDraft.fromResponse(repaired);
            }

            @Override
            public PlanDraft normalizeDraftScheduleWithRouteDurations(PlanDraft draft, java.util.Set<Integer> targetDayIndexes) {
                PlanDraftResponse repaired = PlanProcessorService.this.normalizeDraftScheduleWithRouteDurations(draft == null ? null : draft.toResponse(), targetDayIndexes);
                return PlanDraft.fromResponse(repaired);
            }

            @Override
            public java.util.Set<Integer> detectChangedDayIndexes(PlanDraft before, PlanDraft after) {
                return PlanProcessorService.this.detectChangedDayIndexes(
                        before == null ? null : before.toResponse(),
                        after == null ? null : after.toResponse()
                );
            }

            @Override
            public void appendStageTiming(StringBuilder timingSummary, String stage, long ms) {
                PlanProcessorService.this.appendStageTiming(timingSummary, stage, ms);
            }

            @Override
            public void logPlanStageCounts(StringBuilder stageSummary, String attemptLabel, String stage, PlanDraft draft) {
                PlanProcessorService.this.logPlanStageCounts(stageSummary, attemptLabel, stage, draft == null ? null : draft.toResponse());
            }
        };
    }

    PlanDraftResponse repairMealTimeWindowIssues(PlanDraftResponse draft) {
        return mealTimeWindowRepairService.repairResponse(draft);
    }

    private String normalizedPoiIdentity(String value) {
        String source = duplicateNameSource(value);
        String normalized = normalizeNameForNarrativeMatch(placeHeuristicService.corePoiName(source));
        if (normalized.isBlank()) {
            return "";
        }
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            String clean = normalizeSlot(token);
            if (clean.length() < 2 || isLowSignalDuplicateToken(clean)) {
                continue;
            }
            tokens.add(clean);
        }
        if (tokens.size() < 2) {
            return normalized.length() >= 4 ? normalized : "";
        }
        return tokens.stream().sorted().collect(Collectors.joining(" "));
    }

    private String duplicateNameSource(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\\(([^)]*)\\)").matcher(raw);
        StringBuilder parenthetical = new StringBuilder();
        while (matcher.find()) {
            String inside = matcher.group(1).trim();
            if (!inside.isBlank() && !isLikelyAcronymPhrase(inside)) {
                parenthetical.append(' ').append(inside);
            }
        }
        String outside = raw.replaceAll("\\([^)]*\\)", " ").trim();
        if (isLikelyAcronymPhrase(outside) && parenthetical.length() > 0) {
            return parenthetical.toString();
        }
        return (outside + " " + parenthetical).trim();
    }

    private boolean isLikelyAcronymPhrase(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) {
            return false;
        }
        String compact = candidate.replaceAll("[\\s&./-]+", "");
        return compact.length() >= 2 && compact.length() <= 8 && compact.matches("[A-Z0-9]+");
    }

    private boolean isLowSignalDuplicateToken(String token) {
        return switch (token) {
            case "the", "of", "and", "at", "in", "on", "for", "to", "a", "an",
                    "visit", "stop", "area", "precinct", "near", "nearby" -> true;
            default -> false;
        };
    }

    private PlanDraftResponse repairTimeSensitiveLateStops(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        RouteRecommendationContext ctx = routeSchedulingContext(draft);
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        List<DayPlan> repairedDays = draft.daysPlan().stream()
                .map(day -> repairDayTimeSensitiveLateStops(day, ctx, minNonMealStops))
                .toList();
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                repairedDays,
                draft.copyPolishStatus()
        );
    }

    private DayPlan repairDayTimeSensitiveLateStops(DayPlan day, RouteRecommendationContext ctx, int minNonMealStops) {
        List<Place> stops = day.stops() == null ? List.of() : day.stops();
        if (stops.isEmpty()) {
            return day;
        }
        List<Place> workingStops = new ArrayList<>(stops);
        boolean changed = false;
        int index = 0;
        while (index < workingStops.size()) {
            Place stop = workingStops.get(index);
            if (!shouldCreateCulturalClosingProblem(stop)) {
                index++;
                continue;
            }
            int previousEnd = index > 0 ? parseTimeMinutes(workingStops.get(index - 1).endTime()) : -1;
            int minStart = Math.max(
                    timeSensitiveEarliestStart(stop),
                    previousEnd >= 0 ? previousEnd + transitionMinutes(false) : DAY_START_MINUTES
            );
            Place shifted = shiftTimeSensitiveStopEarlier(stop, minStart);
            workingStops.set(index, shifted);
            if (!shouldCreateCulturalClosingProblem(shifted)) {
                changed = true;
                index++;
                continue;
            }
            int removableIndex = removableFlexibleIndexBefore(workingStops, index);
            if (removableIndex < 0) {
                index++;
                continue;
            }
            Place removable = workingStops.get(removableIndex);
            if (wouldDropBelowMinNonMealStops(workingStops, removable, minNonMealStops)) {
                index++;
                continue;
            }
            workingStops.remove(removableIndex);
            DayPlan normalizedDay = normalizeDayScheduleWithRouteDurations(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    workingStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ), ctx);
            workingStops = new ArrayList<>(normalizedDay.stops() == null ? List.of() : normalizedDay.stops());
            changed = true;
            index = 0;
        }
        if (!changed) {
            return day;
        }
        return new DayPlan(
                day.dayIndex(),
                day.hotel(),
                workingStops,
                day.theme(),
                day.morningNote(),
                day.afternoonNote(),
                day.eveningNote(),
                day.note()
        );
    }

    private Place shiftTimeSensitiveStopEarlier(Place stop, int minStart) {
        if (!shouldCreateCulturalClosingProblem(stop)) {
            return stop;
        }
        int start = parseTimeMinutes(stop.startTime());
        int end = parseTimeMinutes(stop.endTime());
        if (start < 0 || end < 0 || end <= start) {
            return stop;
        }
        int stay = resolveStayMinutes(stop);
        int targetLatestStart = CULTURAL_POI_LATEST_END_MINUTES - stay;
        if (targetLatestStart >= minStart) {
            int newStart = Math.min(start, targetLatestStart);
            int newEnd = newStart + stay;
            return copyPlaceWithTimes(stop, formatMinutes(newStart), formatMinutes(newEnd), stay);
        }
        if (minStart >= CULTURAL_POI_LATEST_END_MINUTES) {
            return stop;
        }
        int fallbackEnd = CULTURAL_POI_LATEST_END_MINUTES;
        int fallbackStart = Math.min(start, fallbackEnd - 30);
        fallbackStart = Math.max(minStart, fallbackStart);
        if (fallbackEnd <= fallbackStart) {
            return stop;
        }
        int adjustedStay = fallbackEnd - fallbackStart;
        return copyPlaceWithTimes(stop, formatMinutes(fallbackStart), formatMinutes(fallbackEnd), adjustedStay);
    }

    public PlanDraftResponse clampOversizedGaps(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = draft.daysPlan().stream().map(this::clampDayGaps).toList();
        return planResponseAssembler.withDays(draft, days);
    }

    private PlanDraftResponse bridgeSmallDeterministicGapOverruns(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        boolean changed = false;
        List<DayPlan> days = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            DayPlan adjustedDay = bridgeSmallDeterministicGapOverruns(day);
            if (adjustedDay != day) {
                changed = true;
            }
            days.add(adjustedDay);
        }
        if (!changed) {
            return draft;
        }
        return planResponseAssembler.withDays(draft, days);
    }

    private DayPlan bridgeSmallDeterministicGapOverruns(DayPlan day) {
        List<Place> stops = day == null || day.stops() == null ? List.of() : day.stops();
        if (stops.size() < 2) {
            return day;
        }
        List<Place> adjusted = new ArrayList<>(stops);
        boolean changed = false;
        for (int i = 1; i < adjusted.size(); i++) {
            Place previous = adjusted.get(i - 1);
            Place current = adjusted.get(i);
            int previousStart = parseTimeMinutes(previous.startTime());
            int previousEnd = parseTimeMinutes(previous.endTime());
            int currentStart = parseTimeMinutes(current.startTime());
            if (previousStart < 0 || previousEnd < 0 || currentStart < 0 || currentStart <= previousEnd) {
                continue;
            }
            int allowedGap = maxAllowedGapMinutes(previous, current, i == adjusted.size() - 1, previousEnd);
            int actualGap = currentStart - previousEnd;
            int overrun = actualGap - allowedGap;
            if (overrun <= 0 || overrun > DETERMINISTIC_SMALL_GAP_OVERRUN_MAX_MINUTES) {
                continue;
            }
            int latestPreviousEnd = currentStart - transitionMinutes(false);
            int targetPreviousEnd = Math.min(previousEnd + overrun, latestPreviousEnd);
            if (targetPreviousEnd <= previousEnd) {
                continue;
            }
            adjusted.set(i - 1, copyPlaceWithTimes(
                    previous,
                    formatMinutes(previousStart),
                    formatMinutes(targetPreviousEnd),
                    targetPreviousEnd - previousStart
            ));
            changed = true;
        }
        if (!changed) {
            return day;
        }
        return new DayPlan(
                day.dayIndex(),
                day.hotel(),
                adjusted,
                day.theme(),
                day.morningNote(),
                day.afternoonNote(),
                day.eveningNote(),
                day.note()
        );
    }

    private DayPlan clampDayGaps(DayPlan day) {
        List<Place> stops = day.stops() == null ? List.of() : day.stops();
        if (stops.size() < 2) return day;
        List<Place> adjusted = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Place stop = stops.get(i);
            int start = parseTimeMinutes(stop.startTime());
            int end = parseTimeMinutes(stop.endTime());
            int stay = resolveStayMinutes(stop);
            if (i > 0) {
                Place previous = adjusted.get(i - 1);
                int prevEnd = parseTimeMinutes(previous.endTime());
                if (shouldExtendThemeParkContinuationBeforeDinner(previous, stop, prevEnd, start)) {
                    Place extendedPrevious = extendThemeParkContinuationBeforeDinner(previous, start);
                    adjusted.set(i - 1, extendedPrevious);
                    prevEnd = parseTimeMinutes(extendedPrevious.endTime());
                }
                int allowedGap = maxAllowedGapMinutes(previous, stop, i == stops.size() - 1, prevEnd);
                if (start >= 0 && prevEnd >= 0 && hasMealSlot(stop, "dinner") && start - prevEnd > allowedGap) {
                    GapClampPair dinnerAdjusted = clampDinnerBoundGap(previous, stop, prevEnd, start, stay, i == stops.size() - 1);
                    previous = dinnerAdjusted.previous();
                    stop = dinnerAdjusted.current();
                    adjusted.set(i - 1, previous);
                    prevEnd = parseTimeMinutes(previous.endTime());
                    start = parseTimeMinutes(stop.startTime());
                    end = parseTimeMinutes(stop.endTime());
                    stay = resolveStayMinutes(stop);
                    allowedGap = maxAllowedGapMinutes(previous, stop, i == stops.size() - 1, prevEnd);
                }
                if (start >= 0 && prevEnd >= 0 && start - prevEnd > allowedGap) {
                    int minStart = Math.max(prevEnd + transitionMinutes(false), earliestAllowedStartForGapClamp(stop));
                    int targetStart = Math.min(start, prevEnd + allowedGap);
                    start = Math.max(minStart, targetStart);
                    end = start + stay;
                }
            }
            adjusted.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
        }
        return new DayPlan(day.dayIndex(), day.hotel(), adjusted, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
    }

    private GapClampPair clampDinnerBoundGap(
            Place previous,
            Place dinner,
            int previousEnd,
            int dinnerStart,
            int dinnerStay,
            boolean finalStopOfDay
    ) {
        if (previous == null || dinner == null || previousEnd < 0 || dinnerStart < 0 || !hasMealSlot(dinner, "dinner")) {
            return new GapClampPair(previous, dinner);
        }
        int allowedGap = maxAllowedGapMinutes(previous, dinner, finalStopOfDay, previousEnd);
        if (dinnerStart - previousEnd <= allowedGap) {
            return new GapClampPair(previous, dinner);
        }

        int earliestDinnerStart = Math.max(previousEnd + transitionMinutes(false), DINNER_EARLIEST_START_MINUTES);
        int targetDinnerStart = Math.max(earliestDinnerStart, previousEnd + allowedGap);
        if (targetDinnerStart < dinnerStart) {
            int newDinnerEnd = targetDinnerStart + dinnerStay;
            return new GapClampPair(
                    previous,
                    copyPlaceWithTimes(dinner, formatMinutes(targetDinnerStart), formatMinutes(newDinnerEnd), dinnerStay)
            );
        }

        int latestPreviousEnd = dinnerStart - transitionMinutes(false);
        int targetPreviousEnd = Math.min(previousEnd + (dinnerStart - previousEnd - allowedGap), latestPreviousEnd);
        int previousStart = parseTimeMinutes(previous.startTime());
        if (previousStart >= 0 && targetPreviousEnd > previousEnd && targetPreviousEnd > previousStart) {
            Place extendedPrevious = copyPlaceWithTimes(
                    previous,
                    formatMinutes(previousStart),
                    formatMinutes(targetPreviousEnd),
                    targetPreviousEnd - previousStart
            );
            return new GapClampPair(extendedPrevious, dinner);
        }
        return new GapClampPair(previous, dinner);
    }

    private boolean shouldExtendThemeParkContinuationBeforeDinner(Place previous, Place current, int previousEnd, int currentStart) {
        return themeParkGovernanceService.shouldExtendContinuationBeforeDinner(previous, current, previousEnd, currentStart);
    }

    private Place extendThemeParkContinuationBeforeDinner(Place previous, int dinnerStart) {
        return themeParkGovernanceService.extendContinuationBeforeDinner(previous, dinnerStart);
    }

    List<String> validateDraft(PlanDraftResponse draft) {
        return planQualityService.validate(PlanDraft.fromResponse(draft));
    }

    List<String> validateDraft(PlanDraftResponse draft, CreatePlanReq req) {
        return planQualityService.validate(PlanDraft.fromResponse(draft), req);
    }

    private List<String> validateDraft(PlanDraftResponse draft, CreatePlanReq req, DaySkeletonContext skeletonContext) {
        Map<Integer, Integer> effectiveMinByDay = null;
        if (skeletonContext != null && draft != null) {
            effectiveMinByDay = skeletonContext.effectiveMinByDay(minNonMealStopsPerDay(draft.pace()));
        }
        return planQualityService.validate(PlanDraft.fromResponse(draft), req, effectiveMinByDay);
    }

    private java.util.Set<Integer> detectChangedDayIndexes(PlanDraftResponse before, PlanDraftResponse after) {
        java.util.Set<Integer> changed = new java.util.LinkedHashSet<>();
        Map<Integer, DayPlan> beforeByDay = before == null || before.daysPlan() == null
                ? Map.of()
                : before.daysPlan().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DayPlan::dayIndex, day -> day, (left, right) -> left, java.util.LinkedHashMap::new));
        if (after == null || after.daysPlan() == null) {
            return changed;
        }
        for (DayPlan afterDay : after.daysPlan()) {
            if (afterDay == null) {
                continue;
            }
            DayPlan beforeDay = beforeByDay.get(afterDay.dayIndex());
            if (!Objects.equals(daySignature(beforeDay), daySignature(afterDay))) {
                changed.add(afterDay.dayIndex());
            }
        }
        return changed;
    }

    private String daySignature(DayPlan day) {
        if (day == null || day.stops() == null) {
            return "";
        }
        return day.stops().stream()
                .map(stop -> joinText(
                        stop == null ? null : stop.name(),
                        stop == null ? null : stop.startTime(),
                        stop == null ? null : stop.endTime(),
                        stop == null ? null : stop.mealType(),
                        stop == null ? null : stop.timeSlot()
                ))
                .collect(Collectors.joining("|"));
    }

    private int minNonMealStopsPerDay(String pace) {
        return daySkeletonService.nonMealRangeForPace(pace).min();
    }

    private boolean hasThemeParkBeforeIndex(List<Place> stops, int index) {
        if (stops == null || index <= 0) {
            return false;
        }
        for (int i = 0; i < Math.min(index, stops.size()); i++) {
            if (isThemeParkLikeStop(stops.get(i))) {
                return true;
            }
        }
        return false;
    }

    private RouteRecommendationContext routeSchedulingContext(PlanDraftResponse draft) {
        int kids = draft == null || draft.party() == null || draft.party().kids() == null ? 0 : draft.party().kids();
        return new RouteRecommendationContext(kids > 0, null, null);
    }

    private DayPlan normalizeDayScheduleWithRouteDurations(DayPlan day, RouteRecommendationContext ctx) {
        return normalizeDaySchedule(day);
    }

    PlanDraftResponse repairCrossDayDuplicatePois(PlanDraftResponse draft) {
        return duplicatePoiRepairService.repairCrossDayDuplicatePois(draft);
    }

    private int earliestAllowedStartForGapClamp(Place stop) {
        String slot = normalizeSlot(stop == null ? null : stop.timeSlot());
        if ("lunch".equals(slot) || hasMealSlot(stop, "lunch")) {
            return LUNCH_EARLIEST_START_MINUTES;
        }
        if ("dinner".equals(slot) || "evening".equals(slot) || hasMealSlot(stop, "dinner")) {
            return DINNER_EARLIEST_START_MINUTES;
        }
        return timeSensitiveEarliestStart(stop);
    }

    private String displayArea(Place stop) {
        if (stop == null) {
            return "the area";
        }
        if (stop.suburb() != null && !stop.suburb().isBlank()) {
            return stop.suburb().trim();
        }
        if (stop.preferredArea() != null && !stop.preferredArea().isBlank()) {
            return stop.preferredArea().trim();
        }
        if (stop.city() != null && !stop.city().isBlank()) {
            return stop.city().trim();
        }
        return "the area";
    }

    // Utility methods
    private int parseTimeMinutes(String val) {
        if (val == null || !val.matches("^\\d{2}:\\d{2}$")) return -1;
        return Integer.parseInt(val.substring(0, 2)) * 60 + Integer.parseInt(val.substring(3, 5));
    }

    private String formatMinutes(int min) {
        int n = Math.max(0, Math.min(min, 1439));
        return LocalTime.of(n / 60, n % 60).format(TIME_FORMATTER);
    }

    private int resolveStayMinutes(Place s) {
        if (s == null) return 60;
        if (s.stayMinutes() != null && s.stayMinutes() > 0) return s.stayMinutes();
        int start = parseTimeMinutes(s.startTime());
        int end = parseTimeMinutes(s.endTime());
        return (start >= 0 && end > start) ? (end - start) : 60;
    }

    private int transitionMinutes(boolean first) { return first ? 0 : 20; }

    private int preferredStartMinutes(String slot, boolean first) {
        return switch (normalizeSlot(slot)) {
            case "morning" -> first ? DAY_START_MINUTES : 9 * 60 + 30;
            case "brunch" -> 10 * 60 + 30;
            case "lunch" -> 12 * 60 + 15;
            case "afternoon" -> 14 * 60;
            case "sunset" -> 16 * 60 + 30;
            case "dinner", "evening" -> 18 * 60;
            case "night" -> 20 * 60;
            default -> first ? DAY_START_MINUTES : DAY_START_MINUTES + 60;
        };
    }

    private int chooseScheduledStart(int rollingStart, int preferredStart, Place stop) {
        String normalizedSlot = normalizeSlot(stop == null ? null : stop.timeSlot());
        int earliestAllowed = timeSensitiveEarliestStart(stop);
        rollingStart = Math.max(rollingStart, earliestAllowed);
        preferredStart = Math.max(preferredStart, earliestAllowed);
        if ("lunch".equals(normalizedSlot)) {
            return chooseLunchStart(rollingStart, preferredStart);
        }
        if ("dinner".equals(normalizedSlot) || "evening".equals(normalizedSlot)) {
            int earliestDinnerStart = Math.max(rollingStart, DINNER_EARLIEST_START_MINUTES);
            if (earliestDinnerStart > DINNER_LATEST_START_MINUTES) {
                return earliestDinnerStart;
            }
            if (preferredStart <= earliestDinnerStart) {
                return earliestDinnerStart;
            }
            if (preferredStart - rollingStart > maxPreferredWaitMinutes(normalizedSlot)) {
                return earliestDinnerStart;
            }
            return Math.min(preferredStart, DINNER_LATEST_START_MINUTES);
        }
        if (preferredStart <= rollingStart) {
            return rollingStart;
        }
        int maxExtraWait = maxPreferredWaitMinutes(normalizedSlot);
        if (preferredStart - rollingStart > maxExtraWait) {
            return rollingStart;
        }
        return preferredStart;
    }

    private int timeSensitiveEarliestStart(Place stop) {
        if (stop == null) {
            return 0;
        }
        if (isCulturalOpeningHoursConstrained(stop)) {
            return 10 * 60;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        if (name.contains("penguin")) {
            return 16 * 60 + 30;
        }
        return 0;
    }

    private int chooseLunchStart(int rollingStart, int preferredStart) {
        int earliestLunchStart = Math.max(rollingStart, LUNCH_EARLIEST_START_MINUTES);
        int preferredWindowStart = Math.max(earliestLunchStart, LUNCH_PREFERRED_EARLIEST_START_MINUTES);
        int cappedPreferred = Math.max(LUNCH_PREFERRED_EARLIEST_START_MINUTES, Math.min(preferredStart, LUNCH_LATEST_START_MINUTES));
        if (rollingStart <= LUNCH_LATEST_START_MINUTES) {
            if (cappedPreferred <= preferredWindowStart) {
                return preferredWindowStart;
            }
            int maxExtraWait = maxPreferredWaitMinutes("lunch");
            if (cappedPreferred - preferredWindowStart > maxExtraWait) {
                return preferredWindowStart;
            }
            return cappedPreferred;
        }
        return rollingStart;
    }

    private int maxPreferredWaitMinutes(String slot) {
        return switch (slot) {
            case "lunch" -> 45;
            case "afternoon" -> 30;
            case "sunset" -> 45;
            case "dinner", "evening" -> 60;
            default -> 20;
        };
    }

    private Place copyPlaceWithTimes(Place s, String start, String end, int stay) {
        return new Place(s.name(), s.addressLine(), s.suburb(), s.city(), s.state(), s.postcode(), s.country(), s.category(), stay, s.timeSlot(), start, end, s.mealType(), s.preferredArea(), s.cuisine(), s.vibe(), s.budgetLevel(), s.reason(), s.tip(), s.websiteUri(), s.googleMapsUri(), s.businessStatus(), s.url(), s.latitude(), s.longitude());
    }

    private Place copyPlaceWithLocation(Place stop, StopLocation location) {
        String addressLine = location.addressLine() == null || location.addressLine().isBlank()
                ? stop.addressLine()
                : location.addressLine();
        return new Place(
                stop.name(),
                addressLine,
                stop.suburb(),
                stop.city(),
                stop.state(),
                stop.postcode(),
                stop.country(),
                stop.category(),
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                stop.preferredArea(),
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                stop.reason(),
                stop.tip(),
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                location.lat(),
                location.lon()
        );
    }

    private boolean isRouteSuggestionOptional(Place stop) {
        if (stop == null) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        String category = normalizeSlot(stop.category());
        return ("attraction".equals(category) || "park".equals(category) || "nature".equals(category))
                && (name.contains("riverwalk")
                || name.contains("promenade")
                || name.contains("coastal walk")
                || name.contains("scenic walk")
                || name.contains("walking path"));
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        boolean exists = values.stream().anyMatch(existing -> existing.equalsIgnoreCase(normalized));
        if (!exists) {
            values.add(normalized);
        }
    }

    private void appendStageTiming(StringBuilder sb, String stage, long ms) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(stage).append("=").append(ms).append("ms");
    }

    private void logPlanStageCounts(StringBuilder sb, String att, String stg, PlanDraftResponse d) {
        String c = summarizeDraftCounts(d);
        if (sb.length() > 0) sb.append(" || ");
        sb.append(att).append("/").append(stg).append(": ").append(c);
    }

    private void logPlanStageSummary(StringBuilder sb) {
        String line = sb == null ? "" : sb.toString();
        log.info("Plan stage summary [{}]", line);
        System.out.println("PLAN_STAGE_SUMMARY " + line);
    }
    private void logPlanStageTimingSummary(StringBuilder sb) {
        String line = sb == null ? "" : sb.toString();
        log.info("Plan stage timings elapsedMs=[{}]", line);
        System.out.println("Plan stage timings elapsedMs=[" + line + "]");
        System.out.println("PLAN_STAGE_TIMINGS " + line);
    }
    private void logPlanQualityReport(PlanQualityReport report) {
        if (report == null) {
            return;
        }
        try {
            log.info("Plan quality report {}", objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            log.debug("Failed to serialize plan quality report", e);
        }
    }

    private PlanStageMetrics captureStageMetrics(
            String stageName,
            PlanDraftResponse draft,
            CreatePlanReq req,
            List<String> additionalIssues
    ) {
        List<String> combinedIssues = new ArrayList<>();
        if (additionalIssues != null) {
            combinedIssues.addAll(additionalIssues);
        }
        combinedIssues.addAll(validateDraft(draft, req));
        return planQualityMetricsService.evaluate(stageName, draft, req, combinedIssues);
    }

    private String summarizeDraftCounts(PlanDraftResponse d) {
        if (d == null || d.daysPlan() == null) return "null";
        String daySummary = d.daysPlan().stream()
                .map(day -> {
                    List<Place> stops = day.stops() == null ? List.of() : day.stops();
                    long meals = stops.stream().filter(this::isStrictMealStop).count();
                    long nonMeals = stops.stream().filter(this::isCountedNonMealStop).count();
                    String names = stops.stream()
                            .filter(this::isCountedNonMealStop)
                            .map(Place::name)
                            .filter(name -> name != null && !name.isBlank())
                            .map(this::shortLogName)
                            .collect(Collectors.joining(", "));
                    return "D" + day.dayIndex()
                            + " total=" + stops.size()
                            + " nonMeal=" + nonMeals
                            + " meals=" + meals
                            + " names=[" + names + "]";
                })
                .collect(Collectors.joining("; "));
        return "Days: " + d.daysPlan().size() + " [" + daySummary + "]";
    }

    private String shortLogName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= 36) {
            return cleaned;
        }
        return cleaned.substring(0, 33) + "...";
    }

    private record PaceNonMealRange(int min, int max) {}
    private record StopLocation(double lat, double lon, String addressLine) {}
    private record ParsedAddress(String addressLine, String suburb, String state, String postcode, String country) {}
    private record SeenPoiStop(int dayIndex, int stopIndex, String stopName) {}
    private record DaySkeletonContext(
            Map<Integer, Integer> effectiveMinByDay,
            String promptHints,
            Map<Integer, String> promptHintsByDay
    ) {
        private static DaySkeletonContext from(
                DaySkeletonService.DaySkeletonBatch batch,
                DaySkeletonService daySkeletonService
        ) {
            Map<Integer, Integer> effectiveMinByDay = daySkeletonService.effectiveMinByDay(batch, 0);
            String promptHints = daySkeletonService.toPromptHints(batch);
            Map<Integer, String> promptHintsByDay = batch == null || batch.skeletons() == null
                    ? Map.of()
                    : batch.skeletons().stream()
                    .filter(skeleton -> skeleton != null)
                    .collect(Collectors.toMap(
                            DaySkeletonService.DaySkeleton::dayIndex,
                            skeleton -> "day-" + skeleton.dayIndex()
                                    + "{primaryArea=" + (skeleton.primaryArea() == null || skeleton.primaryArea().isBlank() ? "n/a" : skeleton.primaryArea())
                                    + ",effectiveNonMeal=" + skeleton.effectiveMinNonMealStops() + "-" + skeleton.effectiveMaxNonMealStops()
                                    + (skeleton.capacityIssueCode().isBlank() ? "" : ",capacityIssue=" + skeleton.capacityIssueCode())
                                    + "}",
                            (left, right) -> left,
                            java.util.LinkedHashMap::new
                    ));
            return new DaySkeletonContext(effectiveMinByDay, promptHints, promptHintsByDay);
        }

        private Map<Integer, Integer> effectiveMinByDay(int fallbackMin) {
            if (effectiveMinByDay == null || effectiveMinByDay.isEmpty()) {
                return Map.of();
            }
            return effectiveMinByDay.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue() == null || entry.getValue() <= 0 ? fallbackMin : entry.getValue(),
                            (left, right) -> left,
                            java.util.LinkedHashMap::new
                    ));
        }

        private String promptHintsText() {
            return promptHints == null ? "" : promptHints;
        }

        private String promptHintForDay(int dayIndex) {
            if (promptHintsByDay == null || promptHintsByDay.isEmpty()) {
                return "";
            }
            return promptHintsByDay.getOrDefault(dayIndex, "");
        }
    }
}
