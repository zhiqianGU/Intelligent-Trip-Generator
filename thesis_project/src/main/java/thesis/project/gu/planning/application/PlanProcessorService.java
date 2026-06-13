package thesis.project.gu.planning.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.routing.domain.RouteRecommendationContext;
import thesis.project.gu.routing.domain.ModeSummary;
import thesis.project.gu.routing.domain.RouteChoice;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;
import thesis.project.gu.routing.application.MapService;
import thesis.project.gu.planning.ai.TripAiService;
import thesis.project.gu.infrastructure.cache.CacheSerive;
import thesis.project.gu.catalog.verification.HotelVerificationService;
import thesis.project.gu.catalog.verification.RestaurantVerificationService;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.planning.localfast.LocalPlanGeneratorService;
import thesis.project.gu.planning.metrics.PlanStageMetrics;
import thesis.project.gu.planning.quality.LocalPlanQualityDiagnosticService;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;
import thesis.project.gu.planning.quality.PlanQualityMetricsService;
import thesis.project.gu.planning.quality.PlanQualityReport;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.time.LocalTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PlanProcessorService {
    private static final Logger log = LoggerFactory.getLogger(PlanProcessorService.class);
    private static final String PHASED_PIPELINE_FLAG = "itrip.plan.phased.enabled";
    private static final String RELAXED_VALIDATION_BENCHMARK_FLAG = "itrip.plan.validation.relaxedForBenchmark";
    private static final int AUTO_PHASED_MIN_DAYS = 3;
    private static final int AUTO_PHASED_RETRY_MIN_DAYS = 3;
    private static final int MAX_PLAN_RETRY_ATTEMPTS = 1;
    private static final int MAX_INVALID_JSON_REPAIR_ATTEMPTS = 2;
    private static final int RETRY_ISSUE_SUMMARY_LIMIT = 12;
    private static final int RETRY_ISSUE_SUMMARY_MAX_CHARS = 900;
    private static final int RETRY_SKELETON_DAY_HINTS_LIMIT = 6;
    private static final int RETRY_SKELETON_HINTS_MAX_CHARS = 1200;
    private static final int RETRY_SCOPED_DAY_CONTEXT_MAX_CHARS = 2200;
    private static final int RETRY_STABLE_FIELDS_MAX_CHARS = 1200;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY_START_MINUTES = 9 * 60;
    private static final int DEFAULT_STAY_MINUTES = 60;
    private static final Pattern STOP_ISSUE_PATTERN = Pattern.compile("^day-(\\d+)-stop-(\\d+)-(.+)$");
    public static final int LUNCH_EARLIEST_START_MINUTES = 11 * 60 + 15;
    private static final int LUNCH_PREFERRED_EARLIEST_START_MINUTES = 11 * 60 + 30;
    public static final int LUNCH_LATEST_START_MINUTES = 13 * 60;
    private static final int THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES = 14 * 60 + 30;
    private static final int DINNER_PREFERRED_EARLIEST_START_MINUTES = 18 * 60;
    private static final int DINNER_PREFERRED_LATEST_START_MINUTES = 19 * 60 + 30;
    private static final int DINNER_LATEST_START_MINUTES = 20 * 60;
    public static final int THEME_PARK_DAY_DINNER_LATEST_START_MINUTES = 20 * 60 + 30;
    private static final double THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS = 150_000D;
    private static final int THEME_PARK_AFTERNOON_CONTINUATION_MINUTES = 60;
    private static final int THEME_PARK_CONTINUATION_MAX_EXTENSION_MINUTES = 150;
    private static final int THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES = 120;
    private static final int CULTURAL_POI_LATEST_END_MINUTES = 17 * 60;
    private static final int SHORT_WALK_ESTIMATE_MAX_DISTANCE_METERS = 650;
    private static final int SHORT_WALK_ESTIMATE_STRICT_AREA_DISTANCE_METERS = 450;
    private static final int SCHEDULING_SAME_AREA_WALK_ESTIMATE_MAX_DISTANCE_METERS = 1_200;
    private static final int SCHEDULING_SAME_SUBURB_WALK_ESTIMATE_MAX_DISTANCE_METERS = 900;
    private static final int SCHEDULING_CULTURAL_PRECINCT_WALK_ESTIMATE_MAX_DISTANCE_METERS = 1_400;
    private static final int SCHEDULING_DIRECT_WALK_ESTIMATE_MAX_DISTANCE_METERS = 750;
    private static final int SHORT_WALK_ESTIMATE_MIN_MINUTES = 8;
    private static final int SHORT_WALK_ESTIMATE_BUFFER_MINUTES = 4;
    private static final double SHORT_WALK_ESTIMATE_METERS_PER_MINUTE = 75.0;
    private static final int DETERMINISTIC_REPAIR_MAX_PASSES = 3;
    private static final String ROUTE_CHOICE_REDIS_PREFIX = "nav:route_choice_hybrid:";
    private static final Duration ROUTE_CHOICE_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration STOP_LOCATION_CACHE_TTL = Duration.ofMinutes(10);
    private static final double REDIS_TTL_JITTER_RATIO = 0.10D;
    private static final int COPY_POLISH_LONG_PLAN_DAY_THRESHOLD = 7;
    private static final long COPY_POLISH_LONG_PLAN_MAX_ELAPSED_MS = Duration.ofSeconds(120).toMillis();
    private static final int DETERMINISTIC_SMALL_GAP_OVERRUN_MAX_MINUTES = 30;
    private static final int GEOCODE_BULKHEAD_CONCURRENCY = 6;
    private static final int ROUTE_SUMMARY_BULKHEAD_CONCURRENCY = 8;
    private static final Cache<RouteChoiceCacheKey, RouteChoice> ROUTE_CHOICE_L1_CACHE = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(ROUTE_CHOICE_CACHE_TTL)
            .build();
    private static final Cache<String, StopLocation> STOP_LOCATION_L1_CACHE = Caffeine.newBuilder()
            .maximumSize(40_000)
            .expireAfterWrite(STOP_LOCATION_CACHE_TTL)
            .build();
    private static final Semaphore GEOCODE_BULKHEAD = new Semaphore(GEOCODE_BULKHEAD_CONCURRENCY, true);
    private static final Semaphore ROUTE_SUMMARY_BULKHEAD = new Semaphore(ROUTE_SUMMARY_BULKHEAD_CONCURRENCY, true);

    private final TripAiService aiService;
    private final CacheSerive cacheSerive;
    private final ObjectMapper objectMapper;
    private final HotelVerificationService hotelVerificationService;
    private final RestaurantVerificationService restaurantVerificationService;
    private final MapService mapService;
    private final GooglePlacesClient googlePlacesClient;
    private final PlanQualityMetricsService planQualityMetricsService;
    private final DaySkeletonService daySkeletonService;
    private final PlaceHeuristicService placeHeuristicService;
    private final StringRedisTemplate stringRedisTemplate;
    private final LocalPlanGeneratorService localPlanGeneratorService;
    private final LocalPlanQualityDiagnosticService localPlanQualityDiagnosticService;

    public PlanProcessorService(
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
            LocalPlanQualityDiagnosticService localPlanQualityDiagnosticService
    ) {
        this.aiService = aiService;
        this.cacheSerive = cacheSerive;
        this.objectMapper = objectMapper;
        this.hotelVerificationService = hotelVerificationService;
        this.restaurantVerificationService = restaurantVerificationService;
        this.mapService = mapService;
        this.googlePlacesClient = googlePlacesClient;
        this.planQualityMetricsService = planQualityMetricsService;
        this.daySkeletonService = daySkeletonService;
        this.placeHeuristicService = placeHeuristicService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.localPlanGeneratorService = localPlanGeneratorService;
        this.localPlanQualityDiagnosticService = localPlanQualityDiagnosticService;
    }

    PlanDraftResponse processExistingRawDraft(CreatePlanReq req, String raw, boolean redisHit, long aiGenerationMs) throws Exception {
        return processDraftSinglePass(req, raw, redisHit, aiGenerationMs, false);
    }

    public PlanDraftResponse generateDraft(CreatePlanReq req, boolean redisHit) throws Exception {
        return generateDraft(req, redisHit, false);
    }

    public PlanDraftResponse generateDraft(CreatePlanReq req, boolean redisHit, boolean deferCopyPolish) throws Exception {
        if (isLocalFastMode(req)) {
            long startedAt = System.currentTimeMillis();
            PlanDraftResponse draft = localPlanGeneratorService.generate(req);
            if (deferCopyPolish) {
                draft = withCopyPolishStatus(draft, "deferred");
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
                    req == null ? null : req.city(),
                    req == null ? null : req.days(),
                    draft == null || draft.daysPlan() == null ? null : draft.daysPlan().size(),
                    System.currentTimeMillis() - startedAt);
            return draft;
        }
        log.info("Plan generation start city={} requestedDays={} phasedGeneration={} redisHit={}",
                req == null ? null : req.city(),
                req == null ? null : req.days(),
                isPhasedGenerationEnabled(req),
                redisHit);
        long aiStartedAt = System.currentTimeMillis();
        String raw = isPhasedGenerationEnabled(req)
                ? aiService.generatePlanRawPhased(req)
                : aiService.generatePlanRaw(req);
        long aiGenerationMs = System.currentTimeMillis() - aiStartedAt;
        return processDraftSinglePass(req, raw, redisHit, aiGenerationMs, deferCopyPolish);
    }

    private boolean isLocalFastMode(CreatePlanReq req) {
        return req != null
                && req.mainModel() != null
                && "local-fast".equals(req.mainModel().trim().toLowerCase(Locale.ROOT));
    }

    private PlanDraftResponse processDraftSinglePass(
            CreatePlanReq req,
            String raw,
            boolean redisHit,
            long aiGenerationMs,
            boolean deferCopyPolish
    ) throws Exception {
        long totalStartedAt = System.currentTimeMillis() - Math.max(0, aiGenerationMs);
        StringBuilder stageSummary = new StringBuilder();
        StringBuilder timingSummary = new StringBuilder();
        List<PlanStageMetrics> qualityStages = new ArrayList<>();
        appendStageTiming(timingSummary, "initial/ai-generate", aiGenerationMs);

        try {
            ProcessAttemptResult initialAttempt = processAttemptWithJsonRecovery(
                    req,
                    raw,
                    "initial",
                    stageSummary,
                    timingSummary,
                    qualityStages,
                    null
            );
            return validateAndRetry(req, raw, redisHit, totalStartedAt, stageSummary, timingSummary, initialAttempt, qualityStages, deferCopyPolish);
        } catch (Exception e) {
            if (timingSummary.indexOf("total=") < 0) {
                appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
                logPlanStageSummary(stageSummary);
                logPlanStageTimingSummary(timingSummary);
            }
            throw e;
        }
    }

    private boolean isPhasedGenerationEnabled(CreatePlanReq req) {
        if (Boolean.parseBoolean(System.getProperty(PHASED_PIPELINE_FLAG, "false"))) {
            return true;
        }
        int days = req == null ? 0 : req.days();
        return days >= AUTO_PHASED_MIN_DAYS;
    }

    private PlanDraftResponse validateAndRetry(
            CreatePlanReq req,
            String raw,
            boolean redisHit,
            long totalStartedAt,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            ProcessAttemptResult initialAttempt,
            List<PlanStageMetrics> qualityStages,
            boolean deferCopyPolish
    ) throws Exception {
        PlanDraftResponse draft = initialAttempt.draft();
        List<String> validationIssues = initialAttempt.validationIssues();

        if (validationIssues.isEmpty()) {
            return finishSuccessfulAttempt(
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
                shortRawPreview(raw),
                raw == null ? 0 : raw.length());

        PlanDraftResponse localRescue = localRescueBeforeRetryIfValid(
                req,
                draft,
                validationIssues,
                stageSummary,
                timingSummary,
                qualityStages
        );
        if (localRescue != null) {
            log.warn("Initial itinerary accepted with local rescue before AI retry. req={}, originalIssues={}",
                    req,
                    validationIssues);
            return finishSuccessfulAttempt(
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
            DeterministicFallbackResult deterministicEarlyStop = deterministicRepairIfValid(
                    req,
                    draft,
                    validationIssues,
                    "initial",
                    stageSummary,
                    timingSummary,
                    qualityStages
            );
            if (deterministicEarlyStop != null && deterministicEarlyStop.accepted()) {
                log.warn("Initial itinerary accepted with deterministic duplicate-first fallback. req={}, originalIssues={}",
                        req,
                        validationIssues);
                return finishSuccessfulAttempt(
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
            cacheSerive.evictAiPlanRaw(req);
        }

        long stageStartedAt = System.currentTimeMillis();
        DaySkeletonContext retrySkeletonContext = skeletonContext(req, draft);
        String retryRaw = regenerateRetryAttempt(req, draft, validationIssues, retrySkeletonContext);
        appendStageTiming(timingSummary, "retry-1/ai-regenerate", System.currentTimeMillis() - stageStartedAt);

        ProcessAttemptResult retryAttempt = processAttemptWithJsonRecovery(
                req,
                retryRaw,
                "retry-1",
                stageSummary,
                timingSummary,
                qualityStages,
                retrySkeletonContext
        );
        PlanDraftResponse retried = retryAttempt.draft();
        List<String> retryValidationIssues = retryAttempt.validationIssues();

        if (retryValidationIssues.isEmpty()) {
            return finishSuccessfulAttempt(
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

        DeterministicFallbackResult deterministicFallback = deterministicRetryFallbackIfValid(
                req,
                retried,
                retryValidationIssues,
                stageSummary,
                timingSummary,
                qualityStages
        );
        if (deterministicFallback != null && deterministicFallback.accepted()) {
            log.warn("Retry itinerary accepted with deterministic fallback. req={}, originalIssues={}", req, retryValidationIssues);
            return finishSuccessfulAttempt(
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

        PlanDraftResponse relaxedFallback = relaxedPaceFallbackIfValid(retried, req, retryValidationIssues);
        if (relaxedFallback != null) {
            log.warn("Retry itinerary accepted with relaxed pace fallback. req={}, originalIssues={}", req, retryValidationIssues);
            return finishSuccessfulAttempt(
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

        logPlanStageSummary(stageSummary);
        appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
        logPlanStageTimingSummary(timingSummary);
        List<String> finalRetryIssues = deterministicFallback != null && deterministicFallback.validationIssues() != null
                && !deterministicFallback.validationIssues().isEmpty()
                ? deterministicFallback.validationIssues()
                : retryValidationIssues;
        log.error("Retried generated itinerary failed validation. issues={}, retryRawPreview={}, retryRawLength={}",
                finalRetryIssues,
                shortRawPreview(retryRaw),
                retryRaw == null ? 0 : retryRaw.length());
        if (isRelaxedValidationBenchmarkEnabled() && !hasRequestedDayCountIssue(finalRetryIssues)) {
            log.warn("Accepting retried itinerary despite validation issues because {}=true. req={}, issues={}",
                    RELAXED_VALIDATION_BENCHMARK_FLAG,
                    req,
                    finalRetryIssues);
            return finishSuccessfulAttempt(
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
        throw thesis.project.gu.exception.ErrorCode.INTERNAL_ERROR.ex(
                "Retried generated itinerary failed validation: " + finalRetryIssues
        );
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
                return objectMapper.writeValueAsString(phasedRetried);
            }
        }
        if (shouldUsePhasedWholePlanRetry(req, draft, validationIssues)) {
            PlanDraftResponse phasedRetried = retryWholePlanPhased(req, draft, validationIssues, retrySkeletonContext);
            if (phasedRetried != null) {
                return objectMapper.writeValueAsString(phasedRetried);
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
        String compactSkeletonHints = compactRetrySkeletonHints(retrySkeletonContext, validationIssues);
        return aiService.regeneratePlanRaw(
                req,
                retryInstruction(req, validationIssues, retrySkeletonContext, failedDraft),
                compactSkeletonHints
        );
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
        String nonDayIssueInstruction = buildWholePlanNonDayIssueInstruction(validationIssues);
        Map<Integer, String> retryInstructionsByDay = new java.util.LinkedHashMap<>();
        for (Integer dayIndex : targetDayIndexes) {
            List<String> dayIssues = issuesByDay.getOrDefault(dayIndex, List.of());
            retryInstructionsByDay.put(
                    dayIndex,
                    retryInstructionForWholePlanDay(dayIndex, dayIssues, failedDraft, skeletonContext, nonDayIssueInstruction)
            );
        }
        return aiService.regeneratePlanDaysPhased(req, failedDraft, targetDayIndexes, retryInstructionsByDay);
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

    private ProcessAttemptResult processAttempt(
            CreatePlanReq req,
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            List<PlanStageMetrics> qualityStages
    ) throws Exception {
        ParseNormalizeResult parsed = parseAndNormalize(raw, attemptLabel, stageSummary, timingSummary);
        qualityStages.add(captureStageMetrics(attemptLabel + "/raw_parsed", parsed.draft(), req, null));
        EntityVerificationResult verified = verifyAndRepairEntities(parsed.draft(), attemptLabel, stageSummary, timingSummary);
        PlanDraftResponse draft = verified.draft();
        List<String> validationIssues = verified.validationIssues();
        qualityStages.add(captureStageMetrics(attemptLabel + "/entity_verified", draft, req, validationIssues));

        draft = applySemanticPruning(draft, req, attemptLabel, stageSummary, timingSummary);

        long stageStartedAt = System.currentTimeMillis();
        draft = restaurantVerificationService.ensureRequiredMeals(draft);
        draft = restaurantVerificationService.verifyAndNormalize(draft).draft();
        appendStageTiming(timingSummary, attemptLabel + "/ensure-required-meals", System.currentTimeMillis() - stageStartedAt);

        draft = applyThemeParkGovernance(draft, req, attemptLabel, stageSummary, timingSummary);

        draft = applyRouteAwareScheduling(draft, attemptLabel, stageSummary, timingSummary);

        draft = applyPostRouteRepair(draft, req, attemptLabel, stageSummary, timingSummary);
        qualityStages.add(captureStageMetrics(attemptLabel + "/post_route_repaired", draft, req, validationIssues));

        stageStartedAt = System.currentTimeMillis();
        DaySkeletonContext skeletonContext = skeletonContext(req, draft);
        validationIssues.addAll(validateDraft(draft, req, skeletonContext));
        appendStageTiming(timingSummary, attemptLabel + "/validate", System.currentTimeMillis() - stageStartedAt);

        return new ProcessAttemptResult(draft, validationIssues);
    }

    private ProcessAttemptResult processAttemptWithJsonRecovery(
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
                        shortRawPreview(currentRaw));
                long stageStartedAt = System.currentTimeMillis();
                if (isPhasedGenerationEnabled(req)) {
                    currentRaw = aiService.generatePlanRawPhased(req);
                } else {
                    currentRaw = aiService.regeneratePlanRaw(
                            req,
                            invalidJsonRetryInstruction(parseFailure),
                            repairSkeletonContext.promptHintsText()
                    );
                }
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
        draft = pruneFlexibleFoodStops(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-flexible-prune", draft);
        draft = pruneUnselectedShoppingStops(draft, req);
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
        draft = pruneAreaInconsistentFlexibleStops(draft, req);
        appendStageTiming(timingSummary, attemptLabel + "/theme-park-area-prune", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "pre-route-prune", draft);
        return draft;
    }

    private PlanDraftResponse applyRouteAwareScheduling(
            PlanDraftResponse draft,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        long stageStartedAt = System.currentTimeMillis();
        draft = normalizeDraftScheduleWithRouteDurations(draft);
        draft = pruneOutOfRangeThemeParkStops(draft);
        draft = pruneThemeParkDayTrips(draft);
        appendStageTiming(timingSummary, attemptLabel + "/route-aware-schedule", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "route-aware-schedule", draft);
        return draft;
    }

    private PlanDraftResponse applyPostRouteRepair(
            PlanDraftResponse draft,
            CreatePlanReq req,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) {
        long stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse beforePostRouteRepair = draft;
        draft = pruneFlexibleFoodStops(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-flexible-prune", draft);
        draft = pruneUnselectedShoppingStops(draft, req);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-shopping-prune", draft);
        draft = pruneAreaInconsistentFlexibleStops(draft, req);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-area-prune", draft);
        draft = pruneExcessNonMealStops(draft, req);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-excess-prune", draft);
        java.util.Set<Integer> postRouteChangedDays = detectChangedDayIndexes(beforePostRouteRepair, draft);
        if (!postRouteChangedDays.isEmpty()) {
            draft = normalizeDraftScheduleWithRouteDurations(draft, postRouteChangedDays);
        }
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-reschedule", draft);
        PlanDraftResponse beforeDuplicateRepair = draft;
        draft = repairCrossDayDuplicatePois(draft);
        java.util.Set<Integer> duplicateRepairChangedDays = detectChangedDayIndexes(beforeDuplicateRepair, draft);
        if (!duplicateRepairChangedDays.isEmpty()) {
            draft = normalizeDraftScheduleWithRouteDurations(draft, duplicateRepairChangedDays);
        }
        java.util.Set<Integer> changedNarrativeDays = new java.util.LinkedHashSet<>(postRouteChangedDays);
        changedNarrativeDays.addAll(duplicateRepairChangedDays);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-duplicate-repair", draft);
        PlanDraftResponse beforeTimeSensitiveRepair = draft;
        draft = repairTimeSensitiveLateStops(draft);
        changedNarrativeDays.addAll(detectChangedDayIndexes(beforeTimeSensitiveRepair, draft));
        logPlanStageCounts(stageSummary, attemptLabel, "post-time-sensitive-repair", draft);
        PlanDraftResponse beforeGapClamp = draft;
        draft = clampOversizedGaps(draft);
        java.util.Set<Integer> gapClampChangedDays = detectChangedDayIndexes(beforeGapClamp, draft);
        changedNarrativeDays.addAll(gapClampChangedDays);
        logPlanStageCounts(stageSummary, attemptLabel, "post-gap-clamp", draft);
        draft = rewriteDayNarratives(draft, changedNarrativeDays);
        appendStageTiming(timingSummary, attemptLabel + "/post-route-prune-narrative", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "final", draft);
        return draft;
    }

    private ParseNormalizeResult parseAndNormalize(
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) throws Exception {
        if (raw == null) {
            throw new IllegalArgumentException("AI raw response is null");
        }
        long stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse draft = objectMapper.readValue(
                raw.strip().replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", ""),
                PlanDraftResponse.class
        );
        appendStageTiming(timingSummary, attemptLabel + "/parse-json", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "raw", draft);

        stageStartedAt = System.currentTimeMillis();
        draft = normalizeDraftSchedule(draft);
        appendStageTiming(timingSummary, attemptLabel + "/normalize-schedule", System.currentTimeMillis() - stageStartedAt);

        return new ParseNormalizeResult(draft);
    }

    private EntityVerificationResult verifyAndRepairEntities(
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

        stageStartedAt = System.currentTimeMillis();
        var initialVerification = restaurantVerificationService.verifyAndNormalize(draft);
        draft = initialVerification.draft();
        appendStageTiming(timingSummary, attemptLabel + "/meal-verify-1", System.currentTimeMillis() - stageStartedAt);
        if (initialVerification.issues().isEmpty()) {
            logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            return new EntityVerificationResult(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse repairedAfterInitialVerification = repairMealStops(draft, initialVerification.issues());
        Map<Integer, java.util.Set<Integer>> initialChangedMealTargets = detectChangedFoodStopIndexes(draft, repairedAfterInitialVerification);
        draft = repairedAfterInitialVerification;
        appendStageTiming(timingSummary, attemptLabel + "/meal-repair-1", System.currentTimeMillis() - stageStartedAt);
        if (!hasChangedMealTargets(initialChangedMealTargets)) {
            logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            validationIssues.addAll(initialVerification.issues());
            return new EntityVerificationResult(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        var finalVerification = restaurantVerificationService.verifyAndNormalizeSelective(draft, initialChangedMealTargets);
        draft = finalVerification.draft();
        appendStageTiming(timingSummary, attemptLabel + "/meal-verify-2", System.currentTimeMillis() - stageStartedAt);
        if (finalVerification.issues().isEmpty()) {
            logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            return new EntityVerificationResult(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse repairedAfterFinalVerification = repairMealStops(draft, finalVerification.issues());
        Map<Integer, java.util.Set<Integer>> finalChangedMealTargets = detectChangedFoodStopIndexes(draft, repairedAfterFinalVerification);
        draft = repairedAfterFinalVerification;
        appendStageTiming(timingSummary, attemptLabel + "/meal-repair-2", System.currentTimeMillis() - stageStartedAt);
        if (!hasChangedMealTargets(finalChangedMealTargets)) {
            logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);
            validationIssues.addAll(finalVerification.issues());
            return new EntityVerificationResult(draft, validationIssues);
        }

        stageStartedAt = System.currentTimeMillis();
        var settledVerification = restaurantVerificationService.verifyAndNormalizeSelective(draft, finalChangedMealTargets);
        draft = settledVerification.draft();
        appendStageTiming(timingSummary, attemptLabel + "/meal-verify-3", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);

        validationIssues.addAll(settledVerification.issues());
        return new EntityVerificationResult(draft, validationIssues);
    }

    private boolean hasChangedMealTargets(Map<Integer, java.util.Set<Integer>> changedTargets) {
        if (changedTargets == null || changedTargets.isEmpty()) {
            return false;
        }
        return changedTargets.values().stream().anyMatch(indexes -> indexes != null && !indexes.isEmpty());
    }

    private record ProcessAttemptResult(PlanDraftResponse draft, List<String> validationIssues) {
    }

    private record ParseNormalizeResult(PlanDraftResponse draft) {
    }

    private record EntityVerificationResult(PlanDraftResponse draft, List<String> validationIssues) {
    }

    private record DeterministicFallbackResult(
            PlanDraftResponse draft,
            List<String> validationIssues,
            boolean accepted
    ) {
    }

    private PlanDraftResponse normalizeDraftSchedule(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            days.add(normalizeDaySchedule(day));
        }
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
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
        if (draft == null || draft.daysPlan() == null) return draft;
        RouteRecommendationContext ctx = routeSchedulingContext(draft);
        List<DayPlan> days = normalizeDaysScheduleWithRouteDurations(draft.daysPlan(), ctx, null);
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
    }

    public PlanDraftResponse normalizeDraftScheduleWithRouteDurations(PlanDraftResponse draft, java.util.Set<Integer> targetDayIndexes) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || targetDayIndexes == null || targetDayIndexes.isEmpty()) {
            return draft;
        }
        RouteRecommendationContext ctx = routeSchedulingContext(draft);
        List<DayPlan> days = normalizeDaysScheduleWithRouteDurations(draft.daysPlan(), ctx, targetDayIndexes);
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
    }

    private RouteRecommendationContext routeSchedulingContext(PlanDraftResponse draft) {
        int kids = draft == null || draft.party() == null || draft.party().kids() == null ? 0 : draft.party().kids();
        return new RouteRecommendationContext(kids > 0, null, null);
    }

    private List<DayPlan> normalizeDaysScheduleWithRouteDurations(
            List<DayPlan> sourceDays,
            RouteRecommendationContext ctx,
            java.util.Set<Integer> targetDayIndexes
    ) {
        if (sourceDays == null || sourceDays.isEmpty()) {
            return List.of();
        }
        if (sourceDays.size() == 1) {
            DayPlan day = sourceDays.getFirst();
            if (shouldNormalizeRouteAwareDay(day, targetDayIndexes)) {
                return List.of(normalizeDayScheduleWithRouteDurations(day, ctx));
            }
            return sourceDays;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, sourceDays.size()));
        try {
            List<CompletableFuture<DayPlan>> futures = sourceDays.stream()
                    .map(day -> shouldNormalizeRouteAwareDay(day, targetDayIndexes)
                            ? CompletableFuture.supplyAsync(() -> normalizeDayScheduleWithRouteDurations(day, ctx), executor)
                            : CompletableFuture.completedFuture(day))
                    .toList();
            return futures.stream().map(this::joinDayPlan).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean shouldNormalizeRouteAwareDay(DayPlan day, java.util.Set<Integer> targetDayIndexes) {
        return day != null && (targetDayIndexes == null || targetDayIndexes.isEmpty() || targetDayIndexes.contains(day.dayIndex()));
    }

    private DayPlan joinDayPlan(CompletableFuture<DayPlan> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            return null;
        }
    }

    private DayPlan normalizeDayScheduleWithRouteDurations(DayPlan day, RouteRecommendationContext ctx) {
        List<Place> stops = day.stops() == null ? List.of() : new ArrayList<>(day.stops());
        if (stops.isEmpty()) return day;
        stops = reorderStopsByTimeSlotIfMealOrderInvalid(stops);
        List<StopCoordinate> coordinates = resolveStopCoordinatesInParallel(stops);
        List<Integer> transferMinutes = resolveTransferMinutesInParallel(stops, coordinates, ctx);
        List<Place> normalized = new ArrayList<>();
        int previousEnd = DAY_START_MINUTES;
        for (int i = 0; i < stops.size(); i++) {
            Place stop = stops.get(i);
            int stay = resolveStayMinutes(stop);
            int transfer = i < transferMinutes.size() ? transferMinutes.get(i) : transitionMinutes(i == 0);
            int rollingStart = previousEnd + transfer;
            int preferredStart = preferredStartMinutes(stop.timeSlot(), i == 0);
            int start = chooseScheduledStart(rollingStart, preferredStart, stop);
            int end = start + stay;
            normalized.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
            previousEnd = end;
        }
        return new DayPlan(day.dayIndex(), day.hotel(), normalized, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
    }

    public PlanDraftResponse repairMealStops(PlanDraftResponse draft, List<String> issues) {
        PlanDraftResponse updated = dropInvalidStrictMealStops(draft, issues);
        updated = restaurantVerificationService.ensureRequiredMeals(updated);
        return normalizeDraftSchedule(updated);
    }

    private PlanDraftResponse dropInvalidStrictMealStops(PlanDraftResponse draft, List<String> issues) {
        if (draft == null || draft.daysPlan() == null || issues == null || issues.isEmpty()) return draft;
        List<DayPlan> updatedDays = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            List<Integer> dropIdx = collectStrictMealIndexesToDrop(day.dayIndex(), issues);
            if (dropIdx.isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> updatedStops = new ArrayList<>();
            for (int i = 0; i < stops.size(); i++) {
                Place s = stops.get(i);
                if (dropIdx.contains(i) && isStrictMealStop(s)) continue;
                updatedStops.add(s);
            }
            updatedDays.add(new DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), updatedDays, draft.copyPolishStatus());
    }

    private Map<Integer, java.util.Set<Integer>> detectChangedFoodStopIndexes(PlanDraftResponse before, PlanDraftResponse after) {
        Map<Integer, java.util.Set<Integer>> changed = new java.util.LinkedHashMap<>();
        if (after == null || after.daysPlan() == null || after.daysPlan().isEmpty()) {
            return changed;
        }
        Map<Integer, DayPlan> beforeByDay = before == null || before.daysPlan() == null
                ? Map.of()
                : before.daysPlan().stream()
                .filter(day -> day != null)
                .collect(Collectors.toMap(
                        DayPlan::dayIndex,
                        day -> day,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        for (DayPlan afterDay : after.daysPlan()) {
            if (afterDay == null) {
                continue;
            }
            DayPlan beforeDay = beforeByDay.get(afterDay.dayIndex());
            List<Place> beforeStops = beforeDay == null || beforeDay.stops() == null ? List.of() : beforeDay.stops();
            List<Place> afterStops = afterDay.stops() == null ? List.of() : afterDay.stops();
            int max = Math.max(beforeStops.size(), afterStops.size());
            for (int i = 0; i < max; i++) {
                Place beforeStop = i < beforeStops.size() ? beforeStops.get(i) : null;
                Place afterStop = i < afterStops.size() ? afterStops.get(i) : null;
                if (!isFoodStop(beforeStop) && !isFoodStop(afterStop)) {
                    continue;
                }
                if (foodStopEquivalent(beforeStop, afterStop)) {
                    continue;
                }
                if (afterStop != null && isFoodStop(afterStop)) {
                    changed.computeIfAbsent(afterDay.dayIndex(), ignored -> new java.util.LinkedHashSet<>()).add(i);
                }
                if (beforeStop != null && isFoodStop(beforeStop)) {
                    int relocatedIndex = findMatchingFoodStopIndex(afterStops, beforeStop);
                    if (relocatedIndex >= 0) {
                        changed.computeIfAbsent(afterDay.dayIndex(), ignored -> new java.util.LinkedHashSet<>()).add(relocatedIndex);
                    }
                }
            }
        }
        return changed;
    }

    private int findMatchingFoodStopIndex(List<Place> stops, Place target) {
        if (stops == null || stops.isEmpty() || target == null || !isFoodStop(target)) {
            return -1;
        }
        for (int i = 0; i < stops.size(); i++) {
            Place candidate = stops.get(i);
            if (!isFoodStop(candidate)) {
                continue;
            }
            if (foodStopEquivalent(target, candidate)) {
                return i;
            }
        }
        return -1;
    }

    private java.util.Set<Integer> detectChangedDayIndexes(PlanDraftResponse before, PlanDraftResponse after) {
        java.util.Set<Integer> changed = new java.util.LinkedHashSet<>();
        Map<Integer, DayPlan> beforeByDay = before == null || before.daysPlan() == null
                ? Map.of()
                : before.daysPlan().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        DayPlan::dayIndex,
                        day -> day,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        Map<Integer, DayPlan> afterByDay = after == null || after.daysPlan() == null
                ? Map.of()
                : after.daysPlan().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        DayPlan::dayIndex,
                        day -> day,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        java.util.Set<Integer> dayIndexes = new java.util.LinkedHashSet<>();
        dayIndexes.addAll(beforeByDay.keySet());
        dayIndexes.addAll(afterByDay.keySet());
        for (Integer dayIndex : dayIndexes) {
            if (!Objects.equals(daySnapshot(beforeByDay.get(dayIndex)), daySnapshot(afterByDay.get(dayIndex)))) {
                changed.add(dayIndex);
            }
        }
        return changed;
    }

    private String daySnapshot(DayPlan day) {
        if (day == null) {
            return "";
        }
        return day.dayIndex() + ":"
                + (day.stops() == null ? "" : day.stops().stream()
                .map(stop -> safeStopName(stop)
                        + "@"
                        + nullToEmpty(stop.startTime())
                        + "-"
                        + nullToEmpty(stop.endTime())
                        + "#"
                        + nullToEmpty(stop.category())
                        + "#"
                        + nullToEmpty(stop.timeSlot()))
                .collect(Collectors.joining("|")));
    }

    private boolean foodStopEquivalent(Place left, Place right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return normalizeSlot(left.name()).equals(normalizeSlot(right.name()))
                && normalizeSlot(left.addressLine()).equals(normalizeSlot(right.addressLine()))
                && normalizeSlot(left.category()).equals(normalizeSlot(right.category()))
                && normalizeSlot(left.timeSlot()).equals(normalizeSlot(right.timeSlot()))
                && normalizeSlot(left.mealType()).equals(normalizeSlot(right.mealType()))
                && normalizeSlot(left.preferredArea()).equals(normalizeSlot(right.preferredArea()))
                && normalizeSlot(left.city()).equals(normalizeSlot(right.city()))
                && normalizeSlot(left.suburb()).equals(normalizeSlot(right.suburb()))
                && normalizeSlot(left.businessStatus()).equals(normalizeSlot(right.businessStatus()));
    }

    private List<Integer> collectStrictMealIndexesToDrop(int dayIdx, List<String> issues) {
        List<Integer> idxs = new ArrayList<>();
        for (String iss : issues) {
            var m = STOP_ISSUE_PATTERN.matcher(iss);
            if (m.matches() && Integer.parseInt(m.group(1)) == dayIdx && isReplaceableStrictMealIssue(m.group(3))) {
                int zIdx = Integer.parseInt(m.group(2)) - 1;
                if (!idxs.contains(zIdx)) idxs.add(zIdx);
            }
        }
        return idxs;
    }

    private boolean isReplaceableStrictMealIssue(String issue) {
        return "google-places-low-confidence".equals(issue)
                || "google-places-no-match".equals(issue)
                || "google-places-area-not-venue".equals(issue)
                || "google-places-meal-not-suitable".equals(issue)
                || "google-places-not-food".equals(issue)
                || "google-places-closed".equals(issue);
    }

    private int minNonMealStopsPerDay(String pace) {
        return paceNonMealRange(pace).min();
    }

    private boolean hasThemeParkBeforeIndex(List<Place> stops, int index) {
        for (int i = 0; i < index; i++) {
            if (isThemeParkLikeStop(stops.get(i))) return true;
        }
        return false;
    }

    public static final int DINNER_EARLIEST_START_MINUTES = 17 * 60 + 30;

    public List<String> validateDraft(PlanDraftResponse draft) {
        return validateDraft(draft, null);
    }

    public List<String> validateDraft(PlanDraftResponse draft, CreatePlanReq req) {
        return validateDraft(draft, req, skeletonContext(req, draft));
    }

    private List<String> validateDraft(
            PlanDraftResponse draft,
            CreatePlanReq req,
            DaySkeletonContext skeletonContext
    ) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return List.of("missing-days");
        }

        List<String> issues = new ArrayList<>();
        issues.addAll(validateRequestedDayCount(draft, req));
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        Map<Integer, Integer> effectiveMinNonMealStopsByDay = effectiveMinNonMealStopsByDay(skeletonContext, minNonMealStops);
        boolean hasThemeParkDay = draft.daysPlan().stream()
                .anyMatch(day -> day.stops() != null && day.stops().stream().anyMatch(this::isThemeParkLikeStop));
        
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            boolean themeParkDay = stops.stream().anyMatch(this::isThemeParkLikeStop);
            int skeletonEffectiveMin = effectiveMinNonMealStopsByDay.getOrDefault(day.dayIndex(), minNonMealStops);
            int dayMinNonMealStops = hasThemeParkDay ? Math.min(skeletonEffectiveMin, 2) : skeletonEffectiveMin;
            if (countNonMealStops(stops) < dayMinNonMealStops && stops.stream().noneMatch(this::isThemeParkLikeStop)) {
                addValidationIssue(issues, "day-" + day.dayIndex() + "-too-few-non-meal-stops", day.dayIndex(), -1, null, null,
                        "nonMeal=" + countNonMealStops(stops) + " min=" + dayMinNonMealStops + " defaultMin=" + minNonMealStops);
            }
            boolean hasLunch = stops.stream().anyMatch(stop -> hasMealSlot(stop, "lunch"));
            boolean hasDinner = stops.stream().anyMatch(stop -> hasMealSlot(stop, "dinner"));
            if (!hasLunch) addValidationIssue(issues, "day-" + day.dayIndex() + "-missing-lunch", day.dayIndex(), -1, null, null, "strict lunch slot missing");
            if (!hasDinner) addValidationIssue(issues, "day-" + day.dayIndex() + "-missing-dinner", day.dayIndex(), -1, null, null, "strict dinner slot missing");
            if (hasLunch && stops.stream().noneMatch(stop -> hasVerifiedMealStop(stop, "lunch"))) {
                addValidationIssue(issues, "day-" + day.dayIndex() + "-missing-real-lunch", day.dayIndex(), -1, null, null, "lunch exists but no verified food venue");
            }
            if (hasDinner && stops.stream().noneMatch(stop -> hasVerifiedMealStop(stop, "dinner"))) {
                addValidationIssue(issues, "day-" + day.dayIndex() + "-missing-real-dinner", day.dayIndex(), -1, null, null, "dinner exists but no verified food venue");
            }

            int previousEnd = -1;
            for (int i = 0; i < stops.size(); i++) {
                Place stop = stops.get(i);
                int start = parseTimeMinutes(stop.startTime());
                int end = parseTimeMinutes(stop.endTime());
                if (start < 0 || end < 0) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-invalid-time", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " end=" + stop.endTime());
                    continue;
                }
                if (end <= start) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-reversed-time", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " end=" + stop.endTime());
                }
                Integer stayMinutes = stop.stayMinutes();
                if (stayMinutes != null && stayMinutes > 0) {
                    int delta = Math.abs((end - start) - stayMinutes);
                    if (delta > 20) {
                        addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-duration-mismatch", day.dayIndex(), i + 1, stop, null,
                                "stayMinutes=" + stayMinutes + " actual=" + (end - start) + " delta=" + delta);
                    }
                }
                if (previousEnd >= 0 && start < previousEnd) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-chronology-error", day.dayIndex(), i + 1, stop, stops.get(i - 1),
                            "previousEnd=" + formatMinutes(previousEnd) + " currentStart=" + stop.startTime());
                }
                if (previousEnd >= 0) {
                    int actualGap = start - previousEnd;
                    int allowedGap = maxAllowedGapMinutes(stops.get(i - 1), stop, i == stops.size() - 1, previousEnd);
                    if (actualGap > allowedGap) {
                        addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-gap-too-large", day.dayIndex(), i + 1, stop, stops.get(i - 1),
                                "actualGap=" + actualGap + " allowedGap=" + allowedGap);
                    }
                }
                int latestLunchStart = themeParkDay
                        ? THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES
                        : LUNCH_LATEST_START_MINUTES;
                if (hasMealSlot(stop, "lunch") && (start < LUNCH_EARLIEST_START_MINUTES || start > latestLunchStart)) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-lunch-time-invalid", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " allowed=" + formatMinutes(LUNCH_EARLIEST_START_MINUTES) + "-" + formatMinutes(latestLunchStart));
                }
                int latestDinnerStart = hasThemeParkBeforeIndex(stops, i)
                        ? THEME_PARK_DAY_DINNER_LATEST_START_MINUTES
                        : DINNER_LATEST_START_MINUTES;
                if (hasMealSlot(stop, "dinner") && (start < DINNER_EARLIEST_START_MINUTES || start > latestDinnerStart)) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-dinner-time-invalid", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " allowed=" + formatMinutes(DINNER_EARLIEST_START_MINUTES) + "-" + formatMinutes(latestDinnerStart));
                }
                if (hasMealSlot(stop, "dinner")
                        && hasThemeParkBeforeIndex(stops, i)
                        && start > THEME_PARK_DAY_DINNER_LATEST_START_MINUTES) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-theme-park-dinner-too-late", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " latest=" + formatMinutes(THEME_PARK_DAY_DINNER_LATEST_START_MINUTES));
                }
                issues.addAll(validateTimeSensitiveStop(day.dayIndex(), i + 1, stop, start, end));
                issues.addAll(validateThemeParkLocation(draft.city(), day.dayIndex(), i + 1, stop));
                previousEnd = Math.max(previousEnd, end);
            }
        }
        issues.addAll(validateSameDayDuplicatePois(draft));
        issues.addAll(validateCrossDayDuplicatePois(draft));
        issues.addAll(validateSelectedStyleCoverage(draft, req));
        return issues;
    }

    private List<String> validateSameDayDuplicatePois(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                continue;
            }
            Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
            for (int i = 0; i < day.stops().size(); i++) {
                Place stop = day.stops().get(i);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    continue;
                }
                SeenPoiStop firstSeen = findSeenPoi(duplicateKeys, seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), i + 1, stop.name()), seenStops);
                    continue;
                }
                addValidationIssue(
                        issues,
                        "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-duplicate-poi-same-day",
                        day.dayIndex(),
                        i + 1,
                        stop,
                        null,
                        "duplicateWith=day-" + firstSeen.dayIndex() + "-stop-" + firstSeen.stopIndex() + " name=" + firstSeen.stopName()
                );
            }
        }
        return issues;
    }

    private List<String> validateCrossDayDuplicatePois(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
        for (DayPlan day : draft.daysPlan()) {
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
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), i + 1, stop.name()), seenStops);
                    continue;
                }
                addValidationIssue(
                        issues,
                        "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-duplicate-poi-across-days",
                        day.dayIndex(),
                        i + 1,
                        stop,
                        null,
                        "duplicateWith=day-" + firstSeen.dayIndex() + "-stop-" + firstSeen.stopIndex() + " name=" + firstSeen.stopName()
                );
            }
        }
        return issues;
    }

    private String crossDayDuplicatePoiKey(Place stop) {
        List<String> keys = crossDayDuplicatePoiKeys(stop);
        return keys.isEmpty() ? "" : keys.get(0);
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

    private SeenPoiStop findSeenPoi(List<String> duplicateKeys, Map<String, SeenPoiStop> seenStops) {
        if (duplicateKeys == null || duplicateKeys.isEmpty() || seenStops == null || seenStops.isEmpty()) {
            return null;
        }
        for (String key : duplicateKeys) {
            SeenPoiStop seen = seenStops.get(key);
            if (seen != null) {
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

    private void addValidationIssue(List<String> issues, String issue, int dayIndex, int stopIndex, Place stop, Place previousStop, String detail) {
        issues.add(issue);
        if (log.isDebugEnabled()) {
            log.debug("Validation issue code={} day={} stopIndex={} stop={} previous={} detail={}",
                    issue,
                    dayIndex,
                    stopIndex,
                    safeStopName(stop),
                    safeStopName(previousStop),
                    detail);
        }
    }

    private String safeStopName(Place stop) {
        return stop == null || stop.name() == null ? "" : stop.name();
    }

    private List<String> validateSelectedStyleCoverage(PlanDraftResponse draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || req == null || req.style() == null || req.style().isEmpty()) {
            return List.of();
        }
        List<Place> stops = draft.daysPlan().stream()
                .filter(day -> day != null && day.stops() != null)
                .flatMap(day -> day.stops().stream())
                .toList();
        List<String> issues = new ArrayList<>();
        for (String style : req.style()) {
            String normalized = normalizeSlot(style);
            if (normalized.isBlank()) {
                continue;
            }
            boolean covered = switch (normalized) {
                case "market_shopping" -> stops.stream().anyMatch(this::isMarketShoppingLikeStop);
                case "theme_park" -> stops.stream().anyMatch(this::isThemeParkLikeStop);
                case "nature" -> stops.stream().anyMatch(this::isNatureCoverageStop);
                case "culture" -> stops.stream().anyMatch(this::isCultureCoverageStop);
                default -> true;
            };
            if (!covered) {
                String issue = "style-missing-" + normalized.replace('_', '-');
                if (isSoftMissingMarketShoppingUnderThemePark(normalized, stops, req)) {
                    logSoftValidationDiagnostic("style-soft-missing-market-shopping-after-theme-prune",
                            "requestedStyle=" + normalized + " reason=theme_park_route_cluster_priority");
                    continue;
                }
                addValidationIssue(issues, issue, -1, -1, null, null, "requestedStyle=" + normalized);
            }
        }
        return issues;
    }

    private boolean isSoftMissingMarketShoppingUnderThemePark(String normalizedStyle, List<Place> stops, CreatePlanReq req) {
        if (!"market_shopping".equals(normalizedStyle) || stops == null || stops.stream().noneMatch(this::isThemeParkLikeStop)) {
            return false;
        }
        return req != null && req.style() != null && req.style().stream()
                .map(this::normalizeSlot)
                .anyMatch("theme_park"::equals);
    }

    private void logSoftValidationDiagnostic(String code, String detail) {
        if (log.isDebugEnabled()) {
            log.debug("Validation diagnostic code={} detail={}", code, detail);
        }
    }

    private boolean isNatureCoverageStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = normalizeSlot(joinText(stop.name(), stop.category(), stop.suburb(), stop.preferredArea()));
        return "park".equals(category)
                || "nature".equals(category)
                || text.contains("garden")
                || text.contains("botanic")
                || text.contains("beach")
                || text.contains("lookout")
                || text.contains("reserve")
                || text.contains("trail")
                || text.contains("river")
                || text.contains("coastal")
                || text.contains("foreshore");
    }

    private boolean isCultureCoverageStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = normalizeSlot(joinText(stop.name(), stop.category(), stop.suburb(), stop.preferredArea()));
        return "museum".equals(category)
                || "gallery".equals(category)
                || "heritage".equals(category)
                || text.contains("museum")
                || text.contains("gallery")
                || text.contains("heritage")
                || text.contains("library")
                || text.contains("memorial")
                || text.contains("shrine")
                || text.contains("cultural")
                || text.contains("historic");
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

    private List<String> validateRequestedDayCount(PlanDraftResponse draft, CreatePlanReq req) {
        if (draft == null || req == null || req.days() < 1 || draft.daysPlan() == null) {
            return List.of();
        }
        int expectedDays = req.days();
        int actualDays = draft.daysPlan().size();
        Integer declaredDays = draft.days();
        List<String> issues = new ArrayList<>();
        if (actualDays != expectedDays) {
            issues.add("expected-" + expectedDays + "-days-but-got-" + actualDays);
        }
        if (declaredDays != null && declaredDays > 0 && declaredDays != expectedDays) {
            issues.add("declared-days-" + declaredDays + "-does-not-match-request-" + expectedDays);
        }
        return issues;
    }

    private String retryInstruction(
            CreatePlanReq req,
            List<String> validationIssues,
            DaySkeletonContext skeletonContext
    ) {
        return retryInstruction(req, validationIssues, skeletonContext, null);
    }

    private String retryInstruction(
            CreatePlanReq req,
            List<String> validationIssues,
            DaySkeletonContext skeletonContext,
            PlanDraftResponse failedDraft
    ) {
        int requestedDays = req == null ? 0 : req.days();
        String dayInstruction = requestedDays > 0
                ? " Please return exactly " + requestedDays + " days: days=" + requestedDays + " and daysPlan length=" + requestedDays + "."
                : "";
        String issueInstruction = buildWholePlanIssueRetryInstruction(validationIssues);
        String duplicateInstruction = buildWholePlanDuplicateRetryInstruction(validationIssues);
        String skeletonInstruction = compactRetrySkeletonHints(skeletonContext, validationIssues);
        String skeletonClause = skeletonInstruction.isBlank()
                ? ""
                : " Follow these day-level skeleton constraints strictly: " + skeletonInstruction + ".";
        String scopedRepairClause = buildWholePlanScopedRetryInstruction(failedDraft, validationIssues, skeletonContext);
        String stableFieldClause = buildWholePlanStableFieldInstruction(failedDraft, validationIssues);
        String hardConstraintClause = " Hard constraints: include exactly one real lunch and one real dinner per day, keep adjacent stop gaps tight, keep non-meal sightseeing POIs unique across days, and avoid remote district out-and-back routing on the same day.";
        String compactOutputClause = " Keep all strings minimal. Use the shortest valid title, overview, theme, note, reason, and tip text that still satisfies the schema. Do not expand unchanged content.";
        return "Please generate a valid itinerary."
                + hardConstraintClause
                + compactOutputClause
                + dayInstruction
                + issueInstruction
                + duplicateInstruction
                + scopedRepairClause
                + stableFieldClause
                + skeletonClause;
    }

    private String buildWholePlanIssueRetryInstruction(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return "";
        }
        List<String> normalizedIssues = validationIssues.stream()
                .filter(issue -> issue != null && !issue.isBlank())
                .distinct()
                .toList();
        if (normalizedIssues.isEmpty()) {
            return "";
        }
        int cappedSize = Math.min(RETRY_ISSUE_SUMMARY_LIMIT, normalizedIssues.size());
        String summary = String.join(", ", normalizedIssues.subList(0, cappedSize));
        if (normalizedIssues.size() > cappedSize) {
            summary = summary + ", ...(+" + (normalizedIssues.size() - cappedSize) + " more)";
        }
        return " Fix these highest-priority validation issues first: "
                + trimToMaxChars(summary, RETRY_ISSUE_SUMMARY_MAX_CHARS)
                + ".";
    }

    private String compactRetrySkeletonHints(DaySkeletonContext skeletonContext, List<String> validationIssues) {
        if (skeletonContext == null) {
            return "";
        }
        List<Integer> dayIndexes = extractRetryDayIndexes(validationIssues);
        if (dayIndexes.isEmpty()) {
            return trimToMaxChars(skeletonContext.promptHintsText(), RETRY_SKELETON_HINTS_MAX_CHARS);
        }
        List<Integer> scopedDays = dayIndexes.stream()
                .distinct()
                .sorted()
                .limit(RETRY_SKELETON_DAY_HINTS_LIMIT)
                .toList();
        List<String> dayHints = scopedDays.stream()
                .map(skeletonContext::promptHintForDay)
                .filter(hint -> hint != null && !hint.isBlank())
                .toList();
        if (dayHints.isEmpty()) {
            return trimToMaxChars(skeletonContext.promptHintsText(), RETRY_SKELETON_HINTS_MAX_CHARS);
        }
        String compact = String.join("; ", dayHints);
        if (dayIndexes.size() > scopedDays.size()) {
            compact = compact + "; ...(+" + (dayIndexes.size() - scopedDays.size()) + " more days)";
        }
        return trimToMaxChars(compact, RETRY_SKELETON_HINTS_MAX_CHARS);
    }

    private String trimToMaxChars(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0 || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        int end = Math.max(0, maxChars - 3);
        return value.substring(0, end).trim() + "...";
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
            retryInstructionsByDay.put(dayIndex, retryInstructionForDay(dayIndex, dayIssues, failedDraft, skeletonContext));
        }
        if (retryInstructionsByDay.isEmpty()) {
            return null;
        }
        return aiService.regeneratePlanDaysPhased(req, failedDraft, targetDayIndexes, retryInstructionsByDay);
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
        if (validationIssues == null || validationIssues.isEmpty()) {
            return List.of();
        }
        List<Integer> dayIndexes = new ArrayList<>();
        for (String issue : validationIssues) {
            Integer dayIndex = extractDayIndex(issue);
            if (dayIndex == null || dayIndex < 1) {
                continue;
            }
            if (!dayIndexes.contains(dayIndex)) {
                dayIndexes.add(dayIndex);
            }
        }
        return dayIndexes;
    }

    private Map<Integer, List<String>> groupIssuesByDay(List<String> validationIssues) {
        Map<Integer, List<String>> issuesByDay = new java.util.LinkedHashMap<>();
        if (validationIssues == null || validationIssues.isEmpty()) {
            return issuesByDay;
        }
        for (String issue : validationIssues) {
            Integer dayIndex = extractDayIndex(issue);
            if (dayIndex == null || dayIndex < 1) {
                continue;
            }
            issuesByDay.computeIfAbsent(dayIndex, key -> new ArrayList<>()).add(issue);
        }
        return issuesByDay;
    }

    private Integer extractDayIndex(String issue) {
        if (issue == null || !issue.startsWith("day-")) {
            return null;
        }
        int secondDash = issue.indexOf('-', 4);
        if (secondDash < 0) {
            return null;
        }
        String token = issue.substring(4, secondDash);
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String retryInstructionForDay(
            int dayIndex,
            List<String> dayIssues,
            PlanDraftResponse failedDraft,
            DaySkeletonContext skeletonContext
    ) {
        String issueInstruction = buildRetryIssueInstructionForDay(dayIndex, dayIssues, failedDraft);
        String dayContext = buildRetryDayContext(failedDraft, dayIndex, skeletonContext);
        String contextClause = dayContext.isBlank()
                ? ""
                : " Current day context: " + dayContext + ".";
        String preservationClause = buildRetryPreservationInstruction(failedDraft, dayIndex, dayIssues);
        String skeletonInstruction = skeletonContext.promptHintForDay(dayIndex);
        String skeletonClause = skeletonInstruction.isBlank()
                ? ""
                : " Follow this day skeleton strictly: " + skeletonInstruction + ".";
        return "Please regenerate only day " + dayIndex + " as a valid itinerary day."
                + preservationClause
                + contextClause
                + issueInstruction
                + skeletonClause;
    }

    private String retryInstructionForWholePlanDay(
            int dayIndex,
            List<String> dayIssues,
            PlanDraftResponse failedDraft,
            DaySkeletonContext skeletonContext,
            String nonDayIssueInstruction
    ) {
        String baseInstruction = retryInstructionForDay(dayIndex, dayIssues, failedDraft, skeletonContext);
        String wholeTripClause = nonDayIssueInstruction == null || nonDayIssueInstruction.isBlank()
                ? ""
                : " " + nonDayIssueInstruction;
        String compactClause = " Keep strings minimal and do not rewrite already-valid content unless needed to resolve the listed whole-trip issues.";
        return baseInstruction + wholeTripClause + compactClause;
    }

    private String buildRetryIssueInstructionForDay(int dayIndex, List<String> dayIssues, PlanDraftResponse failedDraft) {
        if (dayIssues == null || dayIssues.isEmpty()) {
            return "";
        }
        List<String> instructions = new ArrayList<>();
        if (hasAnyIssue(dayIssues, "-missing-lunch")) {
            instructions.add("Add exactly one real lunch venue in the midday window with category restaurant, cafe, food, or dining.");
        }
        if (hasAnyIssue(dayIssues, "-missing-dinner")) {
            instructions.add("Add exactly one real dinner venue in the evening window with category restaurant, cafe, food, or dining.");
        }
        if (hasAnyIssue(dayIssues, "-too-few-non-meal-stops")) {
            instructions.add("Increase the number of non-meal stops for this day until it satisfies the skeleton effective range, while keeping the route compact.");
        }
        if (hasAnyIssue(dayIssues, "-duplicate-poi-across-days")) {
            instructions.add("Replace any stop that duplicates a POI already used on another day with a different real POI in the same area and of a similar visit type. Do not reuse the same attraction, museum, park, lookout, or landmark across multiple days unless no realistic alternative exists.");
            String duplicateDetail = buildDayDuplicateRetryInstruction(dayIndex, failedDraft);
            if (!duplicateDetail.isBlank()) {
                instructions.add(duplicateDetail);
            }
        }
        if (hasAnyIssue(dayIssues, "-duplicate-poi-same-day")) {
            instructions.add("Remove or replace same-day duplicate POIs so each attraction, museum, park, lookout, or landmark appears at most once within this day.");
        }
        if (hasAnyIssue(dayIssues, "-gap-too-large")) {
            instructions.add("Tighten the schedule so adjacent stops do not have oversized idle gaps; prefer compact same-area sequencing.");
        }
        if (hasAnyIssue(dayIssues, "-time-sensitive-too-early")) {
            instructions.add("Move time-sensitive stops later into a suitable window without creating oversized gaps.");
        }
        if (hasAnyIssue(dayIssues, "-time-sensitive-too-late")) {
            instructions.add("Move time-sensitive stops earlier so they occur within suitable operating hours and do not drift late in the day.");
        }
        if (hasAnyIssue(dayIssues, "-time-sensitive-slot-mismatch")) {
            instructions.add("Retime or replace the mismatched stop so its slot and time window fit the venue type.");
        }
        if (hasAnyIssue(dayIssues, "-lunch-time-invalid")) {
            instructions.add("Keep lunch start time inside 11:15-13:00 unless this is a theme-park day.");
        }
        if (hasAnyIssue(dayIssues, "-dinner-time-invalid")) {
            instructions.add("Keep dinner start time inside 17:30-20:00 unless this is a theme-park day.");
        }
        if (hasAnyIssue(dayIssues, "-theme-park-cross-city")) {
            instructions.add("Keep the theme-park day in one remote cluster only. Remove any cross-city jump that leaves the cluster and then returns later.");
        }
        if (instructions.isEmpty()) {
            return " Fix these validation issues for day " + dayIndex + ": " + String.join(", ", dayIssues) + ".";
        }
        return " For day " + dayIndex + ", apply all of these corrections: " + String.join(" ", instructions);
    }

    private String buildWholePlanDuplicateRetryInstruction(List<String> validationIssues) {
        if (!hasAnyIssue(validationIssues, "-duplicate-poi-across-days")) {
            return "";
        }
        return " Eliminate all cross-day duplicate POIs. Every non-meal attraction, museum, park, lookout, landmark, or similar sightseeing stop must appear on only one day.";
    }

    private String buildWholePlanNonDayIssueInstruction(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return "";
        }
        List<String> nonDayIssues = validationIssues.stream()
                .filter(issue -> issue != null && !issue.isBlank())
                .filter(issue -> extractDayIndex(issue) == null)
                .distinct()
                .limit(RETRY_ISSUE_SUMMARY_LIMIT)
                .toList();
        String duplicateInstruction = buildWholePlanDuplicateRetryInstruction(validationIssues);
        if (nonDayIssues.isEmpty()) {
            return duplicateInstruction.isBlank() ? "" : duplicateInstruction.trim();
        }
        String summary = trimToMaxChars(String.join(", ", nonDayIssues), RETRY_ISSUE_SUMMARY_MAX_CHARS);
        if (duplicateInstruction.isBlank()) {
            return "Help resolve these whole-trip issues while keeping this day stable: " + summary + ".";
        }
        return "Help resolve these whole-trip issues while keeping this day stable: "
                + summary
                + ". "
                + duplicateInstruction.trim();
    }

    private String buildWholePlanScopedRetryInstruction(
            PlanDraftResponse failedDraft,
            List<String> validationIssues,
            DaySkeletonContext skeletonContext
    ) {
        if (failedDraft == null || validationIssues == null || validationIssues.isEmpty()) {
            return "";
        }
        List<Integer> scopedDays = extractRetryDayIndexes(validationIssues).stream()
                .distinct()
                .sorted()
                .limit(RETRY_SKELETON_DAY_HINTS_LIMIT)
                .toList();
        if (scopedDays.isEmpty()) {
            return "";
        }
        Map<Integer, List<String>> issuesByDay = groupIssuesByDay(validationIssues);
        List<String> scopedClauses = new ArrayList<>();
        for (Integer dayIndex : scopedDays) {
            List<String> dayIssues = issuesByDay.get(dayIndex);
            if (dayIssues == null || dayIssues.isEmpty()) {
                continue;
            }
            String dayContext = buildRetryDayContext(failedDraft, dayIndex, skeletonContext);
            String issueInstruction = buildRetryIssueInstructionForDay(dayIndex, dayIssues, failedDraft);
            String preservation = buildRetryPreservationInstruction(failedDraft, dayIndex, dayIssues);
            String clause = "day " + dayIndex
                    + "{context=" + dayContext
                    + "; fixes=" + issueInstruction
                    + "; preserve=" + preservation
                    + "}";
            scopedClauses.add(clause);
        }
        if (scopedClauses.isEmpty()) {
            return "";
        }
        String dayList = scopedDays.stream().map(String::valueOf).collect(Collectors.joining(", "));
        String scopedSummary = " Prioritize repairing only these failed days first: " + dayList + ". Keep other days unchanged unless a listed failed day cannot be fixed otherwise. "
                + String.join(" ", scopedClauses);
        return " " + trimToMaxChars(scopedSummary, RETRY_SCOPED_DAY_CONTEXT_MAX_CHARS);
    }

    private String buildWholePlanStableFieldInstruction(PlanDraftResponse failedDraft, List<String> validationIssues) {
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()) {
            return "";
        }
        List<Integer> failedDays = extractRetryDayIndexes(validationIssues);
        List<String> hotelLocks = new ArrayList<>();
        List<String> mealLocks = new ArrayList<>();
        for (DayPlan day : failedDraft.daysPlan()) {
            if (day == null) {
                continue;
            }
            if (day.hotel() != null && day.hotel().name() != null && !day.hotel().name().isBlank()) {
                hotelLocks.add("D" + day.dayIndex() + "=" + day.hotel().name().trim());
            }
            if (failedDays.contains(day.dayIndex())) {
                continue;
            }
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            for (Place stop : stops) {
                if (!isStrictMealStop(stop)) {
                    continue;
                }
                String mealType = normalizeSlot(stop.mealType());
                if (!"lunch".equals(mealType) && !"dinner".equals(mealType)) {
                    continue;
                }
                if (!hasVerifiedMealStop(stop, mealType)) {
                    continue;
                }
                String name = safeStopName(stop);
                mealLocks.add("D" + day.dayIndex() + " " + mealType + "=" + name);
            }
        }
        String hotelClause = hotelLocks.isEmpty()
                ? ""
                : " Preserve these hotels exactly unless a listed validation issue directly requires a hotel change: "
                + trimToMaxChars(String.join("; ", hotelLocks), RETRY_STABLE_FIELDS_MAX_CHARS / 2) + ".";
        String mealClause = mealLocks.isEmpty()
                ? ""
                : " Preserve these already-verified meal venues on unaffected days: "
                + trimToMaxChars(String.join("; ", mealLocks), RETRY_STABLE_FIELDS_MAX_CHARS / 2) + ".";
        if (hotelClause.isBlank() && mealClause.isBlank()) {
            return "";
        }
        return hotelClause + mealClause;
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

    private boolean hasAnyIssue(List<String> issues, String suffix) {
        if (issues == null || issues.isEmpty() || suffix == null || suffix.isBlank()) {
            return false;
        }
        return issues.stream().anyMatch(issue -> issue != null && issue.endsWith(suffix));
    }

    private String buildRetryDayContext(
            PlanDraftResponse draft,
            int dayIndex,
            DaySkeletonContext skeletonContext
    ) {
        DayPlan day = findDayPlan(draft, dayIndex);
        if (day == null) {
            return "";
        }
        List<Place> stops = day.stops() == null ? List.of() : day.stops();
        String stopOrder = stops.isEmpty()
                ? "stops=none"
                : "stops=" + stops.stream()
                .map(this::summarizeRetryStop)
                .collect(Collectors.joining(" -> "));
        boolean hasLunch = stops.stream().anyMatch(stop -> hasMealSlot(stop, "lunch"));
        boolean hasDinner = stops.stream().anyMatch(stop -> hasMealSlot(stop, "dinner"));
        String mealStatus = "meals[lunch=" + (hasLunch ? "present" : "missing")
                + ",dinner=" + (hasDinner ? "present" : "missing") + "]";
        String skeleton = skeletonContext == null ? "" : skeletonContext.promptHintForDay(dayIndex);
        String skeletonRange = extractEffectiveRangeFromSkeletonHint(skeleton);
        String nonMealStatus = "nonMeal=count " + countNonMealStops(stops)
                + (skeletonRange.isBlank() ? "" : ", target " + skeletonRange);
        return stopOrder + "; " + mealStatus + "; " + nonMealStatus;
    }

    private String buildRetryPreservationInstruction(
            PlanDraftResponse draft,
            int dayIndex,
            List<String> dayIssues
    ) {
        DayPlan day = findDayPlan(draft, dayIndex);
        if (day == null || day.stops() == null || day.stops().isEmpty()) {
            return " Preserve unchanged stops when possible.";
        }
        List<Place> stops = day.stops();
        RetryAdjustmentBuckets buckets = collectRetryAdjustmentBuckets(dayIssues, stops);
        List<String> mustKeep = new ArrayList<>();
        List<String> mayRetime = new ArrayList<>();
        List<String> mayReplace = new ArrayList<>();
        List<String> mayInsertAround = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Place stop = stops.get(i);
            String summary = summarizeRetryStop(stop);
            boolean retime = buckets.mayRetime().contains(i);
            boolean replace = buckets.mayReplace().contains(i);
            boolean insertAround = buckets.mayInsertAround().contains(i);
            if (!retime && !replace && !insertAround) {
                mustKeep.add(summary);
                continue;
            }
            if (retime) {
                mayRetime.add(summary);
            }
            if (replace) {
                mayReplace.add(summary);
            }
            if (insertAround) {
                mayInsertAround.add(summary);
            }
        }
        String mustKeepClause = mustKeep.isEmpty()
                ? "mustKeep=none"
                : "mustKeep=" + String.join(" | ", mustKeep);
        String mayRetimeClause = mayRetime.isEmpty()
                ? "mayRetime=none"
                : "mayRetime=" + String.join(" | ", mayRetime);
        String mayReplaceClause = mayReplace.isEmpty()
                ? "mayReplace=none"
                : "mayReplace=" + String.join(" | ", mayReplace);
        String mayInsertAroundClause = mayInsertAround.isEmpty()
                ? "mayInsertAround=none"
                : "mayInsertAround=" + String.join(" | ", mayInsertAround);
        return " Preserve unchanged stops when possible. Keep the must-keep stops stable unless they block a valid repair. "
                + "Stop preservation: " + mustKeepClause + "; "
                + mayRetimeClause + "; "
                + mayReplaceClause + "; "
                + mayInsertAroundClause + ".";
    }

    private RetryAdjustmentBuckets collectRetryAdjustmentBuckets(List<String> dayIssues, List<Place> stops) {
        java.util.Set<Integer> mayRetime = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> mayReplace = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> mayInsertAround = new java.util.LinkedHashSet<>();
        if (dayIssues == null || dayIssues.isEmpty()) {
            return new RetryAdjustmentBuckets(mayRetime, mayReplace, mayInsertAround);
        }
        for (String issue : dayIssues) {
            Matcher matcher = STOP_ISSUE_PATTERN.matcher(issue == null ? "" : issue);
            if (matcher.matches()) {
                int stopIndex = Integer.parseInt(matcher.group(2)) - 1;
                if (stopIndex >= 0 && stopIndex < stops.size()) {
                    if (issue.endsWith("-gap-too-large")
                            || issue.endsWith("-time-sensitive-too-early")
                            || issue.endsWith("-time-sensitive-too-late")
                            || issue.endsWith("-time-sensitive-slot-mismatch")) {
                        mayRetime.add(stopIndex);
                    } else if (issue.endsWith("-duplicate-poi-across-days")) {
                        mayReplace.add(stopIndex);
                    } else {
                        mayReplace.add(stopIndex);
                    }
                }
                continue;
            }
            if (issue.endsWith("-missing-lunch")) {
                addMealStopIndexes(mayInsertAround, stops, "lunch");
            }
            if (issue.endsWith("-missing-dinner")) {
                addMealStopIndexes(mayInsertAround, stops, "dinner");
            }
            if (issue.endsWith("-too-few-non-meal-stops")) {
                for (int i = 0; i < stops.size(); i++) {
                    if (isCountedNonMealStop(stops.get(i))) {
                        mayReplace.add(i);
                    }
                }
                mayInsertAround.addAll(mayReplace);
            }
            if (issue.endsWith("-theme-park-cross-city")) {
                for (int i = 0; i < stops.size(); i++) {
                    if (!isStrictMealStop(stops.get(i))) {
                        mayReplace.add(i);
                    }
                }
            }
        }
        return new RetryAdjustmentBuckets(mayRetime, mayReplace, mayInsertAround);
    }

    private void addMealStopIndexes(java.util.Set<Integer> indexes, List<Place> stops, String slot) {
        for (int i = 0; i < stops.size(); i++) {
            if (hasMealSlot(stops.get(i), slot)) {
                indexes.add(i);
            }
        }
    }

    private record RetryAdjustmentBuckets(
            java.util.Set<Integer> mayRetime,
            java.util.Set<Integer> mayReplace,
            java.util.Set<Integer> mayInsertAround
    ) {}

    private DayPlan findDayPlan(PlanDraftResponse draft, int dayIndex) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return null;
        }
        return draft.daysPlan().stream()
                .filter(day -> day != null && day.dayIndex() == dayIndex)
                .findFirst()
                .orElse(null);
    }

    private String summarizeRetryStop(Place stop) {
        if (stop == null) {
            return "unknown";
        }
        String name = stop.name() == null || stop.name().isBlank() ? "unnamed" : stop.name().trim();
        String slot = stop.timeSlot() == null || stop.timeSlot().isBlank() ? "unslotted" : stop.timeSlot().trim();
        String time = joinNonBlank("-", stop.startTime(), stop.endTime());
        return name + "[" + slot + (time.isBlank() ? "" : "," + time) + "]";
    }

    private String extractEffectiveRangeFromSkeletonHint(String skeletonHint) {
        if (skeletonHint == null || skeletonHint.isBlank()) {
            return "";
        }
        String marker = "effectiveNonMeal=";
        int start = skeletonHint.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int from = start + marker.length();
        int end = skeletonHint.indexOf(',', from);
        if (end < 0) {
            end = skeletonHint.indexOf('}', from);
        }
        if (end < 0 || end <= from) {
            return "";
        }
        return skeletonHint.substring(from, end).trim();
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
        PlanDraftResponse relaxedDraft = withPace(draft, "relaxed");
        CreatePlanReq relaxedReq = req == null
                ? null
                : new CreatePlanReq(req.city(), req.days(), req.budget(), req.party(), req.style(), "relaxed", req.mainModel(), req.departureDate());
        List<String> relaxedIssues = validateDraft(relaxedDraft, relaxedReq);
        return relaxedIssues.isEmpty() ? relaxedDraft : null;
    }

    private PlanDraftResponse withPace(PlanDraftResponse draft, String pace) {
        if (draft == null) {
            return null;
        }
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                pace,
                draft.title(),
                draft.overview(),
                draft.daysPlan(),
                draft.copyPolishStatus()
        );
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
                return withCopyPolishStatus(mergeAllowedCopyFields(verifiedDraft, result.draft()), "completed");
            }
            return withCopyPolishStatus(verifiedDraft, "fallback-" + result.status());
        } catch (Exception e) {
            appendStageTiming(timingSummary, timingLabel, System.currentTimeMillis() - startedAt);
            log.debug("Copy polish fallback to verified draft", e);
            return withCopyPolishStatus(verifiedDraft, "error");
        }
    }

    public PlanDraftResponse applyCopyPolishPatch(PlanDraftResponse verifiedDraft) {
        if (verifiedDraft == null) {
            return null;
        }
        try {
            TripAiService.CopyPolishResult result = aiService.polishPlanCopy(verifiedDraft);
            if (result.completed()) {
                return withCopyPolishStatus(mergeAllowedCopyFields(verifiedDraft, result.draft()), "completed");
            }
            return withCopyPolishStatus(verifiedDraft, "fallback-" + result.status());
        } catch (Exception e) {
            log.debug("Async copy polish fallback to verified draft", e);
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
        if (draft == null) {
            return null;
        }
        PlanDraftResponse safeDraft = finalScheduleSafetyPass(draft);
        safeDraft = sanitizeFinalCopy(safeDraft);
        return new PlanDraftResponse(
                safeDraft.city(),
                safeDraft.country(),
                safeDraft.days(),
                safeDraft.currency(),
                safeDraft.party(),
                safeDraft.pace(),
                safeDraft.title(),
                safeDraft.overview(),
                safeDraft.daysPlan(),
                status
        );
    }

    private PlanDraftResponse sanitizeFinalCopy(PlanDraftResponse draft) {
        if (draft == null) {
            return null;
        }
        List<DayPlan> days = draft.daysPlan() == null ? List.of() : draft.daysPlan().stream()
                .map(this::sanitizeFinalDayCopy)
                .toList();
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                sanitizeNarrativeCopyStrict(draft.title(), finalTitleFallback(draft)),
                sanitizeNarrativeCopyStrict(draft.overview(), finalOverviewFallback(draft)),
                days,
                draft.copyPolishStatus()
        );
    }

    private DayPlan sanitizeFinalDayCopy(DayPlan day) {
        if (day == null) {
            return null;
        }
        List<Place> stops = day.stops() == null ? List.of() : day.stops().stream()
                .map(stop -> sanitizeFinalPlaceCopy(stop, day))
                .toList();
        Place hotel = sanitizeFinalPlaceCopy(day.hotel());
        return new DayPlan(
                day.dayIndex(),
                hotel,
                stops,
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.theme(), day), "Day " + day.dayIndex()),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.morningNote(), day), finalDayCopyFallback(day, "morning")),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.afternoonNote(), day), finalDayCopyFallback(day, "afternoon")),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.eveningNote(), day), finalDayCopyFallback(day, "evening")),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.note(), day), finalDayCopyFallback(day, "day"))
        );
    }

    private Place sanitizeFinalPlaceCopy(Place stop) {
        return sanitizeFinalPlaceCopy(stop, null);
    }

    private Place sanitizeFinalPlaceCopy(Place stop, DayPlan day) {
        if (stop == null) {
            return null;
        }
        String reasonFallback = finalReasonFallback(stop);
        String tipFallback = finalTipFallback(stop);
        return new Place(
                stop.name(),
                stop.addressLine(),
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
                sanitizeNarrativeCopyStrict(cleanUnsupportedPlaceReferences(stop.reason(), stop, day), reasonFallback),
                sanitizeNarrativeCopyStrict(cleanUnsupportedPlaceReferences(stop.tip(), stop, day), tipFallback),
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                stop.latitude(),
                stop.longitude()
        );
    }

    private PlanDraftResponse finalScheduleSafetyPass(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<DayPlan> adjustedDays = draft.daysPlan().stream()
                .map(day -> {
                    List<Place> stops = day.stops() == null ? List.of() : day.stops();
                    List<Place> adjustedStops = enforceLargeAttractionContinuationMinimum(stops);
                    return new DayPlan(
                            day.dayIndex(),
                            day.hotel(),
                            adjustedStops,
                            day.theme(),
                            day.morningNote(),
                            day.afternoonNote(),
                            day.eveningNote(),
                            day.note()
                    );
                })
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
                adjustedDays,
                draft.copyPolishStatus()
        );
    }

    private PlanDraftResponse mergeAllowedCopyFields(PlanDraftResponse base, PlanDraftResponse polished) {
        if (base == null || polished == null) {
            return base;
        }
        List<DayPlan> baseDays = base.daysPlan() == null ? List.of() : base.daysPlan();
        List<DayPlan> polishedDays = polished.daysPlan() == null ? List.of() : polished.daysPlan();
        List<DayPlan> mergedDays = new ArrayList<>();
        for (int i = 0; i < baseDays.size(); i++) {
            DayPlan baseDay = baseDays.get(i);
            DayPlan polishedDay = i < polishedDays.size() ? polishedDays.get(i) : null;
            mergedDays.add(mergeDayCopy(baseDay, polishedDay));
        }
        return new PlanDraftResponse(
                base.city(),
                base.country(),
                base.days(),
                base.currency(),
                base.party(),
                base.pace(),
                base.title(),
                selectCopy(polished.overview(), base.overview()),
                mergedDays,
                base.copyPolishStatus()
        );
    }

    private DayPlan mergeDayCopy(DayPlan base, DayPlan polished) {
        if (base == null) {
            return null;
        }
        List<Place> baseStops = base.stops() == null ? List.of() : base.stops();
        List<Place> polishedStops = polished == null || polished.stops() == null ? List.of() : polished.stops();
        List<Place> mergedStops = new ArrayList<>();
        for (int i = 0; i < baseStops.size(); i++) {
            Place polishedStop = i < polishedStops.size() ? polishedStops.get(i) : null;
            mergedStops.add(mergePlaceCopy(baseStops.get(i), polishedStop));
        }
        return new DayPlan(
                base.dayIndex(),
                mergePlaceCopy(base.hotel(), polished == null ? null : polished.hotel()),
                mergedStops,
                base.theme(),
                base.morningNote(),
                base.afternoonNote(),
                base.eveningNote(),
                base.note()
        );
    }

    private Place mergePlaceCopy(Place base, Place polished) {
        if (base == null) {
            return null;
        }
        String reason = selectCopy(polished == null ? null : polished.reason(), base.reason());
        String tip = selectCopy(polished == null ? null : polished.tip(), base.tip());
        if (isThemeParkLikeStop(base)) {
            reason = sanitizeThemeParkCopy(reason);
            tip = sanitizeThemeParkCopy(tip);
        }
        return new Place(
                base.name(),
                base.addressLine(),
                base.suburb(),
                base.city(),
                base.state(),
                base.postcode(),
                base.country(),
                base.category(),
                base.stayMinutes(),
                base.timeSlot(),
                base.startTime(),
                base.endTime(),
                base.mealType(),
                base.preferredArea(),
                base.cuisine(),
                base.vibe(),
                base.budgetLevel(),
                reason,
                tip,
                base.websiteUri(),
                base.googleMapsUri(),
                base.businessStatus(),
                base.url(),
                base.latitude(),
                base.longitude()
        );
    }

    private String selectCopy(String candidate, String fallback) {
        String selected = candidate == null || candidate.isBlank() ? fallback : candidate.trim();
        return sanitizeNarrativeCopy(selected);
    }

    private String sanitizeNarrativeCopy(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = value
                .replaceAll("(?i)\\bwalkable\\b", "compact")
                .replaceAll("(?i)\\btransit-friendly\\b", "manageable")
                .replaceAll("(?i)\\btransit friendly\\b", "manageable")
                .replaceAll("(?i)\\btour access\\b", "scheduled access")
                .replaceAll("(?i)\\btours\\b", "scheduled visits")
                .replaceAll("(?i)\\btour\\b", "scheduled visit")
                .trim();
        sanitized = removeRiskyNarrativeSentences(sanitized);
        return sanitized.isBlank() ? value.replaceAll("(?i)\\bpriority access\\b", "optional add-ons")
                .replaceAll("(?i)\\btimed entry\\b", "entry requirements")
                .replaceAll("(?i)\\bferry terminal\\b", "nearby access point")
                .replaceAll("(?i)\\bparking hassles\\b", "access issues")
                .replaceAll("(?i)\\bshuttle\\b", "transport")
                .replaceAll("(?i)\\bpriority access\\b", "optional access")
                .replaceAll("(?i)\\bguaranteed\\b", "planned")
                .replaceAll("(?i)\\bfreshest\\b", "fresh")
                .replaceAll("(?i)\\bbest\\b", "good")
                .trim() : sanitized;
    }

    private String sanitizeNarrativeCopyStrict(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null || fallback.isBlank() ? "" : fallback.trim();
        }
        String sanitized = value
                .replaceAll("(?i)\\bwalkable\\b", "compact")
                .replaceAll("(?i)\\btransit-friendly\\b", "manageable")
                .replaceAll("(?i)\\btransit friendly\\b", "manageable")
                .replaceAll("(?i)\\btour access\\b", "scheduled access")
                .replaceAll("(?i)\\btours\\b", "scheduled visits")
                .replaceAll("(?i)\\btour\\b", "scheduled visit")
                .trim();
        sanitized = removeRiskyNarrativeSentences(sanitized);
        if (!sanitized.isBlank()) {
            return sanitized;
        }
        return fallback == null || fallback.isBlank() ? "" : fallback.trim();
    }

    private String finalTitleFallback(PlanDraftResponse draft) {
        String city = draft == null || draft.city() == null || draft.city().isBlank() ? "Trip" : draft.city().trim();
        int days = draft == null || draft.days() <= 0 ? 1 : draft.days();
        return city + " " + days + "-Day Itinerary";
    }

    private String finalOverviewFallback(PlanDraftResponse draft) {
        String city = draft == null || draft.city() == null || draft.city().isBlank() ? "the destination" : draft.city().trim();
        return "A practical itinerary for " + city + " built around the confirmed stops and meal breaks.";
    }

    private String cleanUnsupportedDayReferences(String value, DayPlan day) {
        if (value == null || value.isBlank() || day == null) {
            return value;
        }
        String cleaned = value;
        if (!dayHasStopContaining(day, "royal botanic garden")) {
            cleaned = removeNarrativeSegments(cleaned, "royal botanic garden", "botanic garden");
        }
        if (!dayHasStopContaining(day, "harbour bridge pedestrian")
                && !dayHasStopContaining(day, "harbour bridge pylon")
                && !dayHasStopContaining(day, "harbour bridge walkway")) {
            cleaned = removeNarrativeSegments(cleaned, "harbour bridge pedestrian", "bridge pedestrian path", "scenic bridge walk", "scenic walk across the harbour bridge");
        }
        if (!dayHasStopContaining(day, "lavender bay reserve")) {
            cleaned = removeNarrativeSegments(cleaned, "lavender bay reserve");
        }
        if (!dayHasStopContaining(day, "cadman")) {
            cleaned = removeNarrativeSegments(cleaned, "cadman's cottage", "cadmans cottage", "cadman");
        }
        if (!dayHasStopContaining(day, "circular quay lookout")) {
            cleaned = removeNarrativeSegments(cleaned, "circular quay lookout");
        }
        if (!dayHasStopContaining(day, "big dipper")) {
            cleaned = removeNarrativeSegments(cleaned, "big dipper");
        }
        if (!dayHasStopContaining(day, "taronga")) {
            cleaned = removeNarrativeSegments(cleaned, "taronga zoo", "taronga");
        }
        cleaned = removeNarrativeSegments(cleaned,
                "ferry from",
                "ferry to",
                "small galleries",
                "leafy streets",
                "entrance plaza",
                "quiet bayside moments");
        if (!dayHasZooOrWildlifeStop(day)) {
            cleaned = removeNarrativeSegments(cleaned, "animal encounters", "wildlife");
        }
        cleaned = removeUnsupportedMealVenueClaims(cleaned, day);
        cleaned = fixMarketLunchOrderNarrative(cleaned, day);
        if (!dayHasViewStop(day)) {
            cleaned = removeNarrativeSegments(cleaned, "sunset pause", "sunset views", "golden hour", "harbour lookouts", "harbor lookouts", "scenic views", "skyline views", "scenic dinner");
        }
        return normalizeNarrativePunctuation(cleaned);
    }

    private String cleanUnsupportedPlaceReferences(String value, Place stop, DayPlan day) {
        if (value == null || value.isBlank() || stop == null) {
            return value;
        }
        String cleaned = value;
        String stopText = joinText(stop.name(), stop.category(), stop.preferredArea()).toLowerCase(Locale.ROOT);
        if (!stopText.contains("taronga") && joinText(cleaned).toLowerCase(Locale.ROOT).contains("taronga")) {
            return "";
        }
        if (day != null && !dayHasZooOrWildlifeStop(day)) {
            cleaned = removeNarrativeSegments(cleaned, "animal encounters", "wildlife");
        }
        cleaned = removeNarrativeSegments(cleaned, "ferry to");
        return normalizeNarrativePunctuation(cleaned);
    }

    private String fixMarketLunchOrderNarrative(String value, DayPlan day) {
        if (value == null || value.isBlank() || day == null || !lunchComesBeforeMarket(day)) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.contains("market") || !lower.contains("lunch")) {
            return value;
        }
        int marketPos = lower.indexOf("market");
        int lunchPos = lower.indexOf("lunch");
        if (marketPos < lunchPos) {
            return "After lunch, continue with the market stop and afternoon visit.";
        }
        return value;
    }

    private String invalidJsonRetryInstruction(JsonProcessingException parseFailure) {
        String reason = parseFailure == null || parseFailure.getOriginalMessage() == null
                ? "malformed json"
                : parseFailure.getOriginalMessage();
        return "The previous response was invalid JSON and could not be parsed (" + reason + "). "
                + "Return the full itinerary again as one complete valid JSON object only. "
                + "Do not truncate output. Close every quote, bracket, and brace. "
                + "Ensure every string field is properly escaped, especially addressLine, reason, tip, theme, and note. "
                + "Do not include markdown fences or commentary.";
    }

    private String shortRawPreview(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 237) + "...";
    }

    private boolean lunchComesBeforeMarket(DayPlan day) {
        if (day == null || day.stops() == null) {
            return false;
        }
        int lunchIndex = firstMealIndex(day.stops(), "lunch");
        int marketIndex = firstMarketShoppingIndex(day.stops());
        return lunchIndex >= 0 && marketIndex >= 0 && lunchIndex < marketIndex;
    }

    private int firstMarketShoppingIndex(List<Place> stops) {
        if (stops == null) {
            return -1;
        }
        for (int i = 0; i < stops.size(); i++) {
            if (isMarketShoppingLikeStop(stops.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private String removeUnsupportedMealVenueClaims(String value, DayPlan day) {
        if (value == null || value.isBlank() || day == null) {
            return value;
        }
        String lunchName = mealStopName(day, "lunch");
        String dinnerName = mealStopName(day, "dinner");
        String[] sentences = value.split("(?<=[.!?])\\s+");
        List<String> keptSentences = new ArrayList<>();
        for (String sentence : sentences) {
            String[] clauses = sentence.split("\\s*,\\s*|\\s+then\\s+|\\s+followed by\\s+|\\s+and then\\s+");
            List<String> keptClauses = new ArrayList<>();
            for (String clause : clauses) {
                if (!isUnsupportedMealVenueClaim(clause, lunchName, dinnerName) && !clause.isBlank()) {
                    keptClauses.add(clause.trim());
                }
            }
            if (!keptClauses.isEmpty()) {
                keptSentences.add(String.join(", ", keptClauses));
            }
        }
        return String.join(" ", keptSentences).trim();
    }

    private boolean isUnsupportedMealVenueClaim(String clause, String lunchName, String dinnerName) {
        if (clause == null || clause.isBlank()) {
            return false;
        }
        String lower = clause.toLowerCase(Locale.ROOT);
        if (containsMealVenuePhrase(lower, "lunch")) {
            return !mentionsMealStop(clause, lunchName);
        }
        if (containsMealVenuePhrase(lower, "dinner")
                || lower.contains("dine at ")
                || lower.contains("dine in ")
                || lower.contains("dining at ")) {
            return !mentionsMealStop(clause, dinnerName);
        }
        return false;
    }

    private boolean containsMealVenuePhrase(String lower, String meal) {
        return lower.contains(meal + " at ")
                || lower.contains(meal + " in ")
                || lower.contains("have " + meal + " at ")
                || lower.contains("have " + meal + " in ")
                || lower.contains("enjoy " + meal + " at ")
                || lower.contains("enjoy " + meal + " in ")
                || lower.contains("end the day with " + meal + " at ")
                || lower.contains("finish with " + meal + " at ");
    }

    private String mealStopName(DayPlan day, String mealType) {
        if (day == null || day.stops() == null || mealType == null) {
            return "";
        }
        String target = mealType.toLowerCase(Locale.ROOT);
        return day.stops().stream()
                .filter(stop -> stop != null && isStrictMealStop(stop))
                .filter(stop -> target.equals(normalizeSlot(stop.mealType())) || target.equals(normalizeSlot(stop.timeSlot())))
                .map(Place::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("");
    }

    private boolean mentionsMealStop(String clause, String mealName) {
        if (clause == null || mealName == null || mealName.isBlank()) {
            return false;
        }
        String normalizedClause = normalizeNameForNarrativeMatch(clause);
        String normalizedMeal = normalizeNameForNarrativeMatch(mealName);
        if (normalizedMeal.isBlank() || normalizedClause.contains(normalizedMeal)) {
            return true;
        }
        List<String> significant = List.of(normalizedMeal.split(" ")).stream()
                .filter(token -> token.length() >= 4)
                .filter(token -> !List.of("restaurant", "cafe", "bistro", "grill", "bar", "and", "the").contains(token))
                .limit(2)
                .toList();
        return !significant.isEmpty() && significant.stream().allMatch(normalizedClause::contains);
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

    private String removeNarrativeSegments(String value, String... lowerNeedles) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String[] sentences = value.split("(?<=[.!?])\\s+");
        List<String> keptSentences = new ArrayList<>();
        for (String sentence : sentences) {
            String cleaned = removeNarrativeClauses(sentence, lowerNeedles);
            if (!cleaned.isBlank()) {
                keptSentences.add(cleaned);
            }
        }
        return String.join(" ", keptSentences).trim();
    }

    private String removeNarrativeClauses(String sentence, String... lowerNeedles) {
        if (sentence == null || sentence.isBlank()) {
            return "";
        }
        String[] clauses = sentence.split("\\s*,\\s*|\\s+then\\s+|\\s+followed by\\s+|\\s+and then\\s+");
        List<String> kept = new ArrayList<>();
        for (String clause : clauses) {
            String lower = clause.toLowerCase(Locale.ROOT);
            boolean unsupported = false;
            for (String needle : lowerNeedles) {
                if (needle != null && !needle.isBlank() && lower.contains(needle)) {
                    unsupported = true;
                    break;
                }
            }
            if (!unsupported && !clause.isBlank()) {
                kept.add(clause.trim());
            }
        }
        if (kept.isEmpty()) {
            return "";
        }
        return String.join(", ", kept).trim();
    }

    private String normalizeNarrativePunctuation(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("\\s+,", ",")
                .replaceAll(",\\s*\\.", ".")
                .replaceAll("(?i)\\bcompact\\s*,\\s*compact\\b", "compact")
                .replaceAll("(?i)\\bmanageable\\s*,\\s*manageable\\b", "manageable")
                .replaceAll("(?i)\\bflexible\\s*,\\s*flexible\\b", "flexible")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.!?])", "$1")
                .trim();
    }

    private boolean dayHasStopContaining(DayPlan day, String needle) {
        if (day == null || needle == null || needle.isBlank()) {
            return false;
        }
        String target = needle.toLowerCase(Locale.ROOT);
        List<Place> stops = day.stops() == null ? List.of() : day.stops();
        return stops.stream().anyMatch(stop -> {
            String text = joinText(stop.name(), stop.category(), stop.preferredArea()).toLowerCase(Locale.ROOT);
            return text.contains(target);
        });
    }

    private boolean dayHasViewStop(DayPlan day) {
        if (day == null || day.stops() == null) {
            return false;
        }
        return day.stops().stream().anyMatch(stop -> isLateDayViewStop(stop)
                || joinText(stop.name(), stop.category(), stop.reason()).toLowerCase(Locale.ROOT).contains("view"));
    }

    private boolean dayHasZooOrWildlifeStop(DayPlan day) {
        if (day == null || day.stops() == null) {
            return false;
        }
        return day.stops().stream().anyMatch(stop -> {
            String text = joinText(stop.name(), stop.category(), stop.preferredArea()).toLowerCase(Locale.ROOT);
            return text.contains("zoo") || text.contains("wildlife") || text.contains("animal");
        });
    }

    private String finalReasonFallback(Place stop) {
        String name = stop == null || stop.name() == null || stop.name().isBlank() ? "This stop" : stop.name();
        if (stop != null && isStrictMealStop(stop)) {
            return name + " provides a practical meal break for this part of the day.";
        }
        if (stop != null && "hotel".equals(normalizeSlot(stop.category()))) {
            return name + " provides a practical base for this itinerary.";
        }
        if (stop != null && isThemeParkLikeStop(stop)) {
            return name + " is the main theme park focus for this day.";
        }
        return name + " fits the day's route and keeps the schedule manageable.";
    }

    private String finalTipFallback(Place stop) {
        if (stop != null && isStrictMealStop(stop)) {
            return "Keep this meal stop flexible if timing changes during the day.";
        }
        if (stop != null && isThemeParkLikeStop(stop)) {
            return "Keep the visit flexible around weather, energy, and park conditions.";
        }
        return "Keep this stop flexible if the day starts running late.";
    }

    private String finalDayCopyFallback(DayPlan day, String part) {
        String names = day == null || day.stops() == null ? "" : day.stops().stream()
                .filter(stop -> stop != null && !isStrictMealStop(stop))
                .map(Place::name)
                .filter(name -> name != null && !name.isBlank())
                .limit(2)
                .collect(Collectors.joining(" and "));
        if (names.isBlank()) {
            return "Keep this part of the day flexible around the confirmed stops.";
        }
        return switch (part) {
            case "morning" -> "Start with " + names + " while keeping the route compact.";
            case "afternoon" -> "Continue around the confirmed stops without adding extra backtracking.";
            case "evening" -> "Finish with the planned meal stop and keep timing flexible.";
            default -> "This day follows the confirmed stop order around " + names + ".";
        };
    }

    private String removeRiskyNarrativeSentences(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        StringBuilder kept = new StringBuilder();
        for (String sentence : value.split("(?<=[.!?])\\s+|;\\s*")) {
            String trimmed = sentence.trim();
            if (trimmed.isBlank() || containsRiskyNarrativeClaim(trimmed)) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append(' ');
            }
            kept.append(trimmed);
        }
        return kept.toString().trim();
    }

    private boolean containsRiskyNarrativeClaim(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.contains("priority access")
                || text.contains("timed entry")
                || text.contains("timed ticket")
                || text.contains("timed tickets")
                || text.contains("ferry terminal")
                || text.contains("ferry from")
                || text.contains("ferry to")
                || text.contains("opening hours")
                || text.contains("open daily")
                || text.contains("open weekends")
                || text.contains("weekend-only")
                || text.contains("weekends only")
                || text.contains("last entry")
                || text.contains("opens at")
                || text.contains("closes at")
                || text.contains("open late")
                || text.contains("adjusted to")
                || text.contains("book ahead")
                || text.contains("reserve ahead")
                || text.contains("reserve in advance")
                || text.contains("reserve dinner ahead")
                || text.contains("guarantee seating")
                || text.contains("accepts walk-ins")
                || text.contains("accepts walk ins")
                || text.contains("signature dish")
                || text.contains("signature dishes")
                || text.contains("must-try")
                || text.contains("must try")
                || text.contains("freshest")
                || text.contains("best in")
                || text.contains("best seating")
                || text.contains("preferred seating")
                || text.contains("window-side")
                || text.contains("window side")
                || text.contains("window seats")
                || text.contains("outdoor seating")
                || text.contains("balcony seating")
                || text.contains("upper-level tables")
                || text.contains("upper level tables")
                || text.contains("guaranteed")
                || text.contains("complimentary")
                || text.contains("only verified")
                || text.contains("operationally active")
                || text.contains("verified, accessible")
                || text.contains("elevated terraces")
                || text.contains("classic attractions like")
                || text.contains("small galleries")
                || text.contains("leafy streets")
                || text.contains("entrance plaza")
                || text.contains("quiet bayside moments")
                || text.contains("harbour lookouts")
                || text.contains("harbor lookouts")
                || text.contains("scenic views")
                || text.contains("skyline views")
                || text.contains("scenic dinner")
                || text.contains("cash only")
                || text.contains("card accepted")
                || text.contains("cards accepted")
                || text.contains("payment")
                || text.matches(".*\\b\\d{1,2}:\\d{2}\\s*(am|pm)?\\b.*")
                || text.matches(".*\\b\\d{1,2}\\s*(am|pm)\\b.*")
                || text.matches(".*\\b\\d+\\s*[- ]?minute\\b.*")
                || text.matches(".*\\b\\d+\\s*min\\b.*")
                || text.contains(" bus ")
                || text.contains(" taxi")
                || text.contains(" train")
                || text.contains(" ferry")
                || text.contains(" tram")
                || text.contains(" by bus")
                || text.contains(" by taxi")
                || text.contains(" by train")
                || text.contains(" by ferry")
                || text.contains(" by tram")
                || text.contains("parking hassle")
                || text.contains("shuttle")
                || text.contains("ride access")
                || text.contains("ride schedule")
                || text.contains("ride schedules")
                || text.contains("timed access")
                || text.contains("show schedule")
                || text.contains("show schedules")
                || text.contains("wait time");
    }

    private List<Place> enforceLargeAttractionContinuationMinimum(List<Place> stops) {
        if (stops == null || stops.size() < 2) {
            return stops == null ? List.of() : stops;
        }
        List<Place> adjusted = new ArrayList<>(stops);
        boolean changed = false;
        for (int i = 0; i < adjusted.size(); i++) {
            Place stop = adjusted.get(i);
            if (!isLargeAttractionContinuationStop(stop)) {
                continue;
            }
            int start = parseTimeMinutes(stop.startTime());
            int end = parseTimeMinutes(stop.endTime());
            if (start < 0 || end < 0 || end - start >= THEME_PARK_AFTERNOON_CONTINUATION_MINUTES) {
                continue;
            }
            int targetEnd = start + THEME_PARK_AFTERNOON_CONTINUATION_MINUTES;
            adjusted.set(i, copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(targetEnd), THEME_PARK_AFTERNOON_CONTINUATION_MINUTES));
            changed = true;
            int previousEnd = targetEnd;
            for (int j = i + 1; j < adjusted.size(); j++) {
                Place next = adjusted.get(j);
                int nextStart = parseTimeMinutes(next.startTime());
                int nextEnd = parseTimeMinutes(next.endTime());
                int nextStay = resolveStayMinutes(next);
                int minStart = Math.max(timeSensitiveEarliestStart(next), previousEnd + transitionMinutes(false));
                if (nextStart < minStart || nextEnd <= nextStart) {
                    adjusted.set(j, copyPlaceWithTimes(next, formatMinutes(minStart), formatMinutes(minStart + nextStay), nextStay));
                    previousEnd = minStart + nextStay;
                } else {
                    previousEnd = nextEnd;
                }
            }
        }
        return changed ? adjusted : stops;
    }

    private boolean isLargeAttractionContinuationStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String name = nullToEmpty(stop.name()).toLowerCase(Locale.ROOT);
        String slot = normalizeSlot(stop.timeSlot());
        return "afternoon".equals(slot)
                && (name.contains("afternoon visit")
                || name.contains("continued visit")
                || name.contains("continuation")
                || name.contains("return visit"));
    }

    private List<String> validateThemeParkLocation(String requestedCity, int dayIndex, int stopIndex, Place stop) {
        if (!isThemeParkLikeStop(stop)) {
            return List.of();
        }
        StopCoordinate cityCenter = cityCenterCoordinate(requestedCity);
        StopCoordinate stopCoordinate = coordinateOf(stop);
        if (cityCenter == null || stopCoordinate == null) {
            return List.of();
        }
        double distanceMeters = haversineMeters(cityCenter.lat(), cityCenter.lon(), stopCoordinate.lat(), stopCoordinate.lon());
        if (distanceMeters > THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS) {
            return List.of("day-" + dayIndex + "-stop-" + stopIndex + "-theme-park-cross-city");
        }
        return List.of();
    }

    private List<String> validateTimeSensitiveStop(int dayIndex, int stopIndex, Place stop, int startMinutes, int endMinutes) {
        List<String> issues = new ArrayList<>();
        String name = stop == null || stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        String timeSlot = normalizeSlot(stop == null ? null : stop.timeSlot());

        boolean museumLike = isCulturalOpeningHoursConstrained(stop);
        if (museumLike && startMinutes >= 0 && startMinutes < 10 * 60) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-too-early");
        }
        if (museumLike && endMinutes > CULTURAL_POI_LATEST_END_MINUTES) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-too-late");
        }

        boolean penguinLike = name.contains("penguin");
        if (penguinLike && startMinutes >= 0 && startMinutes < 16 * 60 + 30) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-too-early");
        }
        if (penguinLike
                && !timeSlot.isBlank()
                && !"sunset".equals(timeSlot)
                && !"evening".equals(timeSlot)
                && !"night".equals(timeSlot)) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-slot-mismatch");
        }
        return issues;
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
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }

        List<DayPlan> updatedDays = new ArrayList<>();
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            if (stops.isEmpty()) {
                updatedDays.add(day);
                continue;
            }

            List<Place> workingStops = new ArrayList<>(stops);
            boolean changed;
            do {
                changed = false;
                for (int i = 0; i < workingStops.size(); i++) {
                    Place stop = workingStops.get(i);
                    if (isNonMealOccupyingMealSlot(stop)) {
                        workingStops.set(i, clearMealSlotForNonMealStop(stop));
                        changed = true;
                        break;
                    }
                    if (isCompoundAttractionStop(stop)) {
                        Place simplified = simplifyCompoundStop(stop);
                        if (simplified != null) {
                            workingStops.set(i, simplified);
                        } else {
                            if (wouldDropBelowMinNonMealStops(workingStops, stop, minNonMealStops)) {
                                continue;
                            }
                            workingStops.remove(i);
                        }
                        changed = true;
                        break;
                    }
                    if (isSoftActivityStop(stop)) {
                        if (wouldDropBelowMinNonMealStops(workingStops, stop, minNonMealStops)) {
                            continue;
                        }
                        if (i > 0) {
                            Place previous = workingStops.get(i - 1);
                            workingStops.set(i - 1, mergeSoftActivityIntoPrevious(previous, stop));
                        }
                        workingStops.remove(i);
                        changed = true;
                        break;
                    }
                    if (shouldCreateCulturalClosingProblem(stop)) {
                        int removableIndex = removableFlexibleIndexBefore(workingStops, i);
                        if (removableIndex >= 0) {
                            Place removable = workingStops.get(removableIndex);
                            if (wouldDropBelowMinNonMealStops(workingStops, removable, minNonMealStops)) {
                                continue;
                            }
                            workingStops.remove(removableIndex);
                            changed = true;
                            break;
                        }
                    }
                    if (!isFlexibleStop(stop)) {
                        continue;
                    }
                    if (wouldDropBelowMinNonMealStops(workingStops, stop, minNonMealStops)) {
                        continue;
                    }
                    if (shouldDropFlexibleStop(workingStops, i)) {
                        workingStops.remove(i);
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    workingStops = new ArrayList<>(normalizeDaySchedule(new DayPlan(
                            day.dayIndex(),
                            day.hotel(),
                            workingStops,
                            day.theme(),
                            day.morningNote(),
                            day.afternoonNote(),
                            day.eveningNote(),
                            day.note()
                    )).stops());
                }
            } while (changed);

            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    workingStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean isNonMealOccupyingMealSlot(Place stop) {
        if (stop == null) {
            return false;
        }
        String timeSlot = normalizeSlot(stop.timeSlot());
        if (!"lunch".equals(timeSlot) && !"dinner".equals(timeSlot)) {
            return false;
        }
        return !isMealCategory(normalizeSlot(stop.category()));
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "bar".equals(category)
                || "bakery".equals(category);
    }

    private Place clearMealSlotForNonMealStop(Place stop) {
        String fallbackSlot = "dinner".equals(normalizeSlot(stop.timeSlot())) ? "evening" : "afternoon";
        return new Place(
                stop.name(),
                stop.addressLine(),
                stop.suburb(),
                stop.city(),
                stop.state(),
                stop.postcode(),
                stop.country(),
                stop.category(),
                stop.stayMinutes(),
                fallbackSlot,
                stop.startTime(),
                stop.endTime(),
                null,
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
                stop.latitude(),
                stop.longitude()
        );
    }

    private boolean isCompoundAttractionStop(Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().trim();
        if (!name.matches("(?i).+\\s(&|and|\\+|/|\\|)\\s.+")) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if (!("attraction".equals(category) || "park".equals(category) || "nature".equals(category) || "museum".equals(category))) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("lookout")
                || lower.contains("planetarium")
                || lower.contains("garden")
                || lower.contains("museum")
                || lower.contains("gallery")
                || lower.contains("park")
                || lower.contains("market")
                || lower.contains("beach")
                || lower.contains("zoo");
    }

    private Place simplifyCompoundStop(Place stop) {
        if (stop == null) {
            return null;
        }
        String name = stop.name() == null ? "" : stop.name().trim();
        if (name.isBlank()) {
            return null;
        }
        String[] parts = name.split("(?i)\\s+(?:&|and|\\+|/|\\|)\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String first = parts[0].trim();
        String second = parts[1].trim();
        String chosen = chooseCompoundStopName(first, second);
        if (chosen == null || chosen.isBlank()) {
            return null;
        }
        String tip = stop.tip() == null || stop.tip().isBlank()
                ? "Use this as a single navigable stop rather than combining multiple nearby attractions."
                : stop.tip().trim() + " Keep this as a single navigable stop rather than combining multiple nearby attractions.";
        return new Place(
                chosen,
                stop.addressLine(),
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
                tip,
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                stop.latitude(),
                stop.longitude()
        );
    }

    private String chooseCompoundStopName(String first, String second) {
        String firstLower = first == null ? "" : first.toLowerCase();
        String secondLower = second == null ? "" : second.toLowerCase();
        if (firstLower.contains("lookout")) {
            return first;
        }
        if (secondLower.contains("lookout")) {
            return second;
        }
        if (firstLower.contains("museum") || firstLower.contains("gallery") || firstLower.contains("planetarium")) {
            return first;
        }
        if (secondLower.contains("museum") || secondLower.contains("gallery") || secondLower.contains("planetarium")) {
            return second;
        }
        if (firstLower.contains("garden") || firstLower.contains("park")) {
            return first;
        }
        if (secondLower.contains("garden") || secondLower.contains("park")) {
            return second;
        }
        return first == null || first.isBlank() ? second : first;
    }

    private boolean isSoftActivityStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if (!("attraction".equals(category) || "park".equals(category) || "nature".equals(category))) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
        boolean activityName = name.contains("stroll")
                || name.contains("rainforest walk")
                || name.contains("arbour")
                || name.contains("arbor")
                || (name.contains("lookout") && name.contains("botanic"))
                || name.contains("coastal walk")
                || name.contains("sunset walk")
                || name.contains("scenic walk")
                || name.contains("riverwalk")
                || name.contains(" walk ")
                || name.contains("promenade")
                || name.contains("walking path")
                || name.contains("riverside walk");
        if (!activityName) {
            return false;
        }
        boolean hasVerifiedPlace = stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank();
        boolean hasCoordinates = stop.latitude() != null && stop.longitude() != null;
        return !hasVerifiedPlace || !hasCoordinates;
    }

    private Place mergeSoftActivityIntoPrevious(Place previous, Place softActivity) {
        String reason = (previous.reason() == null || previous.reason().isBlank())
                ? softActivity.reason()
                : previous.reason();
        return new Place(
                previous.name(),
                previous.addressLine(),
                previous.suburb(),
                previous.city(),
                previous.state(),
                previous.postcode(),
                previous.country(),
                previous.category(),
                previous.stayMinutes(),
                previous.timeSlot(),
                previous.startTime(),
                previous.endTime(),
                previous.mealType(),
                previous.preferredArea(),
                previous.cuisine(),
                previous.vibe(),
                previous.budgetLevel(),
                reason,
                previous.tip(),
                previous.websiteUri(),
                previous.googleMapsUri(),
                previous.businessStatus(),
                previous.url(),
                previous.latitude(),
                previous.longitude()
        );
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

    private boolean shouldDropFlexibleStop(List<Place> stops, int index) {
        Place stop = stops.get(index);
        if (isParkLikeStop(stop)) {
            return shouldDropParkLikeStop(stops, index);
        }
        if (isNonStrictFoodStop(stop)) {
            return shouldDropFlexibleFoodStop(stops, index);
        }
        return false;
    }

    private boolean isParkLikeStop(Place stop) {
        if (stop == null) return false;
        String category = normalizeSlot(stop.category());
        return "park".equals(category) || "nature".equals(category);
    }

    private boolean shouldDropParkLikeStop(List<Place> stops, int index) {
        Place stop = stops.get(index);
        if (stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank()) {
            return false;
        }
        if (index > 0 && isParkLikeStop(stops.get(index - 1))) {
            return true;
        }
        if (index < stops.size() - 1 && isParkLikeStop(stops.get(index + 1))) {
            return true;
        }
        return false;
    }

    private boolean isNonStrictFoodStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "cafe".equals(category) || "bakery".equals(category) || "bar".equals(category) || "food".equals(category);
    }

    private boolean shouldDropFlexibleFoodStop(List<Place> stops, int index) {
        Place stop = stops.get(index);
        if (stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank()) {
            return false;
        }
        String timeSlot = normalizeSlot(stop.timeSlot());
        if ("lunch".equals(timeSlot) || "dinner".equals(timeSlot)) {
            return false;
        }
        int mealCount = 0;
        for (Place s : stops) {
            if (isStrictMealStop(s)) mealCount++;
        }
        return mealCount >= 2;
    }
    public PlanDraftResponse pruneUnselectedShoppingStops(PlanDraftResponse draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || isMarketShoppingSelected(req)) {
            return draft;
        }

        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> keptStops = new ArrayList<>();
            for (Place stop : stops) {
                if (shouldDropUnselectedShoppingStop(stop)) {
                    changed = true;
                    continue;
                }
                keptStops.add(stop);
            }
            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    keptStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }

        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean shouldDropUnselectedShoppingStop(Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if ("shop".equals(category) || "shopping".equals(category) || "market".equals(category)) {
            return true;
        }
        String primaryText = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase();
        return primaryText.contains("shopping")
                || primaryText.contains("retail")
                || primaryText.contains(" mall")
                || primaryText.contains("arcade")
                || primaryText.contains("outlet")
                || primaryText.contains("marketplace")
                || primaryText.contains("shopping centre")
                || primaryText.contains("shopping center");
    }

    private boolean isMarketShoppingSelected(CreatePlanReq req) {
        if (req == null || req.style() == null) {
            return false;
        }
        return req.style().stream()
                .map(this::normalizeSlot)
                .anyMatch("market_shopping"::equals);
    }

    private boolean isSelectedMarketShoppingAnchor(Place stop, CreatePlanReq req) {
        if (!isMarketShoppingSelected(req) || stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        return isMarketShoppingLikeStop(stop);
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
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        if (!googlePlacesClient.isEnabled()) {
            return draft;
        }

        boolean changed = false;
        int themeParkStopCount = 0;
        int replacedCount = 0;
        List<DayPlan> updatedDays = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> updatedStops = new ArrayList<>();
            for (Place stop : stops) {
                if (!isThemeParkLikeStop(stop)) {
                    updatedStops.add(stop);
                    continue;
                }
                themeParkStopCount++;
                GooglePlacesClient.PlaceCandidate candidate = resolveThemeParkWithPlaces(stop);
                if (candidate == null) {
                    updatedStops.add(stop);
                    continue;
                }
                updatedStops.add(copyThemeParkWithCandidate(stop, candidate));
                replacedCount++;
                changed = true;
            }
            updatedDays.add(new DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        if (themeParkStopCount > 0) {
            log.info("Theme park Places verification completed: city={} stops={} replaced={}", draft.city(), themeParkStopCount, replacedCount);
        }
        if (!changed) {
            return draft;
        }
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), updatedDays, draft.copyPolishStatus());
    }

    private GooglePlacesClient.PlaceCandidate resolveThemeParkWithPlaces(Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank() || !googlePlacesClient.isEnabled()) {
            return null;
        }
        for (String query : themeParkPlaceSearchQueries(stop)) {
            GooglePlacesClient.PlaceCandidate candidate = googlePlacesClient.searchText(query, stop.city()).stream()
                    .filter(place -> Double.isFinite(place.lat()) && Double.isFinite(place.lng()))
                    .filter(place -> placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(place.lat(), place.lng()), stop.city()))
                    .map(place -> new RankedPlaceCoordinate(place, scoreThemeParkCandidate(stop, place)))
                    .filter(ranked -> isAcceptableThemeParkCandidate(ranked))
                    .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                    .map(RankedPlaceCoordinate::candidate)
                    .findFirst()
                    .orElse(null);
            if (candidate != null) {
                return candidate;
            }
        }
        for (String query : genericThemeParkPlaceSearchQueries(stop)) {
            GooglePlacesClient.PlaceCandidate candidate = googlePlacesClient.searchText(query, stop.city()).stream()
                    .filter(place -> Double.isFinite(place.lat()) && Double.isFinite(place.lng()))
                    .filter(place -> placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(place.lat(), place.lng()), stop.city()))
                    .map(place -> new RankedPlaceCoordinate(place, scoreGenericThemeParkCandidate(place)))
                    .filter(this::isAcceptableGenericThemeParkCandidate)
                    .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                    .map(RankedPlaceCoordinate::candidate)
                    .findFirst()
                    .orElse(null);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isAcceptableThemeParkCandidate(RankedPlaceCoordinate ranked) {
        if (ranked == null || ranked.candidate() == null || ranked.score() < 140) {
            return false;
        }
        if (!hasStrictThemeParkPlaceType(ranked.candidate())) {
            return false;
        }
        String status = ranked.candidate().businessStatus() == null ? "" : ranked.candidate().businessStatus().toUpperCase(Locale.ROOT);
        return !status.contains("CLOSED");
    }

    private boolean isAcceptableGenericThemeParkCandidate(RankedPlaceCoordinate ranked) {
        if (ranked == null || ranked.candidate() == null || ranked.score() < 180) {
            return false;
        }
        if (!hasStrictThemeParkPlaceType(ranked.candidate())) {
            return false;
        }
        String status = ranked.candidate().businessStatus() == null ? "" : ranked.candidate().businessStatus().toUpperCase(Locale.ROOT);
        return !status.contains("CLOSED");
    }

    private boolean hasStrictThemeParkPlaceType(GooglePlacesClient.PlaceCandidate candidate) {
        if (candidate == null || candidate.types() == null) {
            return false;
        }
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return types.contains("amusement_park")
                || types.contains("theme_park")
                || types.contains("water_park");
    }

    private int scoreThemeParkCandidate(Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String expectedName = placeHeuristicService.normalizeSearchText(stop.name());
        String expectedAddress = placeHeuristicService.normalizeSearchText(stop.addressLine());
        String candidateName = placeHeuristicService.normalizeSearchText(candidate.name());
        String candidateText = placeHeuristicService.normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        int score = placeHeuristicService.commonSignificantTokenCount(expectedName, candidateText) * 80;
        score += placeHeuristicService.commonSignificantTokenCount(expectedAddress, candidateText) * 20;
        if (!expectedName.isBlank() && !candidateName.isBlank() && (candidateName.contains(expectedName) || expectedName.contains(candidateName))) {
            score += 120;
        }
        if (types.contains("amusement_park") || types.contains("theme_park") || types.contains("water_park")) {
            score += 160;
        }
        if (types.contains("tourist_attraction")) {
            score += 60;
        }
        if (types.contains("point_of_interest") || types.contains("establishment")) {
            score += 20;
        }
        if (types.contains("restaurant") || types.contains("lodging") || types.contains("shopping_mall")) {
            score -= 120;
        }
        return score;
    }

    private int scoreGenericThemeParkCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String candidateText = placeHeuristicService.normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (types.contains("amusement_park") || types.contains("theme_park") || types.contains("water_park")) {
            score += 220;
        }
        if (types.contains("tourist_attraction")) {
            score += 50;
        }
        if (candidateText.contains("theme park")
                || candidateText.contains("amusement park")
                || candidateText.contains("water park")
                || candidateText.contains("luna park")
                || candidateText.contains("raging waters")) {
            score += 80;
        }
        if (types.contains("restaurant") || types.contains("lodging") || types.contains("shopping_mall")) {
            score -= 160;
        }
        return score;
    }

    private Place copyThemeParkWithCandidate(Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String addressLine = candidate.formattedAddress() == null || candidate.formattedAddress().isBlank()
                ? stop.addressLine()
                : candidate.formattedAddress();
        String name = candidate.name() == null || candidate.name().isBlank()
                ? stop.name()
                : candidate.name();
        String url = candidate.googleMapsUri() == null || candidate.googleMapsUri().isBlank()
                ? stop.url()
                : candidate.googleMapsUri();
        ParsedAddress parsedAddress = parseAustralianAddress(candidate.formattedAddress(), stop);
        String suburb = parsedAddress.suburb().isBlank() ? stop.suburb() : parsedAddress.suburb();
        String state = parsedAddress.state().isBlank() ? stop.state() : parsedAddress.state();
        String postcode = parsedAddress.postcode().isBlank() ? stop.postcode() : parsedAddress.postcode();
        String country = parsedAddress.country().isBlank() ? stop.country() : parsedAddress.country();
        String themeParkAddressLine = parsedAddress.addressLine().isBlank() ? addressLine : parsedAddress.addressLine();
        String preferredArea = suburb == null || suburb.isBlank() ? stop.preferredArea() : suburb;
        return new Place(
                name,
                themeParkAddressLine,
                suburb,
                stop.city(),
                state,
                postcode,
                country,
                "theme_park",
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                preferredArea,
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                sanitizeThemeParkReason(stop),
                sanitizeThemeParkTip(),
                candidate.websiteUri(),
                candidate.googleMapsUri(),
                candidate.businessStatus(),
                url,
                Double.isNaN(candidate.lat()) ? stop.latitude() : candidate.lat(),
                Double.isNaN(candidate.lng()) ? stop.longitude() : candidate.lng()
        );
    }

    private List<String> themeParkPlaceSearchQueries(Place stop) {
        List<String> queries = new ArrayList<>();
        if (stop == null) {
            return queries;
        }
        String name = stop.name() == null ? "" : stop.name().trim();
        String coreName = themeParkCorePoiName(name);
        String address = stop.addressLine() == null ? "" : stop.addressLine().trim();
        String suburb = stop.suburb() == null ? "" : stop.suburb().trim();
        String city = stop.city() == null ? "" : stop.city().trim();
        if (!coreName.isBlank() && !address.isBlank()) {
            addUnique(queries, coreName + ", " + address);
        }
        if (!name.isBlank() && !address.isBlank() && !name.equalsIgnoreCase(coreName)) {
            addUnique(queries, name + ", " + address);
        }
        if (!coreName.isBlank() && !suburb.isBlank()) {
            addUnique(queries, coreName + ", " + suburb);
        }
        if (!name.isBlank() && !suburb.isBlank()) {
            addUnique(queries, name + ", " + suburb);
        }
        if (!coreName.isBlank() && !city.isBlank()) {
            addUnique(queries, coreName + ", " + city);
        }
        if (!name.isBlank() && !city.isBlank()) {
            addUnique(queries, name + ", " + city);
        }
        if (!coreName.isBlank()) {
            addUnique(queries, coreName);
        }
        if (!name.isBlank()) {
            addUnique(queries, name);
        }
        return queries;
    }

    private List<String> genericThemeParkPlaceSearchQueries(Place stop) {
        List<String> queries = new ArrayList<>();
        String city = stop == null || stop.city() == null ? "" : stop.city().trim();
        if (!city.isBlank()) {
            addUnique(queries, "theme park");
            addUnique(queries, "amusement park");
            addUnique(queries, "water park");
            addUnique(queries, "family amusement park");
        }
        return queries;
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
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = value
                .replaceAll("(?i)\\bshuttles\\b", "transport options")
                .replaceAll("(?i)\\bshuttle\\b", "transport")
                .replaceAll("(?i)\\btimed entry\\b", "entry requirements")
                .replaceAll("(?i)\\btimed access\\b", "entry requirements")
                .replaceAll("(?i)\\bride access\\b", "attraction access")
                .replaceAll("(?i)\\bride schedules\\b", "current operating details")
                .replaceAll("(?i)\\bride schedule\\b", "current operating details")
                .replaceAll("(?i)\\bshow schedules\\b", "current operating details")
                .replaceAll("(?i)\\bshow schedule\\b", "current operating details")
                .replaceAll("(?i)\\bshows\\b", "activities")
                .replaceAll("(?i)\\bshow\\b", "activity")
                .trim();
        String sentenceSafe = removeRiskyNarrativeSentences(sanitized);
        return sentenceSafe == null || sentenceSafe.isBlank() ? sanitized : sentenceSafe;
    }

    private String themeParkCorePoiName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim();
        String[] parts = cleaned.split("\\s+(?:-|\\||:)\\s*");
        return parts.length == 0 ? cleaned : parts[0].trim();
    }

    private PlanDraftResponse pruneOutOfRangeThemeParkStops(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        StopCoordinate cityCenter = cityCenterCoordinate(draft.city());
        if (cityCenter == null) {
            return draft;
        }

        boolean changed = false;
        List<DayPlan> updatedDays = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> keptStops = new ArrayList<>();
            for (Place stop : stops) {
                if (isOutOfRangeThemeParkStop(cityCenter, stop)) {
                    log.info("Pruned out-of-range theme park stop city={} day={} stop={}",
                            draft.city(), day.dayIndex(), stop.name());
                    changed = true;
                    continue;
                }
                keptStops.add(stop);
            }
            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    keptStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }
        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean isOutOfRangeThemeParkStop(StopCoordinate cityCenter, Place stop) {
        if (!isThemeParkLikeStop(stop)) {
            return false;
        }
        StopCoordinate stopCoordinate = coordinateOf(stop);
        if (cityCenter == null || stopCoordinate == null) {
            return false;
        }
        double distanceMeters = haversineMeters(cityCenter.lat(), cityCenter.lon(), stopCoordinate.lat(), stopCoordinate.lon());
        return distanceMeters > THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS;
    }

    private PlanDraftResponse pruneThemeParkDayTrips(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            Place themePark = stops.stream()
                    .filter(this::isThemeParkLikeStop)
                    .findFirst()
                    .orElse(null);
            if (themePark == null) {
                updatedDays.add(day);
                continue;
            }

            int keptSameClusterExtra = 0;
            List<Place> keptStops = new ArrayList<>();
            for (Place stop : stops) {
                if (stop == themePark) {
                    keptStops.add(stop);
                    continue;
                }
                if (hasMealSlot(stop, "lunch")) {
                    if (isThemeParkClusterMeal(themePark, stop)) {
                        keptStops.add(stop);
                        continue;
                    }
                    changed = true;
                    continue;
                }
                if (isStrictMealStop(stop)) {
                    keptStops.add(stop);
                    continue;
                }
                if (stops.indexOf(stop) < stops.indexOf(themePark)) {
                    changed = true;
                    continue;
                }
                if (isSameThemeParkCluster(themePark, stop) && keptSameClusterExtra < 1) {
                    keptStops.add(stop);
                    keptSameClusterExtra++;
                    continue;
                }
                changed = true;
            }
            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    keptStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }
        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    public PlanDraftResponse expandThemeParkDiningBreaks(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            Place themePark = stops.stream()
                    .filter(this::isThemeParkLikeStop)
                    .findFirst()
                    .orElse(null);
            if (themePark == null || !isThemeParkSplitEligible(draft.pace())) {
                updatedDays.add(day);
                continue;
            }
            int lunchIndex = firstMealIndex(stops, "lunch");
            int themeParkIndex = stops.indexOf(themePark);
            if (lunchIndex <= themeParkIndex || lunchIndex < 0 || hasThemeParkAfterIndex(stops, lunchIndex)) {
                updatedDays.add(day);
                continue;
            }
            List<Place> expandedStops = new ArrayList<>(stops);
            expandedStops.set(themeParkIndex, morningThemeParkStop(themePark));
            lunchIndex = firstMealIndex(expandedStops, "lunch");
            expandedStops.set(lunchIndex, themeParkLunchStop(themePark));
            expandedStops.add(lunchIndex + 1, themeParkContinuationStop(themePark));
            changed = true;
            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    expandedStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }
        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean isThemeParkLikeStop(Place stop) {
        if (stop == null) return false;
        String cat = normalizeSlot(stop.category());
        if ("theme_park".equals(cat)) return true;
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
        return name.contains("disneyland") || name.contains("disney world") || name.contains("universal studios") || name.contains("theme park") || name.contains("water park");
    }

    private boolean isThemeParkClusterMeal(Place themePark, Place stop) {
        if (themePark == null || stop == null || !hasMealSlot(stop, "lunch")) {
            return false;
        }
        String stopName = nullToEmpty(stop.name()).toLowerCase(Locale.ROOT);
        String themeParkName = nullToEmpty(themePark.name()).toLowerCase(Locale.ROOT);
        if (!themeParkName.isBlank() && stopName.contains(themeParkName)) {
            return true;
        }
        String stopMapsUri = nullToEmpty(stop.googleMapsUri());
        String themeMapsUri = nullToEmpty(themePark.googleMapsUri());
        if (!stopMapsUri.isBlank() && stopMapsUri.equals(themeMapsUri)) {
            return true;
        }
        return isSameThemeParkCluster(themePark, stop);
    }

    private boolean isSameThemeParkCluster(Place themePark, Place stop) {
        if (themePark == null || stop == null) {
            return false;
        }
        if (themePark.latitude() != null && themePark.longitude() != null && stop.latitude() != null && stop.longitude() != null) {
            double distanceMeters = haversineMeters(
                    themePark.latitude(),
                    themePark.longitude(),
                    stop.latitude(),
                    stop.longitude()
            );
            return distanceMeters <= 2000;
        }
        String tpArea = themeParkAnchorArea(themePark);
        String stArea = themeParkAnchorArea(stop);
        return tpArea != null && tpArea.equals(stArea);
    }

    private String themeParkAnchorArea(Place themePark) {
        if (themePark == null) return null;
        String area = normalizeSlot(themePark.preferredArea());
        if (area.isEmpty()) area = normalizeSlot(themePark.suburb());
        return area.isEmpty() ? null : area;
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

    private boolean isThemeParkSplitEligible(String pace) {
        String p = normalizePaceLabel(pace);
        return "relaxed".equals(p) || "normal".equals(p);
    }

    private int firstMealIndex(List<Place> stops, String mealType) {
        for (int i = 0; i < stops.size(); i++) {
            if (hasMealSlot(stops.get(i), mealType)) return i;
        }
        return -1;
    }

    private boolean hasThemeParkAfterIndex(List<Place> stops, int index) {
        for (int i = index + 1; i < stops.size(); i++) {
            if (isThemeParkLikeStop(stops.get(i))) return true;
        }
        return false;
    }

    private Place morningThemeParkStop(Place themePark) {
        return new Place(
                themePark.name() + " (Morning)",
                themePark.addressLine(),
                themePark.suburb(),
                themePark.city(),
                themePark.state(),
                themePark.postcode(),
                themePark.country(),
                themePark.category(),
                180,
                "morning",
                themePark.startTime(),
                "12:00",
                null,
                themePark.preferredArea(),
                null,
                null,
                null,
                themePark.reason(),
                themePark.tip(),
                themePark.websiteUri(),
                themePark.googleMapsUri(),
                themePark.businessStatus(),
                themePark.url(),
                themePark.latitude(),
                themePark.longitude()
        );
    }

    private Place themeParkLunchStop(Place themePark) {
        String parkName = nullToEmpty(themePark.name()).isBlank() ? "Theme Park" : themePark.name();
        return new Place(
                parkName + " Internal Dining Break",
                themePark.addressLine(),
                themePark.suburb(),
                themePark.city(),
                themePark.state(),
                themePark.postcode(),
                themePark.country(),
                "dining",
                60,
                "lunch",
                "12:00",
                "13:00",
                "lunch",
                themePark.preferredArea(),
                "theme park dining",
                "casual",
                "midrange",
                "This is a controlled in-park dining break, not a separate restaurant recommendation.",
                "Choose an available in-park or immediately adjacent option on the day.",
                null,
                null,
                null,
                null,
                themePark.latitude(),
                themePark.longitude()
        );
    }

    private Place themeParkContinuationStop(Place themePark) {
        return new Place(
                themePark.name() + " (Afternoon)",
                themePark.addressLine(),
                themePark.suburb(),
                themePark.city(),
                themePark.state(),
                themePark.postcode(),
                themePark.country(),
                themePark.category(),
                THEME_PARK_AFTERNOON_CONTINUATION_MINUTES,
                "afternoon",
                "13:00",
                formatMinutes(13 * 60 + THEME_PARK_AFTERNOON_CONTINUATION_MINUTES),
                null,
                themePark.preferredArea(),
                null,
                null,
                null,
                "Continue exploring the park at a flexible pace.",
                sanitizeThemeParkCopy(themePark.tip()),
                themePark.websiteUri(),
                themePark.googleMapsUri(),
                themePark.businessStatus(),
                themePark.url(),
                themePark.latitude(),
                themePark.longitude()
        );
    }

    private PlanDraftResponse pruneAreaInconsistentFlexibleStops(PlanDraftResponse draft) {
        return pruneAreaInconsistentFlexibleStops(draft, null);
    }

    private PlanDraftResponse pruneAreaInconsistentFlexibleStops(PlanDraftResponse draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }

        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> workingStops = new ArrayList<>(stops);
            boolean dayChanged;
            do {
                dayChanged = false;
                for (int i = 0; i < workingStops.size(); i++) {
                    if (isSelectedMarketShoppingAnchor(workingStops.get(i), req)) {
                        continue;
                    }
                    if (countNonMealStops(workingStops) <= minNonMealStops
                            && isCountedNonMealStop(workingStops.get(i))
                            && !isThemeParkLikeStop(workingStops.get(i))) {
                        continue;
                    }
                    if (shouldDropAreaInconsistentFlexibleStop(workingStops, i)) {
                        workingStops.remove(i);
                        dayChanged = true;
                        changed = true;
                        break;
                    }
                }
            } while (dayChanged);

            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    workingStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }

        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean shouldDropAreaInconsistentFlexibleStop(List<Place> stops, int index) {
        if (index <= 0 || index >= stops.size() - 1) {
            return false;
        }
        Place stop = stops.get(index);
        if (isThemeParkLikeStop(stop)) {
            return false;
        }
        if (shouldDropThemeParkNearbyFlexibleStop(stops, index)) {
            return true;
        }
        if (isAreaReturnFlexibleStop(stops, index) && isAreaReturnDroppableStop(stop)) {
            return true;
        }
        if (!isFlexibleStop(stop)) {
            return false;
        }
        Place previous = stops.get(index - 1);
        Place next = stops.get(index + 1);
        if (isStrictMealStop(previous) && isStrictMealStop(next)) {
            return false;
        }
        if (previous.latitude() == null || stop.latitude() == null || next.latitude() == null) {
            return false;
        }
        double prevToCurrent = haversineMeters(previous.latitude(), previous.longitude(), stop.latitude(), stop.longitude());
        double currentToNext = haversineMeters(stop.latitude(), stop.longitude(), next.latitude(), next.longitude());
        double prevToNext = haversineMeters(previous.latitude(), previous.longitude(), next.latitude(), next.longitude());
        double detourMeters = prevToCurrent + currentToNext - prevToNext;
        return detourMeters > 12_000 && Math.max(prevToCurrent, currentToNext) > 18_000;
    }

    private boolean shouldDropThemeParkNearbyFlexibleStop(List<Place> stops, int index) {
        if (stops == null || index <= 0 || index >= stops.size() - 1) {
            return false;
        }
        Place stop = stops.get(index);
        if (!isThemeParkDayConnectorCandidate(stop)) {
            return false;
        }
        Place themeParkAnchor = previousThemeParkAnchor(stops, index);
        if (themeParkAnchor == null) {
            return false;
        }
        Place next = stops.get(index + 1);
        if (!isStrictMealStop(next)) {
            return false;
        }
        if (themeParkAnchor.latitude() == null || stop.latitude() == null || next.latitude() == null) {
            return false;
        }

        double anchorToCurrent = haversineMeters(themeParkAnchor.latitude(), themeParkAnchor.longitude(), stop.latitude(), stop.longitude());
        double currentToNext = haversineMeters(stop.latitude(), stop.longitude(), next.latitude(), next.longitude());
        double anchorToNext = haversineMeters(themeParkAnchor.latitude(), themeParkAnchor.longitude(), next.latitude(), next.longitude());
        double detourMeters = anchorToCurrent + currentToNext - anchorToNext;
        boolean closeToThemeParkOrDinner = anchorToCurrent <= 1_500 || currentToNext <= 1_500;
        if (!closeToThemeParkOrDinner && detourMeters > 1_500) {
            return true;
        }
        return anchorToNext <= 1_500 && detourMeters > 1_500;
    }

    private boolean isThemeParkDayConnectorCandidate(Place stop) {
        if (stop == null || isStrictMealStop(stop) || isThemeParkLikeStop(stop)) {
            return false;
        }
        String slot = normalizeSlot(stop.timeSlot());
        return "sunset".equals(slot)
                || isParkLikeStop(stop)
                || isFlexibleStop(stop)
                || isSoftActivityStop(stop);
    }

    private Place previousThemeParkAnchor(List<Place> stops, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Place candidate = stops.get(i);
            if (isThemeParkLikeStop(candidate)) {
                return candidate;
            }
            if (isStrictMealStop(candidate) && !"lunch".equals(normalizeSlot(candidate.mealType()))
                    && !"lunch".equals(normalizeSlot(candidate.timeSlot()))) {
                return null;
            }
        }
        return null;
    }

    private boolean isAreaReturnFlexibleStop(List<Place> stops, int index) {
        if (index <= 0 || index >= stops.size() - 1) return false;
        Place stop = stops.get(index);
        Place previous = stops.get(index - 1);
        Place next = stops.get(index + 1);
        if (isStrictMealStop(stop) || isStrictMealStop(previous)) {
            return false;
        }
        String currentArea = normalizedAreaLabel(stop);
        String previousArea = normalizedAreaLabel(previous);
        String nextArea = normalizedAreaLabel(next);
        if (currentArea.isBlank() || previousArea.isBlank() || nextArea.isBlank()) {
            return false;
        }
        if (areasEquivalent(currentArea, previousArea) || !areasEquivalent(currentArea, nextArea)) {
            return false;
        }
        for (int i = 0; i < index - 1; i++) {
            Place earlier = stops.get(i);
            if (!isStrictMealStop(earlier) && areasEquivalent(currentArea, normalizedAreaLabel(earlier))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAreaReturnDroppableStop(Place stop) {
        if (stop == null) return false;
        String category = normalizeSlot(stop.category());
        return "park".equals(category) || "nature".equals(category) || "shop".equals(category) || "market".equals(category) || "attraction".equals(category);
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

    private PlanDraftResponse pruneExcessNonMealStops(PlanDraftResponse draft) {
        return pruneExcessNonMealStops(draft, null);
    }

    private PlanDraftResponse pruneExcessNonMealStops(PlanDraftResponse draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int maxNonMealStops = maxNonMealStopsPerDay(draft.pace());
        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            List<Place> workingStops = new ArrayList<>(stops);
            while (countNonMealStops(workingStops) > maxNonMealStops) {
                int dropIndex = lowestValueNonMealStopIndex(workingStops, req);
                if (dropIndex < 0) {
                    break;
                }
                workingStops.remove(dropIndex);
                changed = true;
            }
            updatedDays.add(new DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    workingStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }
        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private int maxNonMealStopsPerDay(String pace) {
        return paceNonMealRange(pace).max();
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

    private int lowestValueNonMealStopIndex(List<Place> stops) {
        return lowestValueNonMealStopIndex(stops, null);
    }

    private int lowestValueNonMealStopIndex(List<Place> stops, CreatePlanReq req) {
        int bestIndex = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            Place stop = stops.get(i);
            if (!isCountedNonMealStop(stop) || isThemeParkLikeStop(stop) || isSelectedMarketShoppingAnchor(stop, req)) {
                continue;
            }
            int score = nonMealStopValueScore(stops, i);
            if (score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private boolean isCountedNonMealStop(Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        return !isMealCategory(normalizeSlot(stop.category()));
    }

    private int nonMealStopValueScore(List<Place> stops, int index) {
        Place stop = stops.get(index);
        Place previousStop = index > 0 ? stops.get(index - 1) : null;
        int score = 50 + attractionStrength(stop, previousStop) * 10;
        String category = normalizeSlot(stop.category());
        int stayMinutes = resolveStayMinutes(stop);
        if (isStrongPoiCandidate(stop)) score += 40;
        if (isParkLikeStop(stop)) score += 20;
        if ("museum".equals(category)) score += 30;
        if ("attraction".equals(category)) score += 5;
        if (stayMinutes <= 30) score -= 25;
        if (stayMinutes <= 45) score -= 10;
        if ("sunset".equals(normalizeSlot(stop.timeSlot()))) score -= 10;
        if (isLightweightStop(stop, previousStop)) score -= 30;
        if (index == 0) score += 8;
        String reason = nullToEmpty(stop.reason()).toLowerCase();
        if (reason.contains("punctuation mark") || reason.contains("brief stop")) {
            score -= 20;
        }
        return score;
    }

    private int attractionStrength(Place stop, Place previousStop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return 10;
        }
        String category = normalizeSlot(stop.category());
        int score = switch (category) {
            case "museum", "gallery", "theme_park" -> 5;
            case "park", "nature" -> 4;
            case "shop", "market" -> 2;
            case "attraction" -> 2;
            default -> 2;
        };

        int stayMinutes = resolveStayMinutes(stop);
        if (stayMinutes <= 30) {
            score -= 2;
        } else if (stayMinutes <= 50) {
            score -= 1;
        } else if (stayMinutes >= 75) {
            score += 1;
        }

        String name = nullToEmpty(stop.name()).toLowerCase();
        String text = name + " " + nullToEmpty(stop.addressLine()).toLowerCase();
        if (hasLightweightKeyword(text)) {
            score -= 2;
        }
        if (isAttachedToPreviousStop(stop, previousStop)) {
            score -= 2;
        }
        if (isStrongPoiCandidate(stop)) {
            score += 2;
        }
        if ("museum".equals(category) && stayMinutes >= 60) {
            score += 2;
        }
        return score;
    }

    private boolean hasLightweightKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("rooftop")
                || text.contains("terrace")
                || text.contains("forecourt")
                || text.contains("plaza")
                || text.contains("mall")
                || text.contains(" riverwalk")
                || text.contains("walk ")
                || text.contains("promenade")
                || text.contains("lagoon")
                || text.contains("square")
                || text.contains("exterior");
    }

    private boolean isAttachedToPreviousStop(Place stop, Place previousStop) {
        if (stop == null || previousStop == null) {
            return false;
        }
        String currentName = nullToEmpty(stop.name()).toLowerCase();
        String previousName = nullToEmpty(previousStop.name()).toLowerCase();
        if (currentName.isBlank() || previousName.isBlank()) {
            return false;
        }
        boolean nameLooksAttached = currentName.contains(previousName)
                || previousName.contains(currentName)
                || sharesMeaningfulToken(currentName, previousName);
        boolean sameArea = !nullToEmpty(stop.addressLine()).isBlank()
                && !nullToEmpty(previousStop.addressLine()).isBlank()
                && normalizedAddressAnchor(stop.addressLine()).equals(normalizedAddressAnchor(previousStop.addressLine()));
        return nameLooksAttached && sameArea;
    }

    private boolean sharesMeaningfulToken(String left, String right) {
        for (String token : left.split("[^a-z0-9]+")) {
            if (token.length() >= 4 && right.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalizedAddressAnchor(String value) {
        if (value == null) return "";
        String[] parts = value.split(",");
        if (parts.length == 0) return "";
        String first = parts[0].trim().toLowerCase();
        return first.replaceAll("^\\d+\\s+", "");
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

    private boolean isLightweightStop(Place stop, Place previousStop) {
        return attractionStrength(stop, previousStop) <= 2;
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
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
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

    private List<Integer> resolveTransferMinutesInParallel(
            List<Place> stops,
            List<StopCoordinate> coordinates,
            RouteRecommendationContext recommendationContext
    ) {
        if (stops == null || stops.isEmpty()) {
            return List.of();
        }
        List<Integer> transfers = new ArrayList<>(Collections.nCopies(stops.size(), 0));
        if (stops.size() == 1) {
            return transfers;
        }
        Map<RouteChoiceCacheKey, RouteChoice> routeChoiceCache = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, stops.size() - 1));
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 1; i < stops.size(); i++) {
                final int index = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> routeAwareTransitionMinutes(stops, coordinates, index, recommendationContext, routeChoiceCache),
                        executor
                ));
            }
            for (int i = 1; i < stops.size(); i++) {
                transfers.set(i, joinInteger(futures.get(i - 1), transitionMinutes(false)));
            }
            return transfers;
        } finally {
            executor.shutdownNow();
        }
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

    private int routeAwareTransitionMinutes(
            List<Place> stops,
            List<StopCoordinate> coordinates,
            int index,
            RouteRecommendationContext recommendationContext,
            Map<RouteChoiceCacheKey, RouteChoice> routeChoiceCache
    ) {
        if (index == 0) {
            return 0;
        }
        int fallback = transitionMinutes(false);
        if (stops == null || coordinates == null || index >= stops.size() || index >= coordinates.size()) {
            return fallback;
        }
        Place previous = stops.get(index - 1);
        Place current = stops.get(index);
        if (isThemeParkLunchToContinuation(previous, current)) {
            return 0;
        }
        StopCoordinate origin = coordinates.get(index - 1);
        StopCoordinate destination = coordinates.get(index);
        if (origin == null || destination == null) {
            return fallback;
        }
        Integer lightweightTransfer = estimateSchedulingTransferMinutes(previous, current, origin, destination, recommendationContext);
        int buffered;
        if (lightweightTransfer != null) {
            buffered = lightweightTransfer;
        } else {
            RouteChoice routeChoice = shortWalkRouteChoice(previous, current, origin, destination, recommendationContext, false);
            if (routeChoice == null) {
                routeChoice = resolveRouteChoice(origin.asLatLon(), destination.asLatLon(), recommendationContext, false, routeChoiceCache);
            }
            ModeSummary recommended = routeChoice.recommended();
            if (recommended == null || recommended.durationMinutes() == null || recommended.durationMinutes() <= 0) {
                return fallback;
            }
            buffered = recommended.durationMinutes() + 5;
        }
        int allowedCap = maxAllowedGapMinutes(previous, current, index == stops.size() - 1);
        if (hasMealSlot(current, "lunch")) {
            int previousEnd = parseTimeMinutes(previous.endTime());
            if (!isThemeParkLikeStop(previous) && previousEnd >= 0 && previousEnd < LUNCH_LATEST_START_MINUTES + 5) {
                allowedCap = Math.min(allowedCap, Math.max(transitionMinutes(false), LUNCH_LATEST_START_MINUTES + 5 - previousEnd));
            }
        }
        if (hasMealSlot(current, "dinner") && hasThemeParkBeforeIndex(stops, index)) {
            int previousEnd = parseTimeMinutes(previous.endTime());
            if (previousEnd >= 0 && previousEnd < THEME_PARK_DAY_DINNER_LATEST_START_MINUTES) {
                allowedCap = Math.min(
                        allowedCap,
                        Math.max(transitionMinutes(false), THEME_PARK_DAY_DINNER_LATEST_START_MINUTES - previousEnd)
                );
            }
        }
        return Math.max(fallback, Math.min(buffered, allowedCap));
    }

    private Integer estimateSchedulingTransferMinutes(
            Place previous,
            Place current,
            StopCoordinate origin,
            StopCoordinate destination,
            RouteRecommendationContext context
    ) {
        if (previous == null || current == null || origin == null || destination == null) {
            return null;
        }
        if (context != null && context.hasKids()) {
            return null;
        }
        if (isThemeParkLikeStop(previous) || isThemeParkLikeStop(current)) {
            return null;
        }

        double straightLineMeters = haversineMeters(origin.lat(), origin.lon(), destination.lat(), destination.lon());
        if (sameStopOrAddress(previous, current) && straightLineMeters <= SCHEDULING_DIRECT_WALK_ESTIMATE_MAX_DISTANCE_METERS) {
            return estimateShortWalkMinutes((int) Math.ceil(straightLineMeters)) + 5;
        }
        if (straightLineMeters <= SCHEDULING_DIRECT_WALK_ESTIMATE_MAX_DISTANCE_METERS && !isStrictMealStop(previous) && !isStrictMealStop(current)) {
            return estimateShortWalkMinutes((int) Math.ceil(straightLineMeters)) + 5;
        }
        if (sameCulturalPrecinct(previous, current) && straightLineMeters <= SCHEDULING_CULTURAL_PRECINCT_WALK_ESTIMATE_MAX_DISTANCE_METERS) {
            return estimateShortWalkMinutes((int) Math.ceil(straightLineMeters)) + 5;
        }
        String previousArea = normalizedAreaLabel(previous);
        String currentArea = normalizedAreaLabel(current);
        if (areasEquivalent(previousArea, currentArea)
                && straightLineMeters <= SCHEDULING_SAME_AREA_WALK_ESTIMATE_MAX_DISTANCE_METERS
                && !requiresExternalRoutingForScheduling(previous, current)) {
            return estimateShortWalkMinutes((int) Math.ceil(straightLineMeters)) + 5;
        }
        if (sameAreaFallbackAllowed(previous, current)
                && straightLineMeters <= SCHEDULING_SAME_SUBURB_WALK_ESTIMATE_MAX_DISTANCE_METERS
                && !requiresExternalRoutingForScheduling(previous, current)) {
            return estimateShortWalkMinutes((int) Math.ceil(straightLineMeters)) + 5;
        }
        return null;
    }

    private boolean requiresExternalRoutingForScheduling(Place previous, Place current) {
        return isLateDayViewStop(previous)
                || isLateDayViewStop(current)
                || isThemeParkLikeStop(previous)
                || isThemeParkLikeStop(current);
    }

    private boolean sameStopOrAddress(Place previous, Place current) {
        if (previous == null || current == null) {
            return false;
        }
        String previousMap = normalizeSlot(previous.googleMapsUri());
        String currentMap = normalizeSlot(current.googleMapsUri());
        if (!previousMap.isBlank() && previousMap.equals(currentMap)) {
            return true;
        }
        String previousAddress = normalizeSlot(previous.addressLine());
        String currentAddress = normalizeSlot(current.addressLine());
        return !previousAddress.isBlank() && previousAddress.equals(currentAddress);
    }

    private boolean sameCulturalPrecinct(Place previous, Place current) {
        if (previous == null || current == null) {
            return false;
        }
        if (!isCulturalPrecinctStop(previous) || !isCulturalPrecinctStop(current)) {
            return false;
        }
        String previousArea = normalizedAreaLabel(previous);
        String currentArea = normalizedAreaLabel(current);
        return areasEquivalent(previousArea, currentArea) || sameAreaFallbackAllowed(previous, current);
    }

    private boolean isCulturalPrecinctStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "museum".equals(category)
                || "gallery".equals(category)
                || "cultural".equals(category)
                || "heritage".equals(category)
                || "art_gallery".equals(category);
    }

    private RouteChoice shortWalkRouteChoice(
            Place previous,
            Place current,
            StopCoordinate origin,
            StopCoordinate destination,
            RouteRecommendationContext context,
            boolean rainy
    ) {
        if (!shouldUseShortWalkEstimate(previous, current, origin, destination, context, rainy)) {
            return null;
        }
        int distanceMeters = (int) Math.ceil(haversineMeters(origin.lat(), origin.lon(), destination.lat(), destination.lon()));
        int estimatedMinutes = estimateShortWalkMinutes(distanceMeters);
        ModeSummary walk = new ModeSummary("walk", estimatedMinutes, distanceMeters);
        return new RouteChoice(walk, null, null, walk);
    }

    private boolean shouldUseShortWalkEstimate(
            Place previous,
            Place current,
            StopCoordinate origin,
            StopCoordinate destination,
            RouteRecommendationContext context,
            boolean rainy
    ) {
        if (previous == null || current == null || origin == null || destination == null) {
            return false;
        }
        if (rainy || (context != null && context.hasKids())) {
            return false;
        }
        if (isThemeParkLikeStop(previous) || isThemeParkLikeStop(current)) {
            return false;
        }
        if (hasMealSlot(previous, "lunch") || hasMealSlot(previous, "dinner")
                || hasMealSlot(current, "lunch") || hasMealSlot(current, "dinner")) {
            return false;
        }
        if (isCulturalOpeningHoursConstrained(previous) || isCulturalOpeningHoursConstrained(current)
                || isLateDayViewStop(previous) || isLateDayViewStop(current)) {
            return false;
        }
        String previousArea = normalizedAreaLabel(previous);
        String currentArea = normalizedAreaLabel(current);
        boolean sameArea = areasEquivalent(previousArea, currentArea);
        double straightLineMeters = haversineMeters(origin.lat(), origin.lon(), destination.lat(), destination.lon());
        if (sameArea) {
            return straightLineMeters <= SHORT_WALK_ESTIMATE_MAX_DISTANCE_METERS;
        }
        return sameAreaFallbackAllowed(previous, current) && straightLineMeters <= SHORT_WALK_ESTIMATE_STRICT_AREA_DISTANCE_METERS;
    }

    private boolean sameAreaFallbackAllowed(Place previous, Place current) {
        String previousSuburb = normalizeSlot(previous == null ? null : previous.suburb());
        String currentSuburb = normalizeSlot(current == null ? null : current.suburb());
        String previousCity = normalizeSlot(previous == null ? null : previous.city());
        String currentCity = normalizeSlot(current == null ? null : current.city());
        return !previousSuburb.isBlank()
                && previousSuburb.equals(currentSuburb)
                && !previousCity.isBlank()
                && previousCity.equals(currentCity);
    }

    private int estimateShortWalkMinutes(int distanceMeters) {
        int walkingMinutes = (int) Math.ceil(Math.max(0, distanceMeters) / SHORT_WALK_ESTIMATE_METERS_PER_MINUTE);
        return Math.max(SHORT_WALK_ESTIMATE_MIN_MINUTES, walkingMinutes + SHORT_WALK_ESTIMATE_BUFFER_MINUTES);
    }

    private boolean isThemeParkLunchToContinuation(Place previous, Place current) {
        return hasMealSlot(previous, "lunch")
                && isThemeParkContinuationStop(current)
                && isSameThemeParkCluster(current, previous);
    }

    private boolean isThemeParkContinuationStop(Place stop) {
        if (!isThemeParkLikeStop(stop) || !"afternoon".equals(normalizeSlot(stop.timeSlot()))) {
            return false;
        }
        String name = nullToEmpty(stop.name()).toLowerCase(Locale.ROOT);
        return name.contains("(afternoon)")
                || name.contains("afternoon visit")
                || name.contains("continued visit")
                || name.contains("continuation")
                || name.contains("return visit");
    }

    private RouteChoice resolveRouteChoice(
            String origin,
            String destination,
            RouteRecommendationContext context,
            boolean rainy,
            Map<RouteChoiceCacheKey, RouteChoice> routeChoiceCache
    ) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return new RouteChoice(null, null, null, null);
        }
        RouteChoiceCacheKey cacheKey = routeChoiceCacheKey(origin, destination, rainy, context);
        if (routeChoiceCache != null) {
            RouteChoice requestScoped = routeChoiceCache.get(cacheKey);
            if (requestScoped != null) {
                return requestScoped;
            }
        }
        RouteChoice crossRequestCached = getRouteChoiceFromHybridCache(cacheKey);
        if (crossRequestCached != null) {
            if (routeChoiceCache != null) {
                routeChoiceCache.put(cacheKey, crossRequestCached);
            }
            return crossRequestCached;
        }
        RouteChoice computed = computeRouteChoice(origin, destination, context, rainy);
        putRouteChoiceIntoHybridCache(cacheKey, computed);
        if (routeChoiceCache != null) {
            routeChoiceCache.put(cacheKey, computed);
        }
        return computed;
    }

    private RouteChoice getRouteChoiceFromHybridCache(RouteChoiceCacheKey cacheKey) {
        RouteChoice local = ROUTE_CHOICE_L1_CACHE.getIfPresent(cacheKey);
        if (local != null) {
            return local;
        }
        if (stringRedisTemplate == null) {
            return null;
        }
        try {
            String payload = stringRedisTemplate.opsForValue().get(routeChoiceRedisKey(cacheKey));
            if (payload == null || payload.isBlank()) {
                return null;
            }
            RouteChoice cached = objectMapper.readValue(payload, RouteChoice.class);
            ROUTE_CHOICE_L1_CACHE.put(cacheKey, cached);
            return cached;
        } catch (Exception ex) {
            return null;
        }
    }

    private void putRouteChoiceIntoHybridCache(RouteChoiceCacheKey cacheKey, RouteChoice routeChoice) {
        if (routeChoice == null) {
            return;
        }
        ROUTE_CHOICE_L1_CACHE.put(cacheKey, routeChoice);
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(routeChoice);
            stringRedisTemplate.opsForValue().set(routeChoiceRedisKey(cacheKey), payload, ttlWithJitter(ROUTE_CHOICE_CACHE_TTL));
        } catch (Exception ex) {
            // Best-effort cache write only.
        }
    }

    private Duration ttlWithJitter(Duration baseTtl) {
        long baseMillis = baseTtl == null ? 0L : baseTtl.toMillis();
        if (baseMillis <= 0L) {
            return baseTtl;
        }
        long jitterRange = Math.max(1L, Math.round(baseMillis * REDIS_TTL_JITTER_RATIO));
        long offset = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1L);
        return Duration.ofMillis(Math.max(1_000L, baseMillis + offset));
    }

    private String routeChoiceRedisKey(RouteChoiceCacheKey cacheKey) {
        return ROUTE_CHOICE_REDIS_PREFIX
                + cacheKey.origin()
                + "|"
                + cacheKey.destination()
                + "|r="
                + (cacheKey.rainy() ? "1" : "0")
                + "|k="
                + (cacheKey.hasKids() ? "1" : "0");
    }

    private RouteChoiceCacheKey routeChoiceCacheKey(String origin, String destination, boolean rainy, RouteRecommendationContext context) {
        boolean hasKids = context != null && context.hasKids();
        return new RouteChoiceCacheKey(normalizeLatLonKey(origin), normalizeLatLonKey(destination), rainy, hasKids);
    }

    private String normalizeLatLonKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] parts = raw.trim().split(",");
        if (parts.length != 2) {
            return raw.trim();
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            return roundCoordinate(lat) + "," + roundCoordinate(lon);
        } catch (NumberFormatException ex) {
            return raw.trim();
        }
    }

    private String roundCoordinate(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private RouteChoice computeRouteChoice(String origin, String destination, RouteRecommendationContext context, boolean rainy) {
        ModeSummary walk = modeSummary("walk", () -> withBulkhead(ROUTE_SUMMARY_BULKHEAD, () -> mapService.walk_summary(origin, destination), null));
        boolean hasKids = context != null && context.hasKids();
        int walkDirectThreshold = hasKids ? 15 : 20;
        if (rainy) {
            walkDirectThreshold = Math.min(walkDirectThreshold, 10);
        }
        if (walk != null && walk.durationMinutes() <= walkDirectThreshold) {
            return new RouteChoice(walk, null, null, walk);
        }

        ModeSummary transit = modeSummary("transit", () -> withBulkhead(ROUTE_SUMMARY_BULKHEAD, () -> mapService.transit_summary(origin, destination), null));
        int walkCompareThreshold = hasKids ? 20 : 30;
        if (rainy) {
            walkCompareThreshold = Math.min(walkCompareThreshold, 15);
        }
        if (transit != null) {
            if (walk != null && walk.durationMinutes() <= walkCompareThreshold && transit.durationMinutes() - walk.durationMinutes() > -8) {
                return new RouteChoice(walk, transit, null, walk);
            }
            if (transit.durationMinutes() < 35) {
                return new RouteChoice(walk, transit, null, transit);
            }
        }

        ModeSummary car = normalizeCarSummary(modeSummary("car", () -> withBulkhead(ROUTE_SUMMARY_BULKHEAD, () -> mapService.car_summary(origin, destination), null)));
        ModeSummary recommended = recommendMode(walk, transit, car, context, rainy);
        return new RouteChoice(walk, transit, car, recommended);
    }

    private record RouteChoiceCacheKey(String origin, String destination, boolean rainy, boolean hasKids) {}
    private record GapClampPair(Place previous, Place current) {}

    void clearRouteChoiceCrossRequestCache() {
        ROUTE_CHOICE_L1_CACHE.invalidateAll();
        if (stringRedisTemplate != null) {
            try {
                var keys = stringRedisTemplate.keys(ROUTE_CHOICE_REDIS_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    stringRedisTemplate.delete(keys);
                }
            } catch (Exception ex) {
                // Best-effort cache cleanup only.
            }
        }
    }

    void clearRouteChoiceLocalCacheOnly() {
        ROUTE_CHOICE_L1_CACHE.invalidateAll();
    }

    private ModeSummary modeSummary(String mode, RouteSummarySupplier supplier) {
        try {
            MapService.RouteSummary summary = supplier.get();
            if (summary == null || summary.durationSeconds() == null || summary.durationSeconds().isBlank()) {
                return null;
            }
            Integer durationMinutes = routeSummaryMinutes(() -> summary);
            Integer distanceMeters = parseInteger(summary.distanceMeters());
            if (durationMinutes == null || durationMinutes <= 0) {
                return null;
            }
            return new ModeSummary(mode, durationMinutes, distanceMeters);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ModeSummary normalizeCarSummary(ModeSummary car) {
        if (car == null || car.distanceMeters() == null || car.distanceMeters() <= 0) {
            return car;
        }
        int urbanFloor = Math.max(5, (int) Math.ceil((car.distanceMeters() / 1000.0) / 25.0 * 60.0) + 3);
        int normalizedMinutes = Math.max(car.durationMinutes(), urbanFloor);
        return new ModeSummary(car.mode(), normalizedMinutes, car.distanceMeters());
    }

    private ModeSummary recommendMode(ModeSummary walk, ModeSummary transit, ModeSummary car, RouteRecommendationContext context, boolean rainy) {
        boolean hasKids = context != null && context.hasKids();
        int walkDirectThreshold = hasKids ? 15 : 20;
        int walkCompareThreshold = hasKids ? 20 : 30;
        if (rainy) {
            walkDirectThreshold = Math.min(walkDirectThreshold, 10);
            walkCompareThreshold = Math.min(walkCompareThreshold, 15);
        }
        if (walk != null && walk.durationMinutes() <= walkDirectThreshold) {
            return walk;
        }
        if (transit != null) {
            if (walk != null && walk.durationMinutes() <= walkCompareThreshold && transit.durationMinutes() - walk.durationMinutes() > -8) {
                return walk;
            }
            if (car != null && transit.durationMinutes() - car.durationMinutes() >= 20) {
                return car;
            }
            return transit;
        }
        if (car != null) {
            return car;
        }
        return walk;
    }

    private Integer routeSummaryMinutes(RouteSummarySupplier supplier) {
        try {
            MapService.RouteSummary summary = supplier.get();
            if (summary == null || summary.durationSeconds() == null || summary.durationSeconds().isBlank()) {
                return null;
            }
            int seconds = (int) Math.round(Double.parseDouble(summary.durationSeconds().trim()));
            if (seconds <= 0) {
                return null;
            }
            return Math.max(1, (int) Math.ceil(seconds / 60.0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private StopCoordinate joinNullable(CompletableFuture<StopCoordinate> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return null;
        }
    }

    private int joinInteger(CompletableFuture<Integer> future, int fallback) {
        try {
            Integer value = future.join();
            return value == null ? fallback : value;
        } catch (CompletionException e) {
            return fallback;
        }
    }

    private DeterministicFallbackResult deterministicRetryFallbackIfValid(
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

    private DeterministicFallbackResult deterministicRepairIfValid(
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
        boolean deterministicRepairOnly = validationIssues.stream().allMatch(this::isDeterministicRepairIssue);
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
        return new DeterministicFallbackResult(repaired, repairedIssues, repairedIssues.isEmpty());
    }

    private boolean isDeterministicRepairIssue(String issue) {
        if (issue == null) {
            return false;
        }
        return issue.endsWith("-gap-too-large")
                || issue.endsWith("-lunch-time-invalid")
                || issue.endsWith("-dinner-time-invalid")
                || issue.endsWith("-dinner-too-early")
                || issue.endsWith("-theme-park-dinner-too-late")
                || issue.endsWith("-time-sensitive-too-late")
                || issue.endsWith("-duplicate-poi-same-day")
                || issue.endsWith("-duplicate-poi-across-days");
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
            if (!isDeterministicRepairIssue(issue)) {
                return false;
            }
            otherDeterministicCount++;
        }
        return duplicateCount > 0 && duplicateCount > otherDeterministicCount;
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
        if (isDeterministicRepairIssue(issue)) {
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
        long stageStartedAt = System.currentTimeMillis();
        PlanDraftResponse repaired = draft;
        for (int pass = 0; pass < DETERMINISTIC_REPAIR_MAX_PASSES; pass++) {
            List<String> currentIssues = collectDeterministicValidationIssues(repaired);
            if (currentIssues.isEmpty()) {
                break;
            }
            String beforeSnapshot = deterministicRepairSnapshot(repaired);
            java.util.Set<Integer> changedDayIndexes = new java.util.LinkedHashSet<>();

            if (hasAnyIssue(currentIssues, "-time-sensitive-too-late")) {
                PlanDraftResponse beforeTimeSensitiveRepair = repaired;
                repaired = repairTimeSensitiveLateStops(repaired);
                changedDayIndexes.addAll(detectChangedDayIndexes(beforeTimeSensitiveRepair, repaired));
            }

            if (hasAnyIssue(currentIssues, "-lunch-time-invalid")
                    || hasAnyIssue(currentIssues, "-dinner-time-invalid")
                    || hasAnyIssue(currentIssues, "-dinner-too-early")
                    || hasAnyIssue(currentIssues, "-theme-park-dinner-too-late")) {
                PlanDraftResponse beforeMealTimeRepair = repaired;
                repaired = repairMealTimeWindowIssues(repaired);
                changedDayIndexes.addAll(detectChangedDayIndexes(beforeMealTimeRepair, repaired));
            }

            if (hasAnyIssue(currentIssues, "-duplicate-poi-across-days")) {
                PlanDraftResponse beforeDuplicateRepair = repaired;
                repaired = repairCrossDayDuplicatePois(repaired);
                changedDayIndexes.addAll(detectChangedDayIndexes(beforeDuplicateRepair, repaired));
            }

            if (hasAnyIssue(currentIssues, "-duplicate-poi-same-day")) {
                PlanDraftResponse beforeDuplicateRepair = repaired;
                repaired = repairSameDayDuplicatePois(repaired);
                changedDayIndexes.addAll(detectChangedDayIndexes(beforeDuplicateRepair, repaired));
            }

            if (!changedDayIndexes.isEmpty()) {
                repaired = normalizeDraftScheduleWithRouteDurations(repaired, changedDayIndexes);
            }

            List<String> issuesAfterTargetedRepairs = collectDeterministicValidationIssues(repaired);
            if (issuesAfterTargetedRepairs.isEmpty()) {
                break;
            }
            if (hasAnyIssue(issuesAfterTargetedRepairs, "-gap-too-large")) {
                repaired = clampOversizedGaps(repaired);
                repaired = bridgeSmallDeterministicGapOverruns(repaired);
            }
            if (beforeSnapshot.equals(deterministicRepairSnapshot(repaired))) {
                break;
            }
        }
        appendStageTiming(timingSummary, attemptLabel + "/deterministic-repair", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "deterministic-repair", repaired);
        return repaired;
    }

    private String deterministicRepairSnapshot(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return "";
        }
        return draft.daysPlan().stream()
                .filter(day -> day != null)
                .map(day -> day.dayIndex() + ":"
                        + (day.stops() == null ? "" : day.stops().stream()
                        .map(stop -> safeStopName(stop) + "@" + nullToEmpty(stop.startTime()) + "-" + nullToEmpty(stop.endTime()))
                        .collect(Collectors.joining("|"))))
                .collect(Collectors.joining(" || "));
    }

    private List<String> collectDeterministicValidationIssues(PlanDraftResponse draft) {
        return validateDraft(draft).stream()
                .filter(this::isDeterministicRepairIssue)
                .toList();
    }

    PlanDraftResponse repairMealTimeWindowIssues(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            List<Place> updatedStops = new ArrayList<>(day.stops());
            boolean dayChanged = false;
            for (int i = 0; i < updatedStops.size(); i++) {
                Place stop = updatedStops.get(i);
                int start = parseTimeMinutes(stop.startTime());
                if (start < 0 || (!hasMealSlot(stop, "lunch") && !hasMealSlot(stop, "dinner"))) {
                    continue;
                }
                int earliest = mealEarliestStart(stop);
                int latest = mealLatestStart(updatedStops, i);
                if (start >= earliest && start <= latest) {
                    continue;
                }
                if (start < earliest) {
                    updatedStops.set(i, copyPlaceWithTimes(stop, formatMinutes(earliest), formatMinutes(earliest + resolveStayMinutes(stop)), resolveStayMinutes(stop)));
                    dayChanged = true;
                    changed = true;
                    continue;
                }
                if (repairLateMealByAdjustingPreviousStop(updatedStops, i, latest, minNonMealStops)) {
                    dayChanged = true;
                    changed = true;
                }
            }
            updatedDays.add(dayChanged
                    ? new DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note())
                    : day);
        }
        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean repairLateMealByAdjustingPreviousStop(List<Place> stops, int mealIndex, int latestMealStart, int minNonMealStops) {
        if (stops == null || mealIndex <= 0 || mealIndex >= stops.size()) {
            return false;
        }
        Place previous = stops.get(mealIndex - 1);
        if (previous == null || isFoodStop(previous)) {
            return false;
        }
        int previousStart = parseTimeMinutes(previous.startTime());
        int previousEnd = parseTimeMinutes(previous.endTime());
        int targetPreviousEnd = latestMealStart - transitionMinutes(false);
        int minPreviousStay = Math.min(45, Math.max(30, resolveStayMinutes(previous) / 2));
        if (previousStart >= 0 && previousEnd > previousStart && targetPreviousEnd >= previousStart + minPreviousStay && targetPreviousEnd < previousEnd) {
            stops.set(mealIndex - 1, copyPlaceWithTimes(previous, formatMinutes(previousStart), formatMinutes(targetPreviousEnd), targetPreviousEnd - previousStart));
            return true;
        }
        if (canDropDuplicateStopSafely(stops, previous, mealIndex - 1, minNonMealStops)) {
            stops.remove(mealIndex - 1);
            return true;
        }
        return false;
    }

    private int mealEarliestStart(Place stop) {
        if (hasMealSlot(stop, "lunch")) {
            return LUNCH_EARLIEST_START_MINUTES;
        }
        if (hasMealSlot(stop, "dinner")) {
            return DINNER_EARLIEST_START_MINUTES;
        }
        return 0;
    }

    private int mealLatestStart(List<Place> stops, int index) {
        Place stop = stops == null || index < 0 || index >= stops.size() ? null : stops.get(index);
        if (hasMealSlot(stop, "lunch")) {
            boolean themeParkDay = stops != null && stops.stream().anyMatch(this::isThemeParkLikeStop);
            return themeParkDay ? THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES : LUNCH_LATEST_START_MINUTES;
        }
        if (hasMealSlot(stop, "dinner")) {
            return hasThemeParkBeforeIndex(stops, index) ? THEME_PARK_DAY_DINNER_LATEST_START_MINUTES : DINNER_LATEST_START_MINUTES;
        }
        return Integer.MAX_VALUE;
    }

    PlanDraftResponse repairSameDayDuplicatePois(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        java.util.Set<String> retainedPoiNames = new java.util.LinkedHashSet<>();
        for (DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null) {
                continue;
            }
            day.stops().forEach(stop -> registerRetainedPoiName(retainedPoiNames, stop));
        }
        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
            List<Place> updatedStops = new ArrayList<>(day.stops());
            boolean dayChanged = false;
            int index = 0;
            while (index < updatedStops.size()) {
                Place stop = updatedStops.get(index);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    index++;
                    continue;
                }
                SeenPoiStop firstSeen = findSeenPoi(duplicateKeys, seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(stop)), seenStops);
                    index++;
                    continue;
                }
                if (canDropDuplicateStopSafely(updatedStops, stop, index, minNonMealStops)) {
                    updatedStops.remove(index);
                    dayChanged = true;
                    changed = true;
                    continue;
                }
                Place resolvedReplacement = resolveCrossDayDuplicateReplacementStop(
                        stop,
                        day.dayIndex(),
                        index + 1,
                        retainedPoiNames
                );
                updatedStops.set(index, resolvedReplacement);
                registerSeenPoiKeys(
                        crossDayDuplicatePoiKeys(resolvedReplacement),
                        new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(resolvedReplacement)),
                        seenStops
                );
                registerRetainedPoiName(retainedPoiNames, resolvedReplacement);
                dayChanged = true;
                changed = true;
                index++;
            }
            if (dayChanged) {
                updatedDays.add(new DayPlan(
                        day.dayIndex(),
                        day.hotel(),
                        updatedStops,
                        day.theme(),
                        day.morningNote(),
                        day.afternoonNote(),
                        day.eveningNote(),
                        day.note()
                ));
            } else {
                updatedDays.add(day);
            }
        }
        if (!changed) {
            return draft;
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
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    PlanDraftResponse repairCrossDayDuplicatePois(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
        java.util.Set<String> retainedPoiNames = new java.util.LinkedHashSet<>();
        List<DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            List<Place> updatedStops = new ArrayList<>(day.stops());
            boolean dayChanged = false;
            int index = 0;
            while (index < updatedStops.size()) {
                Place stop = updatedStops.get(index);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    registerRetainedPoiName(retainedPoiNames, stop);
                    index++;
                    continue;
                }
                SeenPoiStop firstSeen = findCrossDaySeenPoi(duplicateKeys, day.dayIndex(), seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(stop)), seenStops);
                    registerRetainedPoiName(retainedPoiNames, stop);
                    index++;
                    continue;
                }
                if (canDropDuplicateStopSafely(updatedStops, stop, index, minNonMealStops)) {
                    updatedStops.remove(index);
                    dayChanged = true;
                    changed = true;
                    continue;
                }
                Place resolvedReplacement = resolveCrossDayDuplicateReplacementStop(
                        stop,
                        day.dayIndex(),
                        index + 1,
                        retainedPoiNames
                );
                updatedStops.set(index, resolvedReplacement);
                registerSeenPoiKeys(
                        crossDayDuplicatePoiKeys(resolvedReplacement),
                        new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(resolvedReplacement)),
                        seenStops
                );
                registerRetainedPoiName(retainedPoiNames, resolvedReplacement);
                dayChanged = true;
                changed = true;
                index++;
            }
            if (dayChanged) {
                updatedDays.add(new DayPlan(
                        day.dayIndex(),
                        day.hotel(),
                        updatedStops,
                        day.theme(),
                        day.morningNote(),
                        day.afternoonNote(),
                        day.eveningNote(),
                        day.note()
                ));
            } else {
                updatedDays.add(day);
            }
        }
        if (!changed) {
            return draft;
        }
        PlanDraftResponse repaired = new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                updatedDays,
                draft.copyPolishStatus()
        );
        return repaired;
    }

    private Place resolveCrossDayDuplicateReplacementStop(
            Place stop,
            int dayIndex,
            int stopIndex,
            java.util.Set<String> retainedPoiNames
    ) {
        Place realCandidate = tryResolveRealAreaLevelDuplicateReplacement(stop, retainedPoiNames);
        if (realCandidate != null) {
            registerRetainedPoiName(retainedPoiNames, realCandidate);
            return realCandidate;
        }
        return buildCrossDayDuplicateFallbackStop(stop, dayIndex, stopIndex, retainedPoiNames);
    }

    private Place tryResolveRealAreaLevelDuplicateReplacement(Place stop, java.util.Set<String> retainedPoiNames) {
        if (stop == null || stop.city() == null || stop.city().isBlank()) {
            return null;
        }
        for (String query : crossDayDuplicateReplacementQueries(stop)) {
            List<GooglePlacesClient.PlaceCandidate> candidates;
            try {
                candidates = googlePlacesClient.searchText(query, stop.city());
            } catch (Exception e) {
                log.debug("Duplicate replacement candidate search skipped query={} city={}", query, stop.city(), e);
                continue;
            }
            GooglePlacesClient.PlaceCandidate best = candidates.stream()
                    .filter(candidate -> isUsableCrossDayDuplicateCandidate(stop, candidate, retainedPoiNames))
                    .max(java.util.Comparator.comparingInt(candidate -> scoreCrossDayDuplicateCandidate(stop, candidate)))
                    .orElse(null);
            if (best != null) {
                return copyCrossDayDuplicateWithCandidate(stop, best);
            }
        }
        return null;
    }

    private List<String> crossDayDuplicateReplacementQueries(Place stop) {
        List<String> queries = new ArrayList<>();
        if (stop == null) {
            return queries;
        }
        String area = displayArea(stop);
        String category = normalizeSlot(stop.category());
        switch (category) {
            case "museum", "gallery", "cultural" -> {
                addUnique(queries, "museum near " + area);
                addUnique(queries, "art gallery near " + area);
            }
            case "park", "nature", "outdoor" -> {
                addUnique(queries, "park near " + area);
                addUnique(queries, "botanic garden near " + area);
            }
            case "lookout", "viewpoint", "landmark" -> {
                addUnique(queries, "landmark near " + area);
                addUnique(queries, "lookout near " + area);
            }
            case "market", "shop", "shopping" -> {
                addUnique(queries, "market near " + area);
                addUnique(queries, "cultural centre near " + area);
            }
            default -> {
                addUnique(queries, "tourist attraction near " + area);
                addUnique(queries, "landmark near " + area);
            }
        }
        addUnique(queries, "tourist attraction near " + area);
        return queries;
    }

    private boolean isUsableCrossDayDuplicateCandidate(
            Place originalStop,
            GooglePlacesClient.PlaceCandidate candidate,
            java.util.Set<String> retainedPoiNames
    ) {
        if (candidate == null || candidate.name() == null || candidate.name().isBlank()) {
            return false;
        }
        String businessStatus = normalizeSlot(candidate.businessStatus());
        if (!businessStatus.isBlank() && !"operational".equals(businessStatus)) {
            return false;
        }
        String candidateNameKey = normalizedPoiIdentity(candidate.name());
        if (candidateNameKey.isBlank()) {
            return false;
        }
        if (candidateNameKey.equals(normalizedPoiIdentity(originalStop.name()))) {
            return false;
        }
        boolean clashesWithRetained = retainedPoiNames.stream()
                .map(this::normalizedPoiIdentity)
                .anyMatch(existing -> existing.equals(candidateNameKey));
        if (clashesWithRetained) {
            return false;
        }
        String types = candidate.types() == null ? "" : String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("restaurant")
                || types.contains("food")
                || types.contains("cafe")
                || types.contains("lodging")
                || types.contains("hotel")
                || types.contains("shopping_mall")
                || types.contains("store")
                || types.contains("supermarket")
                || types.contains("transit_station")
                || types.contains("bus_station")
                || types.contains("train_station")) {
            return false;
        }
        return types.contains("museum")
                || types.contains("art_gallery")
                || types.contains("park")
                || types.contains("tourist_attraction")
                || types.contains("point_of_interest")
                || types.contains("landmark")
                || types.contains("cultural")
                || types.contains("botanical_garden");
    }

    private int scoreCrossDayDuplicateCandidate(Place originalStop, GooglePlacesClient.PlaceCandidate candidate) {
        String originalCategory = normalizeSlot(originalStop.category());
        String candidateTypes = candidate.types() == null ? "" : String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        String candidateText = placeHeuristicService.normalizeSearchText(
                candidate.name() + " " + candidate.formattedAddress() + " " + candidateTypes
        );
        int score = placeHeuristicService.commonSignificantTokenCount(displayArea(originalStop), candidateText) * 20;
        if ("museum".equals(originalCategory) || "gallery".equals(originalCategory) || "cultural".equals(originalCategory)) {
            if (candidateTypes.contains("museum") || candidateTypes.contains("art_gallery")) score += 120;
        } else if ("park".equals(originalCategory) || "nature".equals(originalCategory) || "outdoor".equals(originalCategory)) {
            if (candidateTypes.contains("park") || candidateTypes.contains("botanical_garden")) score += 120;
        } else if ("lookout".equals(originalCategory) || "viewpoint".equals(originalCategory) || "landmark".equals(originalCategory)) {
            if (candidateTypes.contains("landmark") || candidateTypes.contains("tourist_attraction")) score += 100;
        } else {
            if (candidateTypes.contains("tourist_attraction") || candidateTypes.contains("point_of_interest")) score += 80;
        }
        if (candidateTypes.contains("point_of_interest")) score += 20;
        if (candidate.googleMapsUri() != null && !candidate.googleMapsUri().isBlank()) score += 10;
        return score;
    }

    private Place copyCrossDayDuplicateWithCandidate(Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String addressLine = candidate.formattedAddress() == null || candidate.formattedAddress().isBlank()
                ? stop.addressLine()
                : candidate.formattedAddress();
        ParsedAddress parsedAddress = parseAustralianAddress(candidate.formattedAddress(), stop);
        String suburb = parsedAddress.suburb().isBlank() ? stop.suburb() : parsedAddress.suburb();
        String state = parsedAddress.state().isBlank() ? stop.state() : parsedAddress.state();
        String postcode = parsedAddress.postcode().isBlank() ? stop.postcode() : parsedAddress.postcode();
        String country = parsedAddress.country().isBlank() ? stop.country() : parsedAddress.country();
        String normalizedAddressLine = parsedAddress.addressLine().isBlank() ? addressLine : parsedAddress.addressLine();
        String preferredArea = suburb == null || suburb.isBlank() ? stop.preferredArea() : suburb;
        String resolvedCategory = normalizeCrossDayDuplicateCandidateCategory(stop, candidate);
        String reason = candidate.name() + " keeps this " + normalizeSlot(stop.timeSlot()) + " block grounded around " + displayArea(stop) + " without repeating a previously used sight.";
        String tip = "Trim this stop first if transfers start compressing the rest of the day.";
        String url = candidate.googleMapsUri() == null || candidate.googleMapsUri().isBlank()
                ? stop.url()
                : candidate.googleMapsUri();
        return new Place(
                candidate.name(),
                normalizedAddressLine,
                suburb,
                stop.city(),
                state,
                postcode,
                country,
                resolvedCategory,
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                preferredArea,
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                reason,
                tip,
                candidate.websiteUri(),
                candidate.googleMapsUri(),
                candidate.businessStatus(),
                url,
                Double.isNaN(candidate.lat()) ? stop.latitude() : candidate.lat(),
                Double.isNaN(candidate.lng()) ? stop.longitude() : candidate.lng()
        );
    }

    private String normalizeCrossDayDuplicateCandidateCategory(Place originalStop, GooglePlacesClient.PlaceCandidate candidate) {
        String types = candidate.types() == null ? "" : String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("museum") || types.contains("art_gallery")) {
            return "museum";
        }
        if (types.contains("park") || types.contains("botanical_garden")) {
            return "park";
        }
        if (types.contains("landmark") || types.contains("tourist_attraction")) {
            return "attraction";
        }
        return normalizeSlot(originalStop.category()).isBlank() ? "attraction" : originalStop.category();
    }

    private Place buildCrossDayDuplicateFallbackStop(Place stop, int dayIndex, int stopIndex, java.util.Set<String> retainedPoiNames) {
        if (stop == null) {
            return null;
        }
        String area = displayArea(stop);
        String slot = normalizeSlot(stop.timeSlot());
        String theme = switch (normalizeSlot(stop.category())) {
            case "museum", "gallery", "cultural" -> "Heritage Stroll";
            case "park", "nature" -> "Garden Stroll";
            case "lookout", "viewpoint", "landmark" -> "Scenic Stroll";
            case "shop", "market" -> "Local Stroll";
            default -> "Neighborhood Stroll";
        };
        String slotLabel = switch (slot) {
            case "morning" -> "Morning ";
            case "afternoon" -> "Afternoon ";
            case "sunset" -> "Sunset ";
            case "evening", "dinner" -> "Evening ";
            default -> "";
        };
        String uniqueName = (area + " " + slotLabel + theme).replaceAll("\\s+", " ").trim();
        uniqueName = ensureUniqueFallbackPoiName(uniqueName, stop, retainedPoiNames, dayIndex, stopIndex);
        return new Place(
                uniqueName,
                stop.addressLine(),
                stop.suburb(),
                stop.city(),
                stop.state(),
                stop.postcode(),
                stop.country(),
                "walk",
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                stop.preferredArea(),
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                "Flexible nearby filler after duplicate removal.",
                "Trim this block first if timing gets tight.",
                null,
                null,
                null,
                null,
                stop.latitude(),
                stop.longitude()
        );
    }

    private void registerRetainedPoiName(java.util.Set<String> retainedPoiNames, Place stop) {
        if (retainedPoiNames == null || stop == null || stop.name() == null || stop.name().isBlank()) {
            return;
        }
        String normalized = normalizedPoiIdentity(stop.name());
        if (!normalized.isBlank()) {
            retainedPoiNames.add(normalized);
        }
    }

    private String ensureUniqueFallbackPoiName(String candidate, Place originalStop, java.util.Set<String> retainedPoiNames, int dayIndex, int stopIndex) {
        String uniqueName = candidate == null ? "" : candidate.trim();
        String normalizedCandidate = normalizedPoiIdentity(uniqueName);
        String normalizedOriginal = originalStop == null ? "" : normalizedPoiIdentity(originalStop.name());
        int suffix = 2;
        while (!normalizedCandidate.isBlank() && (retainedPoiNames.contains(normalizedCandidate) || normalizedCandidate.equals(normalizedOriginal))) {
            uniqueName = candidate.trim() + " Alt " + suffix;
            normalizedCandidate = normalizedPoiIdentity(uniqueName);
            suffix++;
        }
        if (!normalizedCandidate.isBlank()) {
            retainedPoiNames.add(normalizedCandidate);
        }
        return uniqueName;
    }

    private boolean canDropDuplicateStopSafely(List<Place> stops, Place stop, int stopIndex, int minNonMealStops) {
        if (wouldDropBelowMinNonMealStops(stops, stop, minNonMealStops)) {
            return false;
        }
        return !isOnlyNonMealStopInDayPhase(stops, stop, stopIndex);
    }

    private boolean isOnlyNonMealStopInDayPhase(List<Place> stops, Place target, int targetIndex) {
        if (stops == null || stops.isEmpty() || target == null || isFoodStop(target)) {
            return false;
        }
        String phase = broadNonMealPhase(target);
        if (phase.isBlank()) {
            return false;
        }
        int samePhaseCount = 0;
        for (int i = 0; i < stops.size(); i++) {
            Place candidate = stops.get(i);
            if (isFoodStop(candidate) || !phase.equals(broadNonMealPhase(candidate))) {
                continue;
            }
            samePhaseCount++;
            if (samePhaseCount > 1) {
                return false;
            }
        }
        return samePhaseCount == 1;
    }

    private String broadNonMealPhase(Place stop) {
        String slot = normalizeSlot(stop == null ? null : stop.timeSlot());
        return switch (slot) {
            case "morning" -> "morning";
            case "afternoon", "sunset" -> "afternoon";
            case "evening", "night" -> "evening";
            default -> "";
        };
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
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
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
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                days,
                draft.copyPolishStatus()
        );
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
        if (!isThemeParkContinuationStop(previous) || !hasMealSlot(current, "dinner")) {
            return false;
        }
        if (previousEnd < 0 || currentStart < 0) {
            return false;
        }
        return currentStart - previousEnd > THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES;
    }

    private Place extendThemeParkContinuationBeforeDinner(Place previous, int dinnerStart) {
        int previousStart = parseTimeMinutes(previous.startTime());
        int previousEnd = parseTimeMinutes(previous.endTime());
        if (previousStart < 0 || previousEnd < 0 || dinnerStart < 0) {
            return previous;
        }
        int currentStay = Math.max(resolveStayMinutes(previous), previousEnd - previousStart);
        int targetStay = Math.min(
                THEME_PARK_CONTINUATION_MAX_EXTENSION_MINUTES,
                Math.max(currentStay, dinnerStart - THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES - previousStart)
        );
        int latestEndBeforeDinner = dinnerStart - transitionMinutes(false);
        int targetEnd = Math.min(previousStart + targetStay, latestEndBeforeDinner);
        if (targetEnd <= previousEnd) {
            return previous;
        }
        return copyPlaceWithTimes(previous, formatMinutes(previousStart), formatMinutes(targetEnd), targetEnd - previousStart);
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

    private PlanDraftResponse rewriteDayNarratives(PlanDraftResponse draft) {
        return rewriteDayNarratives(draft, null);
    }

    private PlanDraftResponse rewriteDayNarratives(PlanDraftResponse draft, java.util.Set<Integer> targetDayIndexes) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = draft.daysPlan().stream().map(day -> {
            if (day == null) {
                return null;
            }
            if (targetDayIndexes != null && !targetDayIndexes.isEmpty() && !targetDayIndexes.contains(day.dayIndex())) {
                return day;
            }
            List<Place> stops = day.stops() == null ? List.of() : day.stops().stream().map(this::normalizeNarrative).toList();
            boolean themeParkDay = stops.stream().anyMatch(this::isThemeParkLikeStop);
            String morningNote = themeParkDay ? sanitizeThemeParkCopy(day.morningNote()) : day.morningNote();
            String afternoonNote = themeParkDay ? sanitizeThemeParkCopy(day.afternoonNote()) : day.afternoonNote();
            String eveningNote = themeParkDay ? sanitizeThemeParkCopy(day.eveningNote()) : day.eveningNote();
            String note = themeParkDay ? sanitizeThemeParkCopy(day.note()) : day.note();
            return new DayPlan(day.dayIndex(), day.hotel(), stops, day.theme(), morningNote, afternoonNote, eveningNote, note);
        }).toList();
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
    }

    private Place normalizeNarrative(Place stop) {
        if (stop == null || isMealCategory(normalizeSlot(stop.category()))) {
            return stop;
        }
        String category = normalizeSlot(stop.category());
        String reason = stop.reason();
        String tip = stop.tip();
        String area = displayArea(stop);
        if (isThemeParkLikeStop(stop)) {
            reason = sanitizeThemeParkCopy(reason);
            tip = sanitizeThemeParkCopy(tip);
        } else if ("museum".equals(category)) {
            reason = stop.name() + " works as the day's main cultural block around " + area + ", giving the route substance before the lighter stops.";
            tip = "Check current exhibitions and ticket details before you set out.";
        } else if (isParkLikeStop(stop)) {
            reason = stop.name() + " gives the route an outdoor reset around " + area + " without adding another heavy indoor stop.";
            tip = "Keep this flexible for weather and energy, and shorten it if the day starts running late.";
        } else if ("attraction".equals(category)) {
            reason = stop.name() + " adds a compact sightseeing stop around " + area + " without making the day too dense.";
            tip = "Confirm current access details before visiting if it depends on scheduled entry or events.";
        }
        return copyPlaceWithNarrative(stop, reason, tip);
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

    private Place copyPlaceWithNarrative(Place stop, String reason, String tip) {
        return new Place(
                stop.name(),
                stop.addressLine(),
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
                reason,
                tip,
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                stop.latitude(),
                stop.longitude()
        );
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

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
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

    private interface RouteSummarySupplier {
        MapService.RouteSummary get();
    }

    private record PaceNonMealRange(int min, int max) {}
    private record StopLocation(double lat, double lon, String addressLine) {}
    private record ParsedAddress(String addressLine, String suburb, String state, String postcode, String country) {}
    private record RankedPlaceCoordinate(GooglePlacesClient.PlaceCandidate candidate, int score) {}
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
