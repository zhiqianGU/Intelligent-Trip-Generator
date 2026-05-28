package thesis.project.gu.service;

//测试接口
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.common.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.transaction.annotation.Transactional;
import thesis.project.gu.config.AiMockProperties;
import thesis.project.gu.config.AiProperties;
import thesis.project.gu.mapper.PlaceMapper;
import thesis.project.gu.mapper.TripPlanMapper;
import thesis.project.gu.model.*;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.PlanDraftResponse;
import thesis.project.gu.util.TripPromptTemplate;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

import static com.alibaba.dashscope.utils.Constants.apiKey;
import static thesis.project.gu.service.MapService.safe;

//测试接口
@Service
public class TripAiService {
    private static final int PLAN_MAX_TOKENS = 8192;
    private static final int RETRY_MAX_TOKENS_SHORT = 7168;
    private static final int RETRY_MAX_TOKENS_MEDIUM = 6144;
    private static final int RETRY_MAX_TOKENS_LONG = 5120;
    private static final int RETRY_MAX_TOKENS_XLONG = 4608;
    private static final String PHASED_DAY_CONCURRENCY_FLAG = "itrip.ai.phased.day.concurrency.max";
    private static final int DEFAULT_PHASED_DAY_CONCURRENCY = 5;

    public TripAiService(
            AiProperties aiProps,
            AiMockProperties aiMockProps,
            TripPlanMapper planMapper,
            PlaceMapper placeMapper,
            JdbcTemplate jdbc,
            RuntimeMetricsService runtimeMetricsService,
            DaySkeletonService daySkeletonService
    ) {
        this.aiProps = aiProps;
        this.aiMockProps = aiMockProps;
        this.planMapper = planMapper;
        this.placeMapper = placeMapper;
        this.jdbc = jdbc;
        this.runtimeMetricsService = runtimeMetricsService;
        this.daySkeletonService = daySkeletonService;
    }
    private static final Logger log = LoggerFactory.getLogger(TripAiService.class);
    private final AiProperties aiProps;
    private final AiMockProperties aiMockProps;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final TripPlanMapper planMapper;
    private final PlaceMapper placeMapper;
    private final JdbcTemplate jdbc;
    private final RuntimeMetricsService runtimeMetricsService;
    private final DaySkeletonService daySkeletonService;
    private final ExecutorService phasedDayExecutor = Executors.newFixedThreadPool(
            phasedDayConcurrencyLimit(),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("trip-ai-phased-day");
                thread.setDaemon(true);
                return thread;
            }
    );
    private final Semaphore phasedDayBulkhead = new Semaphore(phasedDayConcurrencyLimit(), true);

    @PostConstruct
    public void init() {
        ensureStyleTags();
        log.info("Trip AI phased-day concurrency max={}", phasedDayConcurrencyLimit());
    }

    @PreDestroy
    public void destroy() {
        phasedDayExecutor.shutdownNow();
    }

    @CircuitBreaker(name = "llmProvider", fallbackMethod = "generatePlanRawFallback")
    @RateLimiter(name = "llmProvider", fallbackMethod = "generatePlanRawFallback")
    public String generatePlanRaw(CreatePlanReq req)
            throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        String daySkeletonHints = daySkeletonService.toPromptHints(daySkeletonService.build(req, null));
        return generatePlanRawInternal(req, null, daySkeletonHints);
    }

    @CircuitBreaker(name = "llmProvider", fallbackMethod = "generatePlanRawPhasedFallback")
    @RateLimiter(name = "llmProvider", fallbackMethod = "generatePlanRawPhasedFallback")
    public String generatePlanRawPhased(CreatePlanReq req)
            throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        if (aiMockProps.isMockEnabled()) {
            String path = aiMockProps.resolvedMockRawPath();
            log.info("AI phased raw generation using mock sample path={}", path);
            return loadMockRaw(path);
        }

        DaySkeletonService.DaySkeletonBatch skeletonBatch = daySkeletonService.build(req, null);
        List<DaySkeletonService.DaySkeleton> skeletons = skeletonBatch == null || skeletonBatch.skeletons() == null
                ? List.of()
                : skeletonBatch.skeletons();
        if (skeletons.isEmpty()) {
            return generatePlanRaw(req);
        }

        try {
            List<CompletableFuture<PhasedDayResult>> futures = skeletons.stream()
                    .filter(Objects::nonNull)
                    .sorted(java.util.Comparator.comparingInt(DaySkeletonService.DaySkeleton::dayIndex))
                    .map(skeleton -> supplyAsyncPhasedDayTask(() -> generateSingleDayPlan(req, skeletonBatch, skeleton, Map.of())))
                    .toList();

            List<PhasedDayResult> dayResults = new java.util.ArrayList<>();
            for (CompletableFuture<PhasedDayResult> future : futures) {
                dayResults.add(future.join());
            }
            dayResults.sort(java.util.Comparator.comparingInt(PhasedDayResult::dayIndex));

            PlanDraftResponse merged = mergePhasedDayResults(req, dayResults);
            return mapper.writeValueAsString(merged);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Phased itinerary generation failed", cause);
        }
    }

    @CircuitBreaker(name = "llmProvider", fallbackMethod = "regeneratePlanRawFallback")
    @RateLimiter(name = "llmProvider", fallbackMethod = "regeneratePlanRawFallback")
    public String regeneratePlanRaw(CreatePlanReq req, String retryInstruction, @Nullable String daySkeletonHints)
            throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        return generatePlanRawInternal(req, retryInstruction, daySkeletonHints);
    }

    @CircuitBreaker(name = "llmProvider", fallbackMethod = "regeneratePlanDaysPhasedFallback")
    @RateLimiter(name = "llmProvider", fallbackMethod = "regeneratePlanDaysPhasedFallback")
    public PlanDraftResponse regeneratePlanDaysPhased(
            CreatePlanReq req,
            PlanDraftResponse baseDraft,
            List<Integer> targetDayIndexes,
            Map<Integer, String> retryInstructionsByDay
    ) throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        if (baseDraft == null || baseDraft.daysPlan() == null || baseDraft.daysPlan().isEmpty()
                || targetDayIndexes == null || targetDayIndexes.isEmpty()) {
            return baseDraft;
        }

        DaySkeletonService.DaySkeletonBatch skeletonBatch = daySkeletonService.build(req, baseDraft);
        Map<Integer, DaySkeletonService.DaySkeleton> skeletonsByDay = skeletonBatch == null || skeletonBatch.skeletons() == null
                ? Map.of()
                : skeletonBatch.skeletons().stream().collect(java.util.stream.Collectors.toMap(
                        DaySkeletonService.DaySkeleton::dayIndex,
                        skeleton -> skeleton,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        try {
            Set<Integer> targetDays = targetDayIndexes.stream()
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<String, String> usedPoiSet = usedNonMealPoisFromBaseDraft(baseDraft, targetDays);
            Map<Integer, PlanDraftResponse.DayPlan> replacements = new java.util.LinkedHashMap<>();
            for (Integer dayIndex : targetDays.stream().sorted().toList()) {
                PhasedDayResult result = regenerateSingleDayPlan(
                        req,
                        skeletonBatch,
                        skeletonsByDay.get(dayIndex),
                        dayIndex,
                        retryInstructionsByDay == null ? null : retryInstructionsByDay.get(dayIndex),
                        usedPoiSet
                );
                replacements.put(result.dayIndex(), result.dayPlan());
                rememberUsedNonMealPois(result.dayPlan(), usedPoiSet);
            }
            return mergeRegeneratedDays(baseDraft, replacements);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Phased day-level retry failed", cause);
        }
    }

    private <T> CompletableFuture<T> supplyAsyncPhasedDayTask(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            boolean acquired = false;
            try {
                phasedDayBulkhead.acquire();
                acquired = true;
                return task.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IllegalStateException("Interrupted while waiting for phased AI day slot", e));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally {
                if (acquired) {
                    phasedDayBulkhead.release();
                }
            }
        }, phasedDayExecutor);
    }

    private static int phasedDayConcurrencyLimit() {
        String raw = System.getProperty(PHASED_DAY_CONCURRENCY_FLAG, String.valueOf(DEFAULT_PHASED_DAY_CONCURRENCY));
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (RuntimeException ex) {
            return DEFAULT_PHASED_DAY_CONCURRENCY;
        }
    }

    private String generatePlanRawFallback(CreatePlanReq req, Throwable cause) {
        log.warn("LLM raw generation degraded city={} days={} model={} reason={}",
                req == null ? null : req.city(),
                req == null ? null : req.days(),
                req == null ? null : req.mainModel(),
                cause.toString());
        throw new IllegalStateException("AI itinerary generation is temporarily unavailable", cause);
    }

    private String generatePlanRawPhasedFallback(CreatePlanReq req, Throwable cause) {
        log.warn("LLM phased raw generation degraded city={} days={} model={} reason={} details={}",
                req == null ? null : req.city(),
                req == null ? null : req.days(),
                req == null ? null : req.mainModel(),
                cause.toString(),
                cause instanceof ApiException ? ((ApiException) cause).getMessage() : "none");
        throw new IllegalStateException("AI phased itinerary generation is temporarily unavailable", cause);
    }

    private String regeneratePlanRawFallback(CreatePlanReq req, String retryInstruction, @Nullable String daySkeletonHints, Throwable cause) {
        log.warn("LLM retry generation degraded city={} days={} model={} reason={}",
                req == null ? null : req.city(),
                req == null ? null : req.days(),
                req == null ? null : req.mainModel(),
                cause.toString());
        throw new IllegalStateException("AI itinerary retry generation is temporarily unavailable", cause);
    }

    private PlanDraftResponse regeneratePlanDaysPhasedFallback(
            CreatePlanReq req,
            PlanDraftResponse baseDraft,
            List<Integer> targetDayIndexes,
            Map<Integer, String> retryInstructionsByDay,
            Throwable cause
    ) {
        log.warn("LLM phased retry generation degraded city={} days={} model={} retryDays={} reason={}",
                req == null ? null : req.city(),
                req == null ? null : req.days(),
                req == null ? null : req.mainModel(),
                targetDayIndexes,
                cause.toString());
        throw new IllegalStateException("AI phased itinerary retry is temporarily unavailable", cause);
    }

    public CopyPolishResult polishPlanCopy(PlanDraftResponse verifiedDraft) {
        if (verifiedDraft == null) {
            log.info("Copy polish skipped: verified draft is null");
            return CopyPolishResult.empty("empty");
        }
        if (aiMockProps.isMockEnabled()) {
            String status = aiMockProps.isMockPolishEnabled() ? "mock-polish-fallback" : "mock-disabled";
            log.info("Copy polish skipped in AI mock mode: {}", status);
            return CopyPolishResult.empty(status);
        }
        if (!aiProps.isCopyPolishEnabled()) {
            log.info("Copy polish disabled by configuration");
            return CopyPolishResult.empty("disabled");
        }

        long startedAt = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PlanDraftResponse> future = executor.submit(() -> polishPlanCopyInternal(verifiedDraft));
            PlanDraftResponse polished = future.get(aiProps.resolvedCopyPolishTimeoutSeconds(), TimeUnit.SECONDS);
            if (polished == null) {
                log.info("Copy polish returned empty result in {} ms", System.currentTimeMillis() - startedAt);
                return CopyPolishResult.empty("empty");
            }
            log.info("Copy polish completed in {} ms", System.currentTimeMillis() - startedAt);
            return new CopyPolishResult(polished, "completed");
        } catch (TimeoutException e) {
            log.warn("Copy polish timed out after {} seconds", aiProps.resolvedCopyPolishTimeoutSeconds());
            return CopyPolishResult.empty("timeout");
        } catch (Exception e) {
            log.warn("Copy polish skipped after {} ms: {}", System.currentTimeMillis() - startedAt, e.getMessage());
            log.debug("Copy polish skipped details", e);
            return CopyPolishResult.empty("error");
        } finally {
            executor.shutdownNow();
        }
    }

    private PlanDraftResponse polishPlanCopyInternal(PlanDraftResponse verifiedDraft)
            throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        String system = """
                You rewrite travel itinerary copy after all places and times have already been verified.
                Return strict JSON matching this patch schema only:
                {
                  "overview": "...",
                  "days": [
                    {
                      "dayIndex": 1,
                      "theme": "...",
                      "morningNote": "...",
                      "afternoonNote": "...",
                      "eveningNote": "...",
                      "note": "...",
                      "hotel": {"reason": "...", "tip": "..."},
                      "stops": [{"index": 1, "reason": "...", "tip": "..."}]
                    }
                  ]
                }
                Do not return full itinerary fields.
                Copy polishing is controlled surface rewriting, not planning.
                Never add a new restaurant, attraction, neighborhood, transport mode, activity, opening detail, ticket detail, booking detail, or venue name that is not present in the provided context.
                Day notes may mention only stop names that already appear in that day's hotel or stops list.
                If referring to lunch or dinner, use the actual provided meal stop name, or say "the planned lunch stop" / "the planned dinner stop".
                Never replace a provided meal stop with another restaurant name.
                Do not invent opening hours, signature dishes, views, rooftop/inside/harbour-front claims, transport details, shuttle details, booking rules, or event/tour access.
                Do not use the words walkable, transit-friendly, transit friendly, tour, or tours in narrative fields unless the exact word appears in a verified place name.
                Use only the provided name, category, area, timeSlot, startTime/endTime, and verified address fields.
                The stops array is already in the final verified travel order. Day theme, morningNote, afternoonNote, eveningNote, and note must follow that exact order.
                Do not say "then continue with" a stop that appears earlier in the ordered stops list.
                Write like a concise travel app, not a travel essay.
                Improve clarity and rhythm, but keep the meaning conservative and grounded in the provided stop order.
                Keep each reason/tip short: preferably one clear sentence under 22 words.
                Prefer practical sequencing, area-fit, pacing, and visit-focus language over poetic metaphors.
                Avoid repeating safe fallback templates. Do not use "check current details", "book ahead", or "reserve in advance" unless the input already gives a specific reason.
                Avoid lyrical or abstract phrasing such as "stone then shade", "the city breathes", "soft cadence", "grounded warmth", "senses reset", or similar.
                Avoid generic templates such as "offers a convenient stop", "check details before you set out", or "book ahead" unless there is no better safe phrasing.
                Good abstract examples:
                - "This lunch break gives the day a slower reset before the afternoon gallery stop."
                - "Use this stop as the main pause of the day rather than rushing straight into the next museum."
                - "This outdoor stretch lightens the afternoon after a museum-heavy morning."
                - "The evening meal creates a clear finish after the day's cultural stops."
                Bad examples:
                - "The route moves from stone to shade as the city slowly breathes."
                - "This stop restores the senses before the evening's softer cadence."
                - "Try the signature seafood platter."
                - "Enjoy rooftop harbour views."
                - "It is open late and accepts walk-ins."
                - "Take the direct bus from the previous stop."
                """;
        String user = "Polish only the copy fields in this compact itinerary context:\n"
                + mapper.writeValueAsString(toCopyPolishContext(verifiedDraft));

        if ("gemini".equals(aiProps.resolvedCopyPolishProvider())) {
            String content = callGemini(system, user, aiProps.resolvedCopyPolishModel());
            CopyPolishPatch patch = mapper.readValue(stripJsonFence(content), CopyPolishPatch.class);
            return toPlanDraftPatch(verifiedDraft, patch);
        }

        Constants.baseHttpApiUrl = resolvedQwenBaseUrl();
        String key = aiProps.key();
        if (key == null || key.isBlank()) throw new IllegalStateException("API_KEY is not configured");
        key = key.trim();
        log.info("Qwen API key (copy polish)={}", key);

        Message sys = Message.builder().role(Role.SYSTEM.getValue()).content(system).build();
        Message usr = Message.builder().role(Role.USER.getValue()).content(user).build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(key)
                .model(aiProps.resolvedCopyPolishModel())
                .messages(List.of(sys, usr))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .maxTokens(4096)
                .build();

        GenerationResult result = new Generation().call(param);
        String content = result.getOutput().getChoices().getFirst().getMessage().getContent();
        CopyPolishPatch patch = mapper.readValue(stripJsonFence(content), CopyPolishPatch.class);
        return toPlanDraftPatch(verifiedDraft, patch);
    }

    private CopyPolishContext toCopyPolishContext(PlanDraftResponse draft) {
        List<CopyPolishDayContext> days = draft.daysPlan() == null ? List.of() : draft.daysPlan().stream()
                .map(day -> new CopyPolishDayContext(
                        day.dayIndex(),
                        day.theme(),
                        day.morningNote(),
                        day.afternoonNote(),
                        day.eveningNote(),
                        day.note(),
                        toCopyPolishStopContext(day.hotel(), 0),
                        day.stops() == null ? List.of() : indexedStopContexts(day.stops())
                ))
                .toList();
        return new CopyPolishContext(draft.city(), draft.overview(), days);
    }

    private List<CopyPolishStopContext> indexedStopContexts(List<PlanDraftResponse.Place> stops) {
        List<CopyPolishStopContext> contexts = new java.util.ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            contexts.add(toCopyPolishStopContext(stops.get(i), i + 1));
        }
        return contexts;
    }

    private CopyPolishStopContext toCopyPolishStopContext(PlanDraftResponse.Place stop, int index) {
        if (stop == null) {
            return null;
        }
        String area = join(", ", safe(stop.preferredArea()), safe(stop.suburb()), safe(stop.city()));
        return new CopyPolishStopContext(
                index,
                stop.name(),
                stop.category(),
                area,
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.reason(),
                stop.tip()
        );
    }

    private PlanDraftResponse toPlanDraftPatch(PlanDraftResponse base, CopyPolishPatch patch) {
        List<CopyPolishDayPatch> patchDays = patch == null || patch.days() == null ? List.of() : patch.days();
        List<PlanDraftResponse.DayPlan> days = base.daysPlan() == null ? List.of() : base.daysPlan().stream()
                .map(day -> toDayPatch(day, patchDays.stream()
                        .filter(candidate -> candidate.dayIndex() == day.dayIndex())
                        .findFirst()
                        .orElse(null)))
                .toList();
        return new PlanDraftResponse(
                null,
                null,
                base.days(),
                null,
                null,
                null,
                null,
                patch == null ? null : patch.overview(),
                days,
                null
        );
    }

    private PlanDraftResponse.DayPlan toDayPatch(PlanDraftResponse.DayPlan base, CopyPolishDayPatch patch) {
        List<CopyPolishStopPatch> stopPatches = patch == null || patch.stops() == null ? List.of() : patch.stops();
        List<PlanDraftResponse.Place> stops = new java.util.ArrayList<>();
        List<PlanDraftResponse.Place> baseStops = base.stops() == null ? List.of() : base.stops();
        for (int i = 0; i < baseStops.size(); i++) {
            int index = i + 1;
            CopyPolishStopPatch stopPatch = stopPatches.stream()
                    .filter(candidate -> candidate.index() == index)
                    .findFirst()
                    .orElse(null);
            stops.add(toPlacePatch(stopPatch));
        }
        return new PlanDraftResponse.DayPlan(
                base.dayIndex(),
                toPlacePatch(patch == null ? null : patch.hotel()),
                stops,
                patch == null ? null : patch.theme(),
                patch == null ? null : patch.morningNote(),
                patch == null ? null : patch.afternoonNote(),
                patch == null ? null : patch.eveningNote(),
                patch == null ? null : patch.note()
        );
    }

    private PlanDraftResponse.Place toPlacePatch(CopyPolishStopPatch patch) {
        if (patch == null) {
            return null;
        }
        return new PlanDraftResponse.Place(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                patch.reason(),
                patch.tip(),
                null, null, null, null, null, null
        );
    }

    private String stripJsonFence(String content) {
        return content == null ? "" : content.strip()
                .replaceAll("^```[a-zA-Z]*\\s*", "")
                .replaceAll("```\\s*$", "");
    }

    private String generatePlanRawInternal(CreatePlanReq req, @Nullable String retryInstruction, @Nullable String daySkeletonHints)
            throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        if (aiMockProps.isMockEnabled()) {
            String path = retryInstruction == null || retryInstruction.isBlank()
                    ? aiMockProps.resolvedMockRawPath()
                    : aiMockProps.resolvedMockRetryRawPath();
            log.info("AI raw generation using mock sample path={} retry={}", path, retryInstruction != null && !retryInstruction.isBlank());
            return loadMockRaw(path);
        }

        // ①（可留）如果你用国际站：
        Constants.baseHttpApiUrl = resolvedQwenBaseUrl();

        // ② 组装消息（用你之前发我的模板类）
        String system = TripPromptTemplate.system();
        String user = TripPromptTemplate.user(req, daySkeletonHints);
        if (retryInstruction == null || retryInstruction.isBlank()) {
            log.debug("Initial generation day skeleton seed: {}", daySkeletonHints);
        }
        if (retryInstruction != null && !retryInstruction.isBlank()) {
            user = user + "\n\nRetry correction:\n" + retryInstruction.trim();
        }

        return generateRawFromPrompt(req, system, user, retryInstruction != null && !retryInstruction.isBlank());
    }

    private String generateRawFromPrompt(CreatePlanReq req, String system, String user, boolean retryMode)
            throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
        long startedAt = System.currentTimeMillis();

        String mainProvider = resolvedMainProvider(req);
        String mainModel = resolvedMainModel(req);
        int maxTokens = resolvePlanMaxTokens(req, retryMode);

        if ("gemini".equals(mainProvider)) {
            try {
                String content = callGemini(system, user, mainModel, maxTokens);
                runtimeMetricsService.recordExternalPlanGenerate(System.currentTimeMillis() - startedAt, true);
                return content;
            } catch (RuntimeException e) {
                runtimeMetricsService.recordExternalPlanGenerate(System.currentTimeMillis() - startedAt, false);
                throw e;
            }
        }

        Constants.baseHttpApiUrl = resolvedQwenBaseUrl();
        String key = aiProps.key();
        if (key == null || key.isBlank()) throw new IllegalStateException("API_KEY is not configured");
        key = key.trim();
        log.info("Qwen API key (plan raw)={}", key);

        Message sys = Message.builder().role(Role.SYSTEM.getValue()).content(system).build();
        Message usr = Message.builder().role(Role.USER.getValue()).content(user).build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(key)
                .model(mainModel)
                .messages(List.of(sys, usr))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .maxTokens(maxTokens)
                .build();

        Generation gen = new Generation();
        try {
            GenerationResult result = gen.call(param);

        // ③ 解析为你定义的结构化对象
            runtimeMetricsService.recordExternalPlanGenerate(System.currentTimeMillis() - startedAt, true);
            return result.getOutput().getChoices().getFirst().getMessage().getContent();
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            runtimeMetricsService.recordExternalPlanGenerate(System.currentTimeMillis() - startedAt, false);
            throw e;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordExternalPlanGenerate(System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    private int resolvePlanMaxTokens(CreatePlanReq req, boolean retryMode) {
        if (!retryMode) {
            return PLAN_MAX_TOKENS;
        }
        int days = req == null ? 0 : Math.max(1, req.days());
        if (days >= 10) {
            return RETRY_MAX_TOKENS_XLONG;
        }
        if (days >= 7) {
            return RETRY_MAX_TOKENS_LONG;
        }
        if (days >= 4) {
            return RETRY_MAX_TOKENS_MEDIUM;
        }
        return RETRY_MAX_TOKENS_SHORT;
    }

    private PhasedDayResult generateSingleDayPlan(
            CreatePlanReq req,
            DaySkeletonService.DaySkeletonBatch skeletonBatch,
            DaySkeletonService.DaySkeleton skeleton,
            Map<String, String> usedPoiSet
    ) {
        try {
            String currentSkeleton = formatCurrentDaySkeleton(skeleton);
            String adjacentContext = adjacentDayContext(skeletonBatch, skeleton.dayIndex());
            String system = TripPromptTemplate.system();
            String user = TripPromptTemplate.dayUser(
                    req,
                    skeleton.dayIndex(),
                    req.days(),
                    currentSkeleton,
                    adjacentContext,
                    formatUsedPoiHints(usedPoiSet)
            );
            return generatePhasedDayResult(req, system, user, skeleton.dayIndex(), "initial");
        } catch (ApiException | NoApiKeyException | InputRequiredException | JsonProcessingException e) {
            throw new CompletionException(e);
        } catch (RuntimeException e) {
            throw new CompletionException(e);
        }
    }

    private PhasedDayResult regenerateSingleDayPlan(
            CreatePlanReq req,
            DaySkeletonService.DaySkeletonBatch skeletonBatch,
            @Nullable DaySkeletonService.DaySkeleton skeleton,
            int dayIndex,
            @Nullable String retryInstruction,
            Map<String, String> usedPoiSet
    ) {
        try {
            String currentSkeleton = formatCurrentDaySkeleton(resolveFallbackSkeleton(skeletonBatch, skeleton, dayIndex));
            String adjacentContext = adjacentDayContext(skeletonBatch, dayIndex);
            String system = TripPromptTemplate.system();
            String user = TripPromptTemplate.dayUser(
                    req,
                    dayIndex,
                    req.days(),
                    currentSkeleton,
                    adjacentContext,
                    formatUsedPoiHints(usedPoiSet)
            );
            if (retryInstruction != null && !retryInstruction.isBlank()) {
                user = user + "\n\nRetry correction for day " + dayIndex + ":\n" + retryInstruction.trim();
            }
            return generatePhasedDayResult(req, system, user, dayIndex, "retry");
        } catch (ApiException | NoApiKeyException | InputRequiredException | JsonProcessingException e) {
            throw new CompletionException(e);
        } catch (RuntimeException e) {
            throw new CompletionException(e);
        }
    }

    private PhasedDayResult generatePhasedDayResult(
            CreatePlanReq req,
            String system,
            String user,
            int dayIndex,
            String phaseLabel
    ) throws ApiException, NoApiKeyException, InputRequiredException, JsonProcessingException {
            String raw = generateRawFromPrompt(req, system, user, false);
        try {
            return parsePhasedDayResult(raw, dayIndex, phaseLabel);
        } catch (JsonProcessingException parseFailure) {
            log.warn("Phased day {} generation returned invalid JSON. city={} day={} error={} rawPreview={}",
                    phaseLabel,
                    req == null ? null : req.city(),
                    dayIndex,
                    parseFailure.getOriginalMessage(),
                    shortRawPreview(raw));
            String repairUser = user + "\n\nRetry correction for day " + dayIndex + ":\n"
                    + invalidJsonRetryInstructionForDay(dayIndex, parseFailure);
            String repairedRaw = generateRawFromPrompt(req, system, repairUser, true);
            return parsePhasedDayResult(repairedRaw, dayIndex, phaseLabel + "-json-repair");
        }
    }

    private PhasedDayResult parsePhasedDayResult(
            String raw,
            int dayIndex,
            String phaseLabel
    ) throws JsonProcessingException {
        PlanDraftResponse parsed = mapper.readValue(stripJsonFence(raw), PlanDraftResponse.class);
        PlanDraftResponse.DayPlan dayPlan = extractRequestedDayPlan(parsed, dayIndex);
        if (dayPlan == null) {
            throw new IllegalStateException("Phased day " + phaseLabel + " returned no matching dayIndex=" + dayIndex);
        }
        return new PhasedDayResult(
                dayIndex,
                copyDayPlanWithIndex(dayPlan, dayIndex),
                firstNonBlank(parsed.country(), dayPlan.hotel() == null ? null : dayPlan.hotel().country(), firstStopCountry(dayPlan)),
                firstNonBlank(parsed.currency(), null),
                firstNonBlank(parsed.title(), null),
                firstNonBlank(parsed.overview(), null)
        );
    }

    private String invalidJsonRetryInstructionForDay(int dayIndex, JsonProcessingException parseFailure) {
        String reason = parseFailure == null || parseFailure.getOriginalMessage() == null
                ? "malformed json"
                : parseFailure.getOriginalMessage();
        return "The previous response for day " + dayIndex + " was invalid JSON and could not be parsed (" + reason + "). "
                + "Return exactly one complete valid JSON object with one day in daysPlan. "
                + "Do not truncate output. Close every quote, bracket, and brace. "
                + "Ensure all string fields such as addressLine, preferredArea, reason, tip, theme, and note are properly quoted and escaped. "
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

    private PlanDraftResponse mergePhasedDayResults(CreatePlanReq req, List<PhasedDayResult> dayResults) {
        List<PlanDraftResponse.DayPlan> days = dayResults.stream()
                .map(PhasedDayResult::dayPlan)
                .toList();
        String country = dayResults.stream()
                .map(PhasedDayResult::country)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("Australia");
        String currency = dayResults.stream()
                .map(PhasedDayResult::currency)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("AUD");
        String title = dayResults.stream()
                .map(PhasedDayResult::title)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(buildFallbackTitle(req));
        String overview = dayResults.stream()
                .map(PhasedDayResult::overview)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(buildFallbackOverview(req));
        return new PlanDraftResponse(
                req.city(),
                country,
                req.days(),
                currency,
                req.party(),
                req.pace(),
                title,
                overview,
                days,
                null
        );
    }

    private PlanDraftResponse.DayPlan extractRequestedDayPlan(PlanDraftResponse parsed, int requestedDayIndex) {
        if (parsed == null || parsed.daysPlan() == null || parsed.daysPlan().isEmpty()) {
            return null;
        }
        for (PlanDraftResponse.DayPlan day : parsed.daysPlan()) {
            if (day != null && day.dayIndex() == requestedDayIndex) {
                return day;
            }
        }
        PlanDraftResponse.DayPlan firstDay = parsed.daysPlan().getFirst();
        return firstDay == null ? null : copyDayPlanWithIndex(firstDay, requestedDayIndex);
    }

    private PlanDraftResponse.DayPlan copyDayPlanWithIndex(PlanDraftResponse.DayPlan dayPlan, int dayIndex) {
        if (dayPlan == null) {
            return null;
        }
        return new PlanDraftResponse.DayPlan(
                dayIndex,
                dayPlan.hotel(),
                dayPlan.stops(),
                dayPlan.theme(),
                dayPlan.morningNote(),
                dayPlan.afternoonNote(),
                dayPlan.eveningNote(),
                dayPlan.note()
        );
    }

    private String formatCurrentDaySkeleton(DaySkeletonService.DaySkeleton skeleton) {
        if (skeleton == null) {
            return "none";
        }
        String issue = skeleton.capacityIssueCode() == null || skeleton.capacityIssueCode().isBlank()
                ? ""
                : ",capacityIssue=" + skeleton.capacityIssueCode();
        return "day-" + skeleton.dayIndex()
                + "{primaryArea=" + safeSkeletonValue(skeleton.primaryArea())
                + ",secondaryArea=" + safeSkeletonValue(skeleton.nearbySecondaryArea())
                + ",effectiveNonMeal=" + skeleton.effectiveMinNonMealStops() + "-" + skeleton.effectiveMaxNonMealStops()
                + issue
                + "}";
    }

    private DaySkeletonService.DaySkeleton resolveFallbackSkeleton(
            DaySkeletonService.DaySkeletonBatch batch,
            @Nullable DaySkeletonService.DaySkeleton skeleton,
            int dayIndex
    ) {
        if (skeleton != null) {
            return skeleton;
        }
        if (batch == null || batch.skeletons() == null) {
            return null;
        }
        return batch.skeletons().stream()
                .filter(candidate -> candidate != null && candidate.dayIndex() == dayIndex)
                .findFirst()
                .orElse(null);
    }

    private String adjacentDayContext(DaySkeletonService.DaySkeletonBatch batch, int dayIndex) {
        if (batch == null || batch.skeletons() == null || batch.skeletons().isEmpty()) {
            return "";
        }
        List<String> context = new java.util.ArrayList<>();
        for (DaySkeletonService.DaySkeleton skeleton : batch.skeletons()) {
            if (skeleton == null || Math.abs(skeleton.dayIndex() - dayIndex) > 1 || skeleton.dayIndex() == dayIndex) {
                continue;
            }
            context.add("day-" + skeleton.dayIndex()
                    + "{primaryArea=" + safeSkeletonValue(skeleton.primaryArea())
                    + ",effectiveNonMeal=" + skeleton.effectiveMinNonMealStops() + "-" + skeleton.effectiveMaxNonMealStops()
                    + "}");
        }
        return String.join("; ", context);
    }

    private Map<String, String> usedNonMealPoisFromBaseDraft(PlanDraftResponse draft, Set<Integer> excludedDayIndexes) {
        Map<String, String> usedPoiSet = new LinkedHashMap<>();
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return usedPoiSet;
        }
        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            if (day == null || (excludedDayIndexes != null && excludedDayIndexes.contains(day.dayIndex()))) {
                continue;
            }
            rememberUsedNonMealPois(day, usedPoiSet);
        }
        return usedPoiSet;
    }

    private void rememberUsedNonMealPois(@Nullable PlanDraftResponse.DayPlan day, Map<String, String> usedPoiSet) {
        if (day == null || day.stops() == null || day.stops().isEmpty() || usedPoiSet == null) {
            return;
        }
        for (PlanDraftResponse.Place stop : day.stops()) {
            if (!isTrackedNonMealPoi(stop)) {
                continue;
            }
            String key = normalizePoiName(stop.name());
            if (key.isBlank()) {
                continue;
            }
            usedPoiSet.putIfAbsent(key, stop.name().trim());
        }
    }

    private boolean isTrackedNonMealPoi(@Nullable PlanDraftResponse.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizePoiName(stop.category());
        if (category.contains("restaurant") || category.contains("food") || category.contains("cafe") || category.contains("dining")) {
            return false;
        }
        String timeSlot = normalizePoiName(stop.timeSlot());
        return !timeSlot.equals("lunch") && !timeSlot.equals("dinner");
    }

    private String normalizePoiName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private String formatUsedPoiHints(Map<String, String> usedPoiSet) {
        if (usedPoiSet == null || usedPoiSet.isEmpty()) {
            return "none";
        }
        return usedPoiSet.values().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(20)
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private String safeSkeletonValue(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstStopCountry(PlanDraftResponse.DayPlan dayPlan) {
        if (dayPlan == null || dayPlan.stops() == null || dayPlan.stops().isEmpty()) {
            return "";
        }
        for (PlanDraftResponse.Place stop : dayPlan.stops()) {
            if (stop != null && stop.country() != null && !stop.country().isBlank()) {
                return stop.country();
            }
        }
        return "";
    }

    private String buildFallbackTitle(CreatePlanReq req) {
        int days = req == null ? 0 : Math.max(1, req.days());
        String city = req == null || req.city() == null || req.city().isBlank() ? "Trip" : req.city().trim();
        return city + " " + days + "-Day Itinerary";
    }

    private String buildFallbackOverview(CreatePlanReq req) {
        int days = req == null ? 0 : Math.max(1, req.days());
        String city = req == null || req.city() == null || req.city().isBlank() ? "the city" : req.city().trim();
        return "A practical " + days + "-day itinerary in " + city + ".";
    }

    private PlanDraftResponse mergeRegeneratedDays(
            PlanDraftResponse baseDraft,
            Map<Integer, PlanDraftResponse.DayPlan> replacements
    ) {
        if (baseDraft == null || baseDraft.daysPlan() == null || baseDraft.daysPlan().isEmpty() || replacements == null || replacements.isEmpty()) {
            return baseDraft;
        }
        List<PlanDraftResponse.DayPlan> mergedDays = baseDraft.daysPlan().stream()
                .map(day -> {
                    if (day == null) {
                        return null;
                    }
                    PlanDraftResponse.DayPlan replacement = replacements.get(day.dayIndex());
                    return replacement == null ? day : replacement;
                })
                .toList();
        return new PlanDraftResponse(
                baseDraft.city(),
                baseDraft.country(),
                baseDraft.days(),
                baseDraft.currency(),
                baseDraft.party(),
                baseDraft.pace(),
                baseDraft.title(),
                baseDraft.overview(),
                mergedDays,
                baseDraft.copyPolishStatus()
        );
    }

    private record PhasedDayResult(
            int dayIndex,
            PlanDraftResponse.DayPlan dayPlan,
            String country,
            String currency,
            String title,
            String overview
    ) {}

    private String resolvedMainProvider(CreatePlanReq req) {
        String model = normalizedMainModel(req);
        if (model.startsWith("gemini")) {
            return "gemini";
        }
        if (model.startsWith("qwen")) {
            return "qwen";
        }
        return aiProps.resolvedProvider();
    }

    private String resolvedMainModel(CreatePlanReq req) {
        String model = normalizedMainModel(req);
        if (!model.isBlank()) {
            return model;
        }
        return aiProps.resolvedModel();
    }

    private String resolvedQwenBaseUrl() {
        String configured = aiProps.baseUrl();
        return (configured == null || configured.isBlank())
                ? "https://dashscope-intl.aliyuncs.com/api/v1"
                : configured.trim();
    }

    private String normalizedMainModel(CreatePlanReq req) {
        if (req == null || req.mainModel() == null) {
            return "";
        }
        String model = req.mainModel().trim().toLowerCase();
        if (model.startsWith("qwen-plus-") || model.startsWith("qwen-max-")) {
            return model;
        }
        return switch (model) {
            case "qwen-max", "qwen-plus", "gemini-2.5-flash" -> model;
            default -> "";
        };
    }

    private String loadMockRaw(String classpathLocation) {
        String normalized = classpathLocation == null ? "" : classpathLocation.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        try {
            ClassPathResource resource = new ClassPathResource(normalized);
            if (!resource.exists()) {
                throw new IllegalStateException("AI mock sample not found on classpath: " + classpathLocation);
            }
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read AI mock sample: " + classpathLocation, e);
        }
    }

    private String callGemini(String system, String user, String model) {
        return callGemini(system, user, model, PLAN_MAX_TOKENS);
    }

    private String callGemini(String system, String user, String model, int maxOutputTokens) {
        String key = aiProps.resolvedGeminiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }
        String resolvedModel = model == null || model.isBlank() ? "gemini-2.5-flash" : model.trim();
        String encodedModel = URLEncoder.encode(resolvedModel, StandardCharsets.UTF_8);
        String endpoint = aiProps.resolvedGeminiBaseUrl()
                + "/models/" + encodedModel + ":generateContent?key="
                + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8);

        try {
            Map<String, Object> body = Map.of(
                    "systemInstruction", Map.of(
                            "parts", List.of(Map.of("text", system == null ? "" : system))
                    ),
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", user == null ? "" : user))
                    )),
                    "generationConfig", Map.of(
                            "temperature", 0.25,
                            "responseMimeType", "application/json",
                            "maxOutputTokens", maxOutputTokens
                    )
            );

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gemini API request failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return extractGeminiText(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Gemini API request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini API request interrupted", e);
        }
    }

    private String extractGeminiText(String body) throws JsonProcessingException {
        var root = mapper.readTree(body);
        var candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini API returned no candidates");
        }
        var parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("Gemini API returned no text parts");
        }
        StringBuilder text = new StringBuilder();
        for (var part : parts) {
            String value = part.path("text").asText("");
            if (!value.isBlank()) {
                text.append(value);
            }
        }
        if (text.isEmpty()) {
            throw new IllegalStateException("Gemini API returned empty text");
        }
        return text.toString();
    }

    private static String currency(String code) { return (code == null || code.isBlank()) ? "AUD" : code; }
    public String render(PlanDraftResponse plan, Integer budget) {
        String city = nz(plan.city());
        int days = plan.days();
        int nights = Math.max(0, days - 1);
        String cur = currency(plan.currency());

        Integer adults = plan.party() != null ? plan.party().adults() : null;
        Integer kids   = plan.party() != null ? plan.party().kids()   : null;
        String pace = nz(plan.pace(), "normal");

        String title = "%s%d天%d晚%s旅行行程".formatted(city, days, nights,
                (adults != null && adults == 2 && (kids == null || kids == 0)) ? "双人" : "");

        StringBuilder sb = new StringBuilder();
        sb.append(title);
        if (budget != null) sb.append("（总预算(general budget)：").append(budget).append(" ").append(cur).append("）");
        sb.append("\n\n");

        sb.append("行程概览(Itinerary Overview)\n");
        sb.append("旅行城市(Traveling City)：").append(city).append("\n");
        sb.append("旅行天数(Number of travel days)：").append(days).append("天(days)").append("\n");
        if (adults != null || kids != null) {
            sb.append("人数(number of people)：");
            if (adults != null) sb.append(adults).append("位成人(adult)");
            if (kids != null && kids > 0) sb.append(" + ").append(kids).append("位儿童(child)");
            sb.append("\n");
        }
        if (budget != null) sb.append("总预算(general budget)：").append(budget).append(" ").append(cur).append("\n");
        sb.append("行程节奏(Travel schedule)：").append(translatePace(pace)).append("\n\n");

        // 每天
        for (var day : plan.daysPlan()) {
            sb.append("Day ").append(day.dayIndex()).append("：");
            sb.append(nz(day.note(), "行程安排(scheduling)")).append("\n");

            if (day.hotel() != null) {
                var h = day.hotel();
                sb.append("住宿(hotel)：").append(nz(h.name())).append("\n");
                String addr = formatAddress(h.addressLine(), h.suburb(), h.city(), h.state(), h.postcode(), h.country());
                if (!addr.isBlank()) sb.append("地址(address)：").append(addr).append("\n");
                if (notBlank(h.url())) sb.append("URL：").append(h.url()).append("\n");
            }

            if (day.stops() != null && !day.stops().isEmpty()) {
                sb.append("行程点(Tour stop)：\n");
                int i = 1;
                for (var p : day.stops()) {
                    sb.append(i++).append("）").append(nz(p.name()));
                    if (p.stayMinutes() != null) sb.append("（建议(suggest) ").append(p.stayMinutes()).append(" 分钟）");
                    sb.append("\n");
                    String addr = formatAddress(p.addressLine(), p.suburb(), p.city(), p.state(), p.postcode(), p.country());
                    if (!addr.isBlank()) sb.append("地址(address)：").append(addr).append("\n");
                    if (notBlank(p.url())) sb.append("URL：").append(p.url()).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String nz(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(sep);
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static String translatePace(String pace) {
        return switch (pace.toLowerCase()) {
            case "relax", "relaxed" -> "舒缓";
            case "rush", "fast", "fast-pace" -> "紧凑";
            default -> "适中";
        };
    }
    private static String formatAddress(String addressLine, String suburb, String city, String state, String postcode, String country) {
        // 去重逻辑：如果 addressLine 已包含 suburb/city，就不再重复；suburb 与 city 相同只保留一个
        String al = safe(addressLine);
        String sub = safe(suburb);
        String cty = safe(city);
        if (al != null && sub != null && !al.isEmpty() && !sub.isEmpty() && al.toLowerCase().contains(sub.toLowerCase()))
            sub = "";
        if (al != null && cty != null && !al.isEmpty() && !cty.isEmpty() && al.toLowerCase().contains(cty.toLowerCase()))
            cty = "";
        if (sub != null && cty != null && !sub.isEmpty() && !cty.isEmpty() && sub.equalsIgnoreCase(cty)) cty = "";

        String part2 = join(", ", sub, cty);
        String part3 = join(" ", safe(state), safe(postcode));
        return join(", ", al, part2, part3, safe(country));
    }

    @Transactional
    public long saveDraftPlan(Long userId, PlanDraftResponse draft, @Nullable Integer budget, @Nullable String title,
                              @Nullable List<String> styles, @Nullable String requestedPace, @Nullable String departureDate) {
        if (userId == null) throw new IllegalArgumentException("userId 为空，无法落库");

        TripPlan plan = new TripPlan();
        plan.setUserId(userId);
        plan.setTitle(title);
        plan.setCity(draft.city());
        plan.setDays(draft.days());
        plan.setBudgetCents(budget == null ? null : budget * 100);
        plan.setPartyAdults(draft.party() != null ? Optional.ofNullable(draft.party().adults()).orElse(1) : 1);
        plan.setPartyKids(draft.party() != null ? Optional.ofNullable(draft.party().kids()).orElse(0) : 0);
        plan.setPace(normalizePace(requestedPace, draft.pace()));
        plan.setDepartureDate(departureDate);
        planMapper.insertPlan(plan);

        persistStyles(plan.getId(), styles);

        for (var day : draft.daysPlan()) {
            TripDay dayRow = new TripDay();
            dayRow.setPlanId(plan.getId());
            dayRow.setDayIndex(day.dayIndex());
            dayRow.setHotelPlaceId(null); // 暂不绑定酒店 place，之后 geocode/resolve 再更新
            dayRow.setNote(day.note());
            dayRow.setHotelReason(day.hotel() != null ? day.hotel().reason() : null);
            dayRow.setHotelTip(day.hotel() != null ? day.hotel().tip() : null);
            planMapper.insertDay(dayRow);

            // 先把酒店“按文本”录入 place（经纬度可空）
            if (day.hotel() != null) {
                Long hotelId = upsertPlaceFromModel(day.hotel());
                planMapper.updateDayHotel(dayRow.getId(), hotelId);
            }

            // stops
            if (day.stops() != null) {
                int seq = 1;
                for (var s : day.stops()) {
                    Long placeId = upsertPlaceFromModel(s); // 先插或复用
                    TripDayStop stopRow = new TripDayStop();
                    stopRow.setDayId(dayRow.getId());
                    stopRow.setSeq(seq++);
                    stopRow.setPlaceId(placeId);
                    stopRow.setDwellMinutes(Optional.ofNullable(s.stayMinutes()).orElse(60));
                    stopRow.setCategory(s.category());
                    stopRow.setTimeSlot(s.timeSlot());
                    stopRow.setStartTime(s.startTime());
                    stopRow.setEndTime(s.endTime());
                    stopRow.setReason(s.reason());
                    stopRow.setTip(s.tip());
                    stopRow.setNote(null);
                    planMapper.insertDayStop(stopRow);
                }
            }
        }
        return plan.getId();
    }
    private Long upsertPlaceFromModel(PlanDraftResponse.Place p) {
        String fullAddress = buildFullAddress(p);
        Place row = new Place();
        row.setName(p.name());
        row.setAddress(fullAddress);
        row.setCity(p.city());
        row.setDistrict(p.suburb());
        row.setCountry(p.country());
        row.setLatitude(null);  // 暂无
        row.setLongitude(null); // 暂无
        row.setSource("USER");
        row.setWebsiteUri(p.websiteUri());
        row.setGoogleMapsUri(p.googleMapsUri());
        row.setBusinessStatus(p.businessStatus());
        String ext = (p.url() != null && !p.url().isBlank())
                ? p.url().trim()
                : stableFingerprint(p); // 见下方方法
        row.setExternalRef(ext);

        Long exist = placeMapper.findExistingId(row);
        if (exist != null) return exist;

        placeMapper.upsert(row);
        return row.getId();
    }

    private void persistStyles(long planId, @Nullable List<String> styles) {
        if (styles == null || styles.isEmpty()) return;

        for (String code : styles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList()) {
            Integer styleId = planMapper.selectStyleIdByCode(code);
            if (styleId != null) {
                planMapper.insertPlanStyle(planId, styleId);
            } else {
                log.warn("Unknown style code ignored when saving plan: {}", code);
            }
        }
    }

    private void ensureStyleTags() {
        jdbc.update("""
                INSERT IGNORE INTO style_tag (code, name_zh) VALUES
                    ('nature', 'Nature'),
                    ('culture', 'Culture'),
                    ('market_shopping', 'Market / Shopping'),
                    ('theme_park', 'Theme Park')
                """);
    }

    private String normalizePace(@Nullable String requestedPace, @Nullable String draftPace) {
        String pace = Optional.ofNullable(requestedPace)
                .filter(s -> !s.isBlank())
                .orElse(draftPace);
        if (pace == null || pace.isBlank()) return "normal";
        String normalized = pace.trim().toLowerCase().replace("_", "-").replace(" ", "-");
        return switch (normalized) {
            case "relax", "relaxed", "slow" -> "relaxed";
            case "rush", "fast", "fast-pace", "fastpaced", "intense" -> "rush";
            case "moderate", "normal", "medium" -> "normal";
            default -> "normal";
        };
    }

    private static String buildFullAddress(PlanDraftResponse.Place p) {
        String locality = join(", ", safe(p.suburb()), safe(p.city()));
        String region = join(" ", safe(p.state()), safe(p.postcode()));
        return join(", ", safe(p.addressLine()), locality, region, safe(p.country()));
    }

    private static String stableFingerprint(PlanDraftResponse.Place p) {
        String key = (p.name() + "|" + p.addressLine() + "|" + p.suburb() + "|" +
                p.city() + "|" + p.state() + "|" + p.postcode() + "|" + p.country()).toLowerCase();
        // 简单 hash；如你有 commons-codec 可用 DigestUtils.sha256Hex(key)
        return "FP#" + Integer.toHexString(key.hashCode());
    }

    public record CopyPolishResult(PlanDraftResponse draft, String status) {
        public static CopyPolishResult empty(String status) {
            return new CopyPolishResult(null, status);
        }

        public boolean completed() {
            return draft != null && "completed".equals(status);
        }
    }

    private record CopyPolishContext(String city, String overview, List<CopyPolishDayContext> days) {}

    private record CopyPolishDayContext(
            int dayIndex,
            String theme,
            String morningNote,
            String afternoonNote,
            String eveningNote,
            String note,
            CopyPolishStopContext hotel,
            List<CopyPolishStopContext> stops
    ) {}

    private record CopyPolishStopContext(
            int index,
            String name,
            String category,
            String area,
            String timeSlot,
            String startTime,
            String endTime,
            String reason,
            String tip
    ) {}

    private record CopyPolishPatch(String overview, List<CopyPolishDayPatch> days) {}

    private record CopyPolishDayPatch(
            int dayIndex,
            String theme,
            String morningNote,
            String afternoonNote,
            String eveningNote,
            String note,
            CopyPolishStopPatch hotel,
            List<CopyPolishStopPatch> stops
    ) {}

    private record CopyPolishStopPatch(int index, String reason, String tip) {}
}
