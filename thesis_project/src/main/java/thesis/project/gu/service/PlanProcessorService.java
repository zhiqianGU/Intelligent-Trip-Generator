package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.client.GooglePlacesClient;
import thesis.project.gu.model.StopCoordinate;
import thesis.project.gu.model.RouteRecommendationContext;
import thesis.project.gu.model.ModeSummary;
import thesis.project.gu.model.RouteChoice;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.response.PlanDraftResponse;
import thesis.project.gu.response.PlanDraftResponse.DayPlan;
import thesis.project.gu.response.PlanDraftResponse.Place;
import thesis.project.gu.service.MapService;
import thesis.project.gu.service.TripAiService;
import thesis.project.gu.service.CacheSerive;
import thesis.project.gu.service.HotelVerificationService;
import thesis.project.gu.service.RestaurantVerificationService;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PlanProcessorService {
    private static final Logger log = LoggerFactory.getLogger(PlanProcessorService.class);
    private static final int MAX_PLAN_RETRY_ATTEMPTS = 1;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY_START_MINUTES = 9 * 60;
    private static final int DEFAULT_STAY_MINUTES = 60;
    private static final Pattern STOP_ISSUE_PATTERN = Pattern.compile("^day-(\\d+)-stop-(\\d+)-(.+)$");
    public static final int LUNCH_EARLIEST_START_MINUTES = 11 * 60 + 15;
    private static final int LUNCH_PREFERRED_EARLIEST_START_MINUTES = 11 * 60 + 30;
    public static final int LUNCH_LATEST_START_MINUTES = 13 * 60;
    private static final int LUNCH_LATEST_ROUTE_AWARE_START_MINUTES = 14 * 60;
    private static final int THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES = 14 * 60 + 30;
    private static final int DINNER_PREFERRED_EARLIEST_START_MINUTES = 18 * 60;
    private static final int DINNER_PREFERRED_LATEST_START_MINUTES = 19 * 60 + 30;
    public static final int THEME_PARK_DAY_DINNER_LATEST_START_MINUTES = 20 * 60 + 30;
    private static final double THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS = 150_000D;
    private static final int THEME_PARK_AFTERNOON_CONTINUATION_MINUTES = 60;
    private static final int THEME_PARK_CONTINUATION_MAX_EXTENSION_MINUTES = 150;
    private static final int THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES = 120;
    private static final int CULTURAL_POI_LATEST_END_MINUTES = 17 * 60;

    private final TripAiService aiService;
    private final CacheSerive cacheSerive;
    private final ObjectMapper objectMapper;
    private final HotelVerificationService hotelVerificationService;
    private final RestaurantVerificationService restaurantVerificationService;
    private final MapService mapService;
    private final GooglePlacesClient googlePlacesClient;

    public PlanProcessorService(
            TripAiService aiService,
            CacheSerive cacheSerive,
            ObjectMapper objectMapper,
            HotelVerificationService hotelVerificationService,
            RestaurantVerificationService restaurantVerificationService,
            MapService mapService,
            GooglePlacesClient googlePlacesClient
    ) {
        this.aiService = aiService;
        this.cacheSerive = cacheSerive;
        this.objectMapper = objectMapper;
        this.hotelVerificationService = hotelVerificationService;
        this.restaurantVerificationService = restaurantVerificationService;
        this.mapService = mapService;
        this.googlePlacesClient = googlePlacesClient;
    }

    public PlanDraftResponse processDraft(CreatePlanReq req, String raw, boolean redisHit, long aiGenerationMs) throws Exception {
        long totalStartedAt = System.currentTimeMillis() - Math.max(0, aiGenerationMs);
        StringBuilder stageSummary = new StringBuilder();
        StringBuilder timingSummary = new StringBuilder();
        appendStageTiming(timingSummary, "initial/ai-generate", aiGenerationMs);

        ProcessAttemptResult initialAttempt = processAttempt(req, raw, "initial", stageSummary, timingSummary);
        return validateAndRetry(req, raw, redisHit, totalStartedAt, stageSummary, timingSummary, initialAttempt);
    }

    private PlanDraftResponse validateAndRetry(
            CreatePlanReq req,
            String raw,
            boolean redisHit,
            long totalStartedAt,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            ProcessAttemptResult initialAttempt
    ) throws Exception {
        PlanDraftResponse draft = initialAttempt.draft();
        List<String> validationIssues = initialAttempt.validationIssues();

        if (validationIssues.isEmpty()) {
            return finishSuccessfulAttempt(draft, timingSummary, stageSummary, totalStartedAt, "initial/copy-polish");
        }

        log.warn("Initial generated itinerary failed validation. req={}, issues={}, maxRetryAttempts={}, raw={}",
                req, validationIssues, MAX_PLAN_RETRY_ATTEMPTS, raw);

        if (redisHit) {
            cacheSerive.evictAiPlanRaw(req);
        }

        long stageStartedAt = System.currentTimeMillis();
        String retryRaw = aiService.regeneratePlanRaw(req, retryInstruction(req, validationIssues));
        appendStageTiming(timingSummary, "retry-1/ai-regenerate", System.currentTimeMillis() - stageStartedAt);

        ProcessAttemptResult retryAttempt = processAttempt(req, retryRaw, "retry-1", stageSummary, timingSummary);
        PlanDraftResponse retried = retryAttempt.draft();
        List<String> retryValidationIssues = retryAttempt.validationIssues();

        if (retryValidationIssues.isEmpty()) {
            return finishSuccessfulAttempt(retried, timingSummary, stageSummary, totalStartedAt, "retry-1/copy-polish");
        }

        PlanDraftResponse relaxedFallback = relaxedPaceFallbackIfValid(retried, req, retryValidationIssues);
        if (relaxedFallback != null) {
            log.warn("Retry itinerary accepted with relaxed pace fallback. req={}, originalIssues={}", req, retryValidationIssues);
            return finishSuccessfulAttempt(relaxedFallback, timingSummary, stageSummary, totalStartedAt, "retry-1/copy-polish");
        }

        logPlanStageSummary(stageSummary);
        appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
        logPlanStageTimingSummary(timingSummary);
        log.error("Retried generated itinerary failed validation. issues={}, retryRaw={}", retryValidationIssues, retryRaw);
        throw thesis.project.gu.exception.ErrorCode.INTERNAL_ERROR.ex(
                "Retried generated itinerary failed validation: " + retryValidationIssues
        );
    }

    private PlanDraftResponse finishSuccessfulAttempt(
            PlanDraftResponse draft,
            StringBuilder timingSummary,
            StringBuilder stageSummary,
            long totalStartedAt,
            String copyPolishTimingLabel
    ) {
        logPlanStageSummary(stageSummary);
        PlanDraftResponse polished = polishCopySafely(draft, timingSummary, copyPolishTimingLabel);
        appendStageTiming(timingSummary, "total", System.currentTimeMillis() - totalStartedAt);
        logPlanStageTimingSummary(timingSummary);
        return polished;
    }

    private ProcessAttemptResult processAttempt(
            CreatePlanReq req,
            String raw,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary
    ) throws Exception {
        ParseNormalizeResult parsed = parseAndNormalize(raw, attemptLabel, stageSummary, timingSummary);
        EntityVerificationResult verified = verifyAndRepairEntities(parsed.draft(), attemptLabel, stageSummary, timingSummary);
        PlanDraftResponse draft = verified.draft();
        List<String> validationIssues = verified.validationIssues();

        draft = applySemanticPruning(draft, req, attemptLabel, stageSummary, timingSummary);

        long stageStartedAt = System.currentTimeMillis();
        draft = restaurantVerificationService.ensureRequiredMeals(draft);
        draft = restaurantVerificationService.verifyAndNormalize(draft).draft();
        appendStageTiming(timingSummary, attemptLabel + "/ensure-required-meals", System.currentTimeMillis() - stageStartedAt);

        draft = applyThemeParkGovernance(draft, req, attemptLabel, stageSummary, timingSummary);

        draft = applyRouteAwareScheduling(draft, attemptLabel, stageSummary, timingSummary);

        draft = applyPostRouteRepair(draft, req, attemptLabel, stageSummary, timingSummary);

        stageStartedAt = System.currentTimeMillis();
        validationIssues.addAll(validateDraft(draft, req));
        appendStageTiming(timingSummary, attemptLabel + "/validate", System.currentTimeMillis() - stageStartedAt);

        return new ProcessAttemptResult(draft, validationIssues);
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
        draft = pruneFlexibleFoodStops(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-flexible-prune", draft);
        draft = pruneUnselectedShoppingStops(draft, req);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-shopping-prune", draft);
        draft = pruneAreaInconsistentFlexibleStops(draft, req);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-area-prune", draft);
        draft = pruneExcessNonMealStops(draft, req);
        logPlanStageCounts(stageSummary, attemptLabel, "post-route-excess-prune", draft);
        draft = clampOversizedGaps(draft);
        logPlanStageCounts(stageSummary, attemptLabel, "post-gap-clamp", draft);
        draft = rewriteDayNarratives(draft);
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

        stageStartedAt = System.currentTimeMillis();
        draft = repairMealStops(draft, initialVerification.issues());
        appendStageTiming(timingSummary, attemptLabel + "/meal-repair-1", System.currentTimeMillis() - stageStartedAt);

        stageStartedAt = System.currentTimeMillis();
        var finalVerification = restaurantVerificationService.verifyAndNormalize(draft);
        draft = finalVerification.draft();
        appendStageTiming(timingSummary, attemptLabel + "/meal-verify-2", System.currentTimeMillis() - stageStartedAt);

        stageStartedAt = System.currentTimeMillis();
        draft = repairMealStops(draft, finalVerification.issues());
        appendStageTiming(timingSummary, attemptLabel + "/meal-repair-2", System.currentTimeMillis() - stageStartedAt);

        stageStartedAt = System.currentTimeMillis();
        var settledVerification = restaurantVerificationService.verifyAndNormalize(draft);
        draft = settledVerification.draft();
        appendStageTiming(timingSummary, attemptLabel + "/meal-verify-3", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "verified-meals-hotels", draft);

        validationIssues.addAll(settledVerification.issues());
        return new EntityVerificationResult(draft, validationIssues);
    }

    private record ProcessAttemptResult(PlanDraftResponse draft, List<String> validationIssues) {
    }

    private record ParseNormalizeResult(PlanDraftResponse draft) {
    }

    private record EntityVerificationResult(PlanDraftResponse draft, List<String> validationIssues) {
    }

    public PlanDraftResponse normalizeDraftSchedule(PlanDraftResponse draft) {
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
        List<DayPlan> days = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            days.add(normalizeDayScheduleWithRouteDurations(day, ctx));
        }
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
    }

    private RouteRecommendationContext routeSchedulingContext(PlanDraftResponse draft) {
        int kids = draft == null || draft.party() == null || draft.party().kids() == null ? 0 : draft.party().kids();
        return new RouteRecommendationContext(kids > 0, null, null);
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
        return switch (normalizeSlot(pace)) {
            case "relaxed" -> 2;
            case "rush", "fast" -> 4;
            default -> 3;
        };
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
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return List.of("missing-days");
        }

        List<String> issues = new ArrayList<>();
        issues.addAll(validateRequestedDayCount(draft, req));
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        boolean hasThemeParkDay = draft.daysPlan().stream()
                .anyMatch(day -> day.stops() != null && day.stops().stream().anyMatch(this::isThemeParkLikeStop));
        
        for (DayPlan day : draft.daysPlan()) {
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            boolean themeParkDay = stops.stream().anyMatch(this::isThemeParkLikeStop);
            int dayMinNonMealStops = hasThemeParkDay ? Math.min(minNonMealStops, 2) : minNonMealStops;
            if (countNonMealStops(stops) < dayMinNonMealStops && stops.stream().noneMatch(this::isThemeParkLikeStop)) {
                addValidationIssue(issues, "day-" + day.dayIndex() + "-too-few-non-meal-stops", day.dayIndex(), -1, null, null,
                        "nonMeal=" + countNonMealStops(stops) + " min=" + dayMinNonMealStops);
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
                        : LUNCH_LATEST_ROUTE_AWARE_START_MINUTES;
                if (hasMealSlot(stop, "lunch") && (start < LUNCH_EARLIEST_START_MINUTES || start > latestLunchStart)) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-lunch-time-invalid", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " allowed=" + formatMinutes(LUNCH_EARLIEST_START_MINUTES) + "-" + formatMinutes(latestLunchStart));
                }
                if (hasMealSlot(stop, "dinner") && start < DINNER_EARLIEST_START_MINUTES) {
                    addValidationIssue(issues, "day-" + day.dayIndex() + "-stop-" + (i + 1) + "-dinner-too-early", day.dayIndex(), i + 1, stop, null,
                            "start=" + stop.startTime() + " earliest=" + formatMinutes(DINNER_EARLIEST_START_MINUTES));
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
        issues.addAll(validateSelectedStyleCoverage(draft, req));
        return issues;
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

    private String retryInstruction(CreatePlanReq req, List<String> validationIssues) {
        int requestedDays = req == null ? 0 : req.days();
        String dayInstruction = requestedDays > 0
                ? " Please return exactly " + requestedDays + " days: days=" + requestedDays + " and daysPlan length=" + requestedDays + "."
                : "";
        String issueInstruction = validationIssues == null || validationIssues.isEmpty()
                ? ""
                : " Fix these validation issues: " + String.join(", ", validationIssues) + ".";
        return "Please generate a valid itinerary." + dayInstruction + issueInstruction;
    }

    private PlanDraftResponse relaxedPaceFallbackIfValid(
            PlanDraftResponse draft,
            CreatePlanReq req,
            List<String> validationIssues
    ) {
        if (draft == null || validationIssues == null || validationIssues.isEmpty()) {
            return null;
        }
        if (!"normal".equals(normalizeSlot(draft.pace())) && (req == null || !"normal".equals(normalizeSlot(req.pace())))) {
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

    public PlanDraftResponse polishCopySafely(PlanDraftResponse verifiedDraft, StringBuilder timingSummary, String timingLabel) {
        long startedAt = System.currentTimeMillis();
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

    public PlanDraftResponse verifyThemeParkStopsWithPlaces(PlanDraftResponse draft) {
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
                    .filter(place -> isCoordinatePlausibleForCity(new StopCoordinate(place.lat(), place.lng()), stop.city()))
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
                    .filter(place -> isCoordinatePlausibleForCity(new StopCoordinate(place.lat(), place.lng()), stop.city()))
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
        String expectedName = normalizeSearchText(stop.name());
        String expectedAddress = normalizeSearchText(stop.addressLine());
        String candidateName = normalizeSearchText(candidate.name());
        String candidateText = normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        int score = commonSignificantTokenCount(expectedName, candidateText) * 80;
        score += commonSignificantTokenCount(expectedAddress, candidateText) * 20;
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
        String candidateText = normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
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

    private int commonSignificantTokenCount(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String token : left.split("\\s+")) {
            if (token.length() < 4 || isLowSignalPoiToken(token)) {
                continue;
            }
            if (right.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private boolean isLowSignalPoiToken(String token) {
        return "the".equals(token)
                || "and".equals(token)
                || "australia".equals(token)
                || "sydney".equals(token)
                || "melbourne".equals(token)
                || "brisbane".equals(token)
                || "victoria".equals(token)
                || "south".equals(token)
                || "north".equals(token);
    }

    private String normalizeSearchText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    public PlanDraftResponse pruneOutOfRangeThemeParkStops(PlanDraftResponse draft) {
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

    public PlanDraftResponse pruneThemeParkDayTrips(PlanDraftResponse draft) {
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
        String p = normalizeSlot(pace);
        return "relaxed".equals(p) || "normal".equals(p) || "".equals(p);
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

    public PlanDraftResponse pruneAreaInconsistentFlexibleStops(PlanDraftResponse draft) {
        return pruneAreaInconsistentFlexibleStops(draft, null);
    }

    public PlanDraftResponse pruneAreaInconsistentFlexibleStops(PlanDraftResponse draft, CreatePlanReq req) {
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

    public PlanDraftResponse pruneExcessNonMealStops(PlanDraftResponse draft) {
        return pruneExcessNonMealStops(draft, null);
    }

    public PlanDraftResponse pruneExcessNonMealStops(PlanDraftResponse draft, CreatePlanReq req) {
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
        return switch (normalizeSlot(pace)) {
            case "relaxed" -> 3;
            case "rush", "fast" -> 5;
            default -> 4;
        };
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

    private boolean hasMealSlot(Place s, String slot) {
        return s != null && (slot.equals(normalizeSlot(s.mealType())) || slot.equals(normalizeSlot(s.timeSlot())));
    }

    private String normalizeSlot(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    public PlanDraftResponse normalizeDraftCoordinates(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = new ArrayList<>();
        for (DayPlan day : draft.daysPlan()) {
            Place hotel = withResolvedCoordinate(day.hotel());
            List<Place> stops = day.stops() == null ? List.of() : day.stops().stream().map(this::withResolvedCoordinate).toList();
            days.add(new DayPlan(day.dayIndex(), hotel, stops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
    }

    private Place withResolvedCoordinate(Place stop) {
        if (stop == null) return null;
        if (stop.latitude() != null && stop.longitude() != null && !shouldRefreshCoordinate(stop)) return stop;
        StopLocation location = resolveStopLocationSafely(stop);
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
        return isNavigationAnchorCandidate(stop.name()) || isParkStopForCoordinateRefresh(stop) || isStrongPoiCandidate(stop);
    }

    public List<StopCoordinate> resolveStopCoordinatesInParallel(List<Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, stops.size()));
        try {
            List<CompletableFuture<StopCoordinate>> futures = stops.stream()
                    .map(stop -> CompletableFuture.supplyAsync(() -> resolveStopCoordinateSafely(stop), executor))
                    .toList();
            return futures.stream().map(this::joinNullable).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    public List<Integer> resolveTransferMinutesInParallel(
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
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, stops.size() - 1));
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 1; i < stops.size(); i++) {
                final int index = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> routeAwareTransitionMinutes(stops, coordinates, index, recommendationContext),
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
                return existingLocation;
            }
            if (stop == null) {
                return null;
            }
            if (isRouteSuggestionOptional(stop) && stop.name() != null && !stop.name().isBlank()) {
                GeoResponse response = mapService.geocodeWithoutBackfill(stop.name(), stop.city());
                StopCoordinate coordinate = coordinateFromGeocode(response);
                if (coordinate != null && isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    return new StopLocation(coordinate.lat(), coordinate.lon(), null);
                }
            }
            for (String candidate : geocodeCandidates(stop)) {
                GeoResponse response = mapService.geocode(candidate, stop.city());
                StopCoordinate coordinate = coordinateFromGeocode(response);
                if (coordinate != null && isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    return new StopLocation(coordinate.lat(), coordinate.lon(), null);
                }
            }
        } catch (RuntimeException e) {
            return existingLocation;
        }
        return existingLocation;
    }

    private StopLocation existingStopLocation(Place stop) {
        if (stop == null || stop.latitude() == null || stop.longitude() == null) {
            return null;
        }
        StopCoordinate coordinate = new StopCoordinate(stop.latitude(), stop.longitude());
        if (!isCoordinatePlausibleForCity(coordinate, stop.city())) {
            return null;
        }
        return new StopLocation(stop.latitude(), stop.longitude(), null);
    }

    private int routeAwareTransitionMinutes(
            List<Place> stops,
            List<StopCoordinate> coordinates,
            int index,
            RouteRecommendationContext recommendationContext
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
        RouteChoice routeChoice = resolveRouteChoice(origin.asLatLon(), destination.asLatLon(), recommendationContext, false);
        ModeSummary recommended = routeChoice.recommended();
        if (recommended == null || recommended.durationMinutes() == null || recommended.durationMinutes() <= 0) {
            return fallback;
        }
        int buffered = recommended.durationMinutes() + 5;
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

    private RouteChoice resolveRouteChoice(String origin, String destination, RouteRecommendationContext context, boolean rainy) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return new RouteChoice(null, null, null, null);
        }
        ModeSummary walk = modeSummary("walk", () -> mapService.walk_summary(origin, destination));
        ModeSummary transit = modeSummary("transit", () -> mapService.transit_summary(origin, destination));
        ModeSummary car = normalizeCarSummary(modeSummary("car", () -> mapService.car_summary(origin, destination)));
        ModeSummary recommended = recommendMode(walk, transit, car, context, rainy);
        return new RouteChoice(walk, transit, car, recommended);
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

    public PlanDraftResponse clampOversizedGaps(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = draft.daysPlan().stream().map(this::clampDayGaps).toList();
        return new PlanDraftResponse(draft.city(), draft.country(), draft.days(), draft.currency(), draft.party(), draft.pace(), draft.title(), draft.overview(), days, draft.copyPolishStatus());
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
                if (start - prevEnd > 120) {
                    start = Math.max(prevEnd + 20, earliestAllowedStartForGapClamp(stop));
                    end = start + stay;
                }
            }
            adjusted.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
        }
        return new DayPlan(day.dayIndex(), day.hotel(), adjusted, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
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

    public PlanDraftResponse rewriteDayNarratives(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<DayPlan> days = draft.daysPlan().stream().map(day -> {
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
            if (preferredStart <= earliestDinnerStart) {
                return earliestDinnerStart;
            }
            if (preferredStart - rollingStart > maxPreferredWaitMinutes(normalizedSlot)) {
                return earliestDinnerStart;
            }
            return preferredStart;
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

    private Place copyPlaceWithCoordinates(Place s, double lat, double lon) {
        return new Place(s.name(), s.addressLine(), s.suburb(), s.city(), s.state(), s.postcode(), s.country(), s.category(), s.stayMinutes(), s.timeSlot(), s.startTime(), s.endTime(), s.mealType(), s.preferredArea(), s.cuisine(), s.vibe(), s.budgetLevel(), s.reason(), s.tip(), s.websiteUri(), s.googleMapsUri(), s.businessStatus(), s.url(), lat, lon);
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

    private List<String> geocodeCandidates(Place stop) {
        List<String> candidates = new ArrayList<>();
        if (stop == null) {
            return candidates;
        }
        String rawAddress = stop.addressLine() == null ? "" : stop.addressLine().trim();
        String name = stop.name() == null ? "" : stop.name().trim();
        boolean navigationAnchorCandidate = isNavigationAnchorCandidate(name) || isParkStopForCoordinateRefresh(stop);
        boolean strongPoiCandidate = isStrongPoiCandidate(stop);
        if (strongPoiCandidate && !name.isBlank()) {
            if (!rawAddress.isBlank() && !rawAddress.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                addUnique(candidates, corePoiName(name) + ", " + rawAddress);
                addUnique(candidates, name + ", " + rawAddress);
            }
            if (stop.city() != null && !stop.city().isBlank()) {
                addUnique(candidates, corePoiName(name) + ", " + stop.city().trim());
                addUnique(candidates, name + ", " + stop.city().trim());
            }
            addUnique(candidates, corePoiName(name));
            addUnique(candidates, name);
        } else if (navigationAnchorCandidate && !name.isBlank()) {
            addUnique(candidates, name);
            if (stop.city() != null && !stop.city().isBlank()) {
                addUnique(candidates, name + ", " + stop.city().trim());
            }
        }
        if (!rawAddress.isBlank()) {
            addUnique(candidates, rawAddress);
            if (!name.isBlank() && !rawAddress.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                addUnique(candidates, name + ", " + rawAddress);
            }
        }
        if (!navigationAnchorCandidate && !strongPoiCandidate && !name.isBlank()) {
            addUnique(candidates, name);
            if (stop.city() != null && !stop.city().isBlank()) {
                addUnique(candidates, name + ", " + stop.city().trim());
            }
        }
        return candidates;
    }

    private StopCoordinate coordinateFromGeocode(GeoResponse response) {
        if (response == null || response.features() == null || response.features().isEmpty()) {
            return null;
        }
        GeoResponse.Feature feature = response.features().getFirst();
        if (feature == null || feature.properties() == null) {
            return null;
        }
        Double lat = feature.properties().lat();
        Double lon = feature.properties().lon();
        if ((lat == null || lon == null) && feature.geometry() != null && feature.geometry().coordinates() != null && feature.geometry().coordinates().size() >= 2) {
            lon = feature.geometry().coordinates().get(0);
            lat = feature.geometry().coordinates().get(1);
        }
        if (lat == null || lon == null) {
            return null;
        }
        return new StopCoordinate(lat, lon);
    }

    private boolean isCoordinatePlausibleForCity(StopCoordinate coordinate, String city) {
        if (coordinate == null || city == null || city.isBlank()) {
            return true;
        }
        String normalizedCity = city.trim().toLowerCase(Locale.ROOT);
        double lat = coordinate.lat();
        double lon = coordinate.lon();
        if ("brisbane".equals(normalizedCity)) {
            return lat >= -28.2 && lat <= -26.8 && lon >= 152.4 && lon <= 153.7;
        }
        if ("sydney".equals(normalizedCity)) {
            return lat >= -34.4 && lat <= -33.2 && lon >= 150.5 && lon <= 151.6;
        }
        if ("melbourne".equals(normalizedCity)) {
            return lat >= -38.5 && lat <= -37.2 && lon >= 144.2 && lon <= 145.6;
        }
        return true;
    }

    private boolean isNavigationAnchorCandidate(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String text = name.toLowerCase(Locale.ROOT);
        return text.contains("harbour")
                || text.contains("parklands")
                || text.contains("botanic")
                || text.contains("garden")
                || text.contains("lookout")
                || text.contains("wharf")
                || text.contains("wharves")
                || text.contains("quay")
                || text.contains("pier")
                || text.contains("landmark")
                || text.contains("summit")
                || text.contains("mount ")
                || text.contains("mt ")
                || text.contains("beach")
                || text.contains("reserve")
                || text.contains("riverwalk")
                || text.contains("waterfront")
                || text.contains("precinct")
                || text.contains("trail")
                || text.contains("national park");
    }

    private boolean isParkStopForCoordinateRefresh(Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank()) {
            return false;
        }
        String name = stop.name().toLowerCase(Locale.ROOT);
        if (!name.matches(".*\\bpark\\b.*")) {
            return false;
        }
        if (name.matches(".*\\b(car park|parking|parkroyal|park hotel|hotel|restaurant|cafe|bar)\\b.*")) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "park".equals(category)
                || "nature".equals(category)
                || "attraction".equals(category)
                || "outdoor".equals(category)
                || name.contains("national park")
                || name.contains("reserve")
                || name.contains("garden")
                || name.contains("lookout")
                || name.contains("beach");
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

    private String corePoiName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim();
        String[] parts = cleaned.split("\\s[-|]\\s|\\|");
        return parts.length == 0 ? cleaned : parts[0].trim();
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

    private void appendStageTiming(StringBuilder sb, String stage, long ms) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(stage).append("=").append(ms).append("ms");
    }

    private void logPlanStageCounts(StringBuilder sb, String att, String stg, PlanDraftResponse d) {
        String c = summarizeDraftCounts(d);
        if (sb.length() > 0) sb.append(" || ");
        sb.append(att).append("/").append(stg).append(": ").append(c);
    }

    private void logPlanStageSummary(StringBuilder sb) { log.info("Plan stage summary [{}]", sb); }
    private void logPlanStageTimingSummary(StringBuilder sb) { log.info("Plan stage timings elapsedMs=[{}]", sb); }

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

    private record StopLocation(double lat, double lon, String addressLine) {}
    private record ParsedAddress(String addressLine, String suburb, String state, String postcode, String country) {}
    private record RankedPlaceCoordinate(GooglePlacesClient.PlaceCandidate candidate, int score) {}
}
