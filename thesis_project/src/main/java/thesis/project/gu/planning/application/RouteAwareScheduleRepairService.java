package thesis.project.gu.planning.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.infrastructure.cache.CacheSerive;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.scheduling.DaySkeletonService;
import thesis.project.gu.routing.application.MapService;
import thesis.project.gu.routing.domain.ModeSummary;
import thesis.project.gu.routing.domain.RouteChoice;
import thesis.project.gu.routing.domain.RouteRecommendationContext;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;

import java.time.Duration;
import java.time.LocalTime;
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
import java.util.stream.Collectors;

@Service
public class RouteAwareScheduleRepairService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY_START_MINUTES = 9 * 60;
    private static final int LUNCH_LATEST_START_MINUTES = PlanProcessorService.LUNCH_LATEST_START_MINUTES;
    private static final int THEME_PARK_DAY_DINNER_LATEST_START_MINUTES = PlanProcessorService.THEME_PARK_DAY_DINNER_LATEST_START_MINUTES;
    private static final int SHORT_WALK_ESTIMATE_MAX_DISTANCE_METERS = 650;
    private static final int SHORT_WALK_ESTIMATE_STRICT_AREA_DISTANCE_METERS = 450;
    private static final int SCHEDULING_SAME_AREA_WALK_ESTIMATE_MAX_DISTANCE_METERS = 1_200;
    private static final int SCHEDULING_SAME_SUBURB_WALK_ESTIMATE_MAX_DISTANCE_METERS = 900;
    private static final int SCHEDULING_CULTURAL_PRECINCT_WALK_ESTIMATE_MAX_DISTANCE_METERS = 1_400;
    private static final int SCHEDULING_DIRECT_WALK_ESTIMATE_MAX_DISTANCE_METERS = 750;
    private static final int SHORT_WALK_ESTIMATE_MIN_MINUTES = 8;
    private static final int SHORT_WALK_ESTIMATE_BUFFER_MINUTES = 4;
    private static final double SHORT_WALK_ESTIMATE_METERS_PER_MINUTE = 75.0;
    private static final String ROUTE_CHOICE_REDIS_PREFIX = "nav:route_choice_hybrid:";
    private static final Duration ROUTE_CHOICE_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration STOP_LOCATION_CACHE_TTL = Duration.ofMinutes(10);
    private static final double REDIS_TTL_JITTER_RATIO = 0.10D;
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

    private final MapService mapService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final PlaceHeuristicService placeHeuristicService;
    private final DaySkeletonService daySkeletonService;

    public RouteAwareScheduleRepairService(
            MapService mapService,
            ObjectMapper objectMapper,
            StringRedisTemplate stringRedisTemplate,
            PlaceHeuristicService placeHeuristicService,
            DaySkeletonService daySkeletonService
    ) {
        this.mapService = mapService;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.placeHeuristicService = placeHeuristicService;
        this.daySkeletonService = daySkeletonService;
    }

    public PlanDraftResponse applyRouteAwareScheduling(PlanDraftResponse draft, String attemptLabel, StringBuilder stageSummary, StringBuilder timingSummary) {
        PlanDraft repaired = applyRouteAwareScheduling(PlanDraft.fromResponse(draft), attemptLabel, stageSummary, timingSummary);
        return repaired == null ? null : repaired.toResponse();
    }

    public PlanDraft applyRouteAwareScheduling(PlanDraft draft, String attemptLabel, StringBuilder stageSummary, StringBuilder timingSummary) {
        long stageStartedAt = System.currentTimeMillis();
        draft = normalizeDraftScheduleWithRouteDurations(draft);
        draft = pruneOutOfRangeThemeParkStops(draft);
        draft = pruneThemeParkDayTrips(draft);
        appendStageTiming(timingSummary, attemptLabel + "/route-aware-schedule", System.currentTimeMillis() - stageStartedAt);
        logPlanStageCounts(stageSummary, attemptLabel, "route-aware-schedule", draft);
        return draft;
    }

    public PlanDraftResponse normalizeDraftScheduleWithRouteDurations(PlanDraftResponse draft) {
        PlanDraft normalized = normalizeDraftScheduleWithRouteDurations(PlanDraft.fromResponse(draft));
        return normalized == null ? null : normalized.toResponse();
    }

    public PlanDraft normalizeDraftScheduleWithRouteDurations(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        RouteRecommendationContext ctx = routeSchedulingContext(draft);
        List<PlanDraft.DayPlan> days = normalizeDaysScheduleWithRouteDurations(draft.daysPlan(), ctx, null);
        return withDays(draft, days);
    }

    public PlanDraftResponse normalizeDraftScheduleWithRouteDurations(PlanDraftResponse draft, java.util.Set<Integer> targetDayIndexes) {
        PlanDraft normalized = normalizeDraftScheduleWithRouteDurations(PlanDraft.fromResponse(draft), targetDayIndexes);
        return normalized == null ? null : normalized.toResponse();
    }

    public PlanDraft normalizeDraftScheduleWithRouteDurations(PlanDraft draft, java.util.Set<Integer> targetDayIndexes) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || targetDayIndexes == null || targetDayIndexes.isEmpty()) {
            return draft;
        }
        RouteRecommendationContext ctx = routeSchedulingContext(draft);
        List<PlanDraft.DayPlan> days = normalizeDaysScheduleWithRouteDurations(draft.daysPlan(), ctx, targetDayIndexes);
        return withDays(draft, days);
    }

    public List<StopCoordinate> resolveStopCoordinatesInParallel(List<PlanDraft.Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, stops.size()));
        try {
            Map<String, CompletableFuture<StopCoordinate>> deduplicatedFutures = new ConcurrentHashMap<>();
            List<CompletableFuture<StopCoordinate>> futures = new ArrayList<>(stops.size());
            for (PlanDraft.Place stop : stops) {
                String key = stopCoordinateDedupeKey(stop);
                CompletableFuture<StopCoordinate> future = deduplicatedFutures.computeIfAbsent(
                        key,
                        ignored -> CompletableFuture.supplyAsync(() -> resolveStopCoordinateSafely(stop), executor)
                );
                futures.add(future);
            }
            return futures.stream().map(this::joinCoordinate).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    void clearRouteChoiceCrossRequestCache() {
        ROUTE_CHOICE_L1_CACHE.invalidateAll();
        if (stringRedisTemplate != null) {
            try {
                var keys = stringRedisTemplate.keys(ROUTE_CHOICE_REDIS_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    stringRedisTemplate.delete(keys);
                }
            } catch (RuntimeException ignored) {
                // Cache cleanup is best effort for tests and admin-style maintenance paths.
            }
        }
    }

    void clearRouteChoiceLocalCacheOnly() {
        ROUTE_CHOICE_L1_CACHE.invalidateAll();
    }

    public StopCoordinate resolveStopCoordinateSafely(PlanDraft.Place stop) {
        StopLocation location = resolveStopLocationSafely(stop);
        return location == null ? null : new StopCoordinate(location.lat(), location.lon());
    }

    private StopLocation resolveStopLocationSafely(PlanDraft.Place stop) {
        if (stop == null) {
            return null;
        }
        if (stop.latitude() != null && stop.longitude() != null && !shouldRefreshCoordinate(stop)) {
            StopLocation existing = existingStopLocation(stop);
            if (existing != null) {
                cacheResolvedStopLocation(stop, existing);
                return existing;
            }
        }
        StopLocation cached = getCachedStopLocation(stop);
        if (cached != null) {
            return cached;
        }
        StopLocation existing = existingStopLocation(stop);
        if (existing != null && !isRouteSuggestionOptional(stop)) {
            cacheResolvedStopLocation(stop, existing);
            return existing;
        }
        StopLocation resolved = null;
        if (stop.name() != null && !stop.name().isBlank()) {
            String searchCity = stop.city();
            if (searchCity == null || searchCity.isBlank()) {
                searchCity = stop.suburb();
            }
            String effectiveSearchCity = searchCity;
            boolean navigationAnchorCandidate = isRouteSuggestionOptional(stop);
            for (String candidate : placeHeuristicService.geocodeCandidates(stop.toResponse(), isStrongPoiCandidate(stop), navigationAnchorCandidate)) {
                GeoResponse response = withBulkhead(GEOCODE_BULKHEAD, () -> mapService.geocode(candidate, effectiveSearchCity), null);
                StopCoordinate coordinate = firstCoordinate(response);
                if (coordinate == null || !placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    continue;
                }
                resolved = new StopLocation(coordinate.lat(), coordinate.lon(), stop.addressLine());
                break;
            }
        }
        if (resolved == null) {
            resolved = existing;
        }
        if (resolved != null) {
            cacheResolvedStopLocation(stop, resolved);
        }
        return resolved;
    }

    private RouteRecommendationContext routeSchedulingContext(PlanDraft draft) {
        int kids = draft == null || draft.party() == null || draft.party().kids() == null ? 0 : draft.party().kids();
        return new RouteRecommendationContext(kids > 0, null, null);
    }

    private List<PlanDraft.DayPlan> normalizeDaysScheduleWithRouteDurations(List<PlanDraft.DayPlan> sourceDays, RouteRecommendationContext ctx, java.util.Set<Integer> targetDayIndexes) {
        if (sourceDays == null || sourceDays.isEmpty()) {
            return List.of();
        }
        if (sourceDays.size() == 1) {
            PlanDraft.DayPlan day = sourceDays.getFirst();
            if (shouldNormalizeRouteAwareDay(day, targetDayIndexes)) {
                return List.of(normalizeDayScheduleWithRouteDurations(day, ctx));
            }
            return sourceDays;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, sourceDays.size()));
        try {
            List<CompletableFuture<PlanDraft.DayPlan>> futures = sourceDays.stream()
                    .map(day -> shouldNormalizeRouteAwareDay(day, targetDayIndexes)
                            ? CompletableFuture.supplyAsync(() -> normalizeDayScheduleWithRouteDurations(day, ctx), executor)
                            : CompletableFuture.completedFuture(day))
                    .toList();
            return futures.stream().map(this::joinDayPlan).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean shouldNormalizeRouteAwareDay(PlanDraft.DayPlan day, java.util.Set<Integer> targetDayIndexes) {
        return day != null && (targetDayIndexes == null || targetDayIndexes.isEmpty() || targetDayIndexes.contains(day.dayIndex()));
    }

    private PlanDraft.DayPlan joinDayPlan(CompletableFuture<PlanDraft.DayPlan> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            return null;
        }
    }

    private PlanDraft.DayPlan normalizeDayScheduleWithRouteDurations(PlanDraft.DayPlan day, RouteRecommendationContext ctx) {
        List<PlanDraft.Place> stops = day.stops() == null ? List.of() : new ArrayList<>(day.stops());
        if (stops.isEmpty()) return day;
        stops = reorderStopsByTimeSlotIfMealOrderInvalid(stops);
        List<StopCoordinate> coordinates = resolveStopCoordinatesInParallel(stops);
        List<Integer> transferMinutes = resolveTransferMinutesInParallel(stops, coordinates, ctx);
        List<PlanDraft.Place> normalized = new ArrayList<>();
        int previousEnd = DAY_START_MINUTES;
        for (int i = 0; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
            int stay = resolveStayMinutes(stop);
            int transfer = i < transferMinutes.size() ? transferMinutes.get(i) : transitionMinutes(i == 0);
            int rollingStart = previousEnd + transfer;
            int preferredStart = preferredStartMinutes(stop.timeSlot(), i == 0);
            int start = chooseScheduledStart(rollingStart, preferredStart, stop);
            int end = start + stay;
            normalized.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
            previousEnd = end;
        }
        return new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), normalized, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
    }

    private List<Integer> resolveTransferMinutesInParallel(List<PlanDraft.Place> stops, List<StopCoordinate> coordinates, RouteRecommendationContext recommendationContext) {
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

    private int routeAwareTransitionMinutes(List<PlanDraft.Place> stops, List<StopCoordinate> coordinates, int index, RouteRecommendationContext recommendationContext, Map<RouteChoiceCacheKey, RouteChoice> routeChoiceCache) {
        if (index == 0) {
            return 0;
        }
        int fallback = transitionMinutes(false);
        if (stops == null || coordinates == null || index >= stops.size() || index >= coordinates.size()) {
            return fallback;
        }
        PlanDraft.Place previous = stops.get(index - 1);
        PlanDraft.Place current = stops.get(index);
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
                allowedCap = Math.min(allowedCap, Math.max(transitionMinutes(false), THEME_PARK_DAY_DINNER_LATEST_START_MINUTES - previousEnd));
            }
        }
        return Math.max(fallback, Math.min(buffered, allowedCap));
    }

    private Integer estimateSchedulingTransferMinutes(PlanDraft.Place previous, PlanDraft.Place current, StopCoordinate origin, StopCoordinate destination, RouteRecommendationContext context) {
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

    private boolean requiresExternalRoutingForScheduling(PlanDraft.Place previous, PlanDraft.Place current) {
        return isLateDayViewStop(previous)
                || isLateDayViewStop(current)
                || isThemeParkLikeStop(previous)
                || isThemeParkLikeStop(current);
    }

    private boolean sameStopOrAddress(PlanDraft.Place previous, PlanDraft.Place current) {
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

    private boolean sameCulturalPrecinct(PlanDraft.Place previous, PlanDraft.Place current) {
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

    private boolean isCulturalPrecinctStop(PlanDraft.Place stop) {
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

    private RouteChoice shortWalkRouteChoice(PlanDraft.Place previous, PlanDraft.Place current, StopCoordinate origin, StopCoordinate destination, RouteRecommendationContext context, boolean rainy) {
        if (!shouldUseShortWalkEstimate(previous, current, origin, destination, context, rainy)) {
            return null;
        }
        int distanceMeters = (int) Math.ceil(haversineMeters(origin.lat(), origin.lon(), destination.lat(), destination.lon()));
        int estimatedMinutes = estimateShortWalkMinutes(distanceMeters);
        ModeSummary walk = new ModeSummary("walk", estimatedMinutes, distanceMeters);
        return new RouteChoice(walk, null, null, walk);
    }

    private boolean shouldUseShortWalkEstimate(PlanDraft.Place previous, PlanDraft.Place current, StopCoordinate origin, StopCoordinate destination, RouteRecommendationContext context, boolean rainy) {
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

    private boolean sameAreaFallbackAllowed(PlanDraft.Place previous, PlanDraft.Place current) {
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

    private boolean isThemeParkLunchToContinuation(PlanDraft.Place previous, PlanDraft.Place current) {
        return hasMealSlot(previous, "lunch")
                && isThemeParkContinuationStop(current)
                && isSameThemeParkCluster(current, previous);
    }

    private boolean isThemeParkContinuationStop(PlanDraft.Place stop) {
        return stop != null && stop.name() != null && stop.name().toLowerCase(Locale.ROOT).contains("(afternoon)");
    }

    private PlanDraft pruneOutOfRangeThemeParkStops(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        StopCoordinate cityCenter = cityCenterCoordinate(draft.city());
        if (cityCenter == null) {
            return draft;
        }
        boolean changed = false;
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day.stops() == null ? List.of() : day.stops();
            List<PlanDraft.Place> keptStops = new ArrayList<>();
            for (PlanDraft.Place stop : stops) {
                if (isOutOfRangeThemeParkStop(cityCenter, stop)) {
                    changed = true;
                    continue;
                }
                keptStops.add(stop);
            }
            updatedDays.add(new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), keptStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    private PlanDraft pruneThemeParkDayTrips(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day.stops() == null ? List.of() : day.stops();
            PlanDraft.Place themePark = stops.stream().filter(this::isThemeParkLikeStop).findFirst().orElse(null);
            if (themePark == null) {
                updatedDays.add(day);
                continue;
            }
            int keptSameClusterExtra = 0;
            List<PlanDraft.Place> keptStops = new ArrayList<>();
            for (PlanDraft.Place stop : stops) {
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
            updatedDays.add(new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), keptStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    private boolean isThemeParkLikeStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String cat = normalizeSlot(stop.category());
        if ("theme_park".equals(cat)) {
            return true;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        return name.contains("disneyland") || name.contains("disney world") || name.contains("universal studios") || name.contains("theme park") || name.contains("water park");
    }

    private boolean isSameThemeParkCluster(PlanDraft.Place themePark, PlanDraft.Place stop) {
        if (themePark == null || stop == null) {
            return false;
        }
        if (themePark.latitude() != null && themePark.longitude() != null && stop.latitude() != null && stop.longitude() != null) {
            double distanceMeters = haversineMeters(themePark.latitude(), themePark.longitude(), stop.latitude(), stop.longitude());
            return distanceMeters <= 2000;
        }
        String tpArea = themeParkAnchorArea(themePark);
        String stArea = themeParkAnchorArea(stop);
        return tpArea != null && tpArea.equals(stArea);
    }

    private String themeParkAnchorArea(PlanDraft.Place stop) {
        if (stop == null) {
            return null;
        }
        String suburb = stop.suburb() == null ? "" : stop.suburb().trim();
        if (!suburb.isBlank()) {
            return suburb.toLowerCase(Locale.ROOT);
        }
        String area = stop.preferredArea() == null ? "" : stop.preferredArea().trim();
        return area.isBlank() ? null : area.toLowerCase(Locale.ROOT);
    }

    private boolean isThemeParkClusterMeal(PlanDraft.Place themePark, PlanDraft.Place stop) {
        return hasMealSlot(stop, "lunch") && isSameThemeParkCluster(themePark, stop);
    }

    private boolean isOutOfRangeThemeParkStop(StopCoordinate cityCenter, PlanDraft.Place stop) {
        if (!isThemeParkLikeStop(stop) || stop == null || stop.latitude() == null || stop.longitude() == null) {
            return false;
        }
        double distanceMeters = haversineMeters(cityCenter.lat(), cityCenter.lon(), stop.latitude(), stop.longitude());
        return distanceMeters > 150_000D;
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

    private boolean hasMealSlot(PlanDraft.Place s, String slot) {
        return s != null && (slot.equals(normalizeSlot(s.mealType())) || slot.equals(normalizeSlot(s.timeSlot())));
    }

    private boolean isStrictMealStop(PlanDraft.Place s) {
        String cat = normalizeSlot(s.category());
        return "restaurant".equals(cat) || "cafe".equals(cat) || "food".equals(cat);
    }

    private String normalizeSlot(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private int resolveStayMinutes(PlanDraft.Place s) {
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

    private int chooseScheduledStart(int rollingStart, int preferredStart, PlanDraft.Place stop) {
        String normalizedSlot = normalizeSlot(stop == null ? null : stop.timeSlot());
        int earliestAllowed = timeSensitiveEarliestStart(stop);
        rollingStart = Math.max(rollingStart, earliestAllowed);
        preferredStart = Math.max(preferredStart, earliestAllowed);
        if ("lunch".equals(normalizedSlot)) {
            return chooseLunchStart(rollingStart, preferredStart);
        }
        if ("dinner".equals(normalizedSlot) || "evening".equals(normalizedSlot)) {
            int earliestDinnerStart = Math.max(rollingStart, PlanProcessorService.DINNER_EARLIEST_START_MINUTES);
            if (earliestDinnerStart > PlanProcessorService.DINNER_LATEST_START_MINUTES) {
                return earliestDinnerStart;
            }
            if (preferredStart <= earliestDinnerStart) {
                return earliestDinnerStart;
            }
            return Math.min(preferredStart, PlanProcessorService.DINNER_LATEST_START_MINUTES);
        }
        if (preferredStart <= rollingStart) {
            return rollingStart;
        }
        return Math.min(preferredStart, rollingStart + maxPreferredWaitMinutes(normalizedSlot));
    }

    private int chooseLunchStart(int rollingStart, int preferredStart) {
        int earliest = Math.max(rollingStart, PlanProcessorService.LUNCH_EARLIEST_START_MINUTES);
        if (earliest > PlanProcessorService.LUNCH_LATEST_START_MINUTES) {
            return earliest;
        }
        if (preferredStart <= earliest) {
            return earliest;
        }
        if (preferredStart - rollingStart > maxPreferredWaitMinutes("lunch")) {
            return earliest;
        }
        return Math.min(preferredStart, PlanProcessorService.LUNCH_LATEST_START_MINUTES);
    }

    private int maxPreferredWaitMinutes(String slot) {
        return "lunch".equals(slot) ? 90 : 120;
    }

    private int timeSensitiveEarliestStart(PlanDraft.Place stop) {
        if (stop == null) {
            return 0;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        if (name.contains("penguin")) {
            return 16 * 60 + 30;
        }
        if (isCulturalOpeningHoursConstrained(stop)) {
            return 10 * 60;
        }
        return 0;
    }

    private boolean isCulturalOpeningHoursConstrained(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        String category = normalizeSlot(stop.category());
        return "museum".equals(category) || "gallery".equals(category) || name.contains("museum") || name.contains("gallery") || name.contains("historic");
    }

    private PlanDraft normalizeDraftSchedule(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null) return draft;
        List<PlanDraft.DayPlan> days = new ArrayList<>();
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            days.add(normalizeDaySchedule(day));
        }
        return withDays(draft, days);
    }

    private PlanDraft.DayPlan normalizeDaySchedule(PlanDraft.DayPlan day) {
        List<PlanDraft.Place> stops = day.stops() == null ? List.of() : new ArrayList<>(day.stops());
        if (stops.isEmpty()) return day;
        stops = reorderStopsByTimeSlotIfMealOrderInvalid(stops);
        List<PlanDraft.Place> normalized = new ArrayList<>();
        int previousEnd = DAY_START_MINUTES;
        for (int i = 0; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
            int stay = resolveStayMinutes(stop);
            int rollingStart = previousEnd + transitionMinutes(i == 0);
            int preferredStart = preferredStartMinutes(stop.timeSlot(), i == 0);
            int start = chooseScheduledStart(rollingStart, preferredStart, stop);
            int end = start + stay;
            normalized.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
            previousEnd = end;
        }
        return new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), normalized, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
    }

    private List<PlanDraft.Place> reorderStopsByTimeSlotIfMealOrderInvalid(List<PlanDraft.Place> stops) {
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
        List<PlanDraft.Place> reordered = new ArrayList<>(stops);
        reordered.sort((left, right) -> Integer.compare(slotSortOrder(left), slotSortOrder(right)));
        return reordered;
    }

    private int firstMealIndex(List<PlanDraft.Place> stops, String slot) {
        if (stops == null) {
            return -1;
        }
        for (int i = 0; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
            if (hasMealSlot(stop, slot)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasLateDayNonMealBefore(List<PlanDraft.Place> stops, int index) {
        for (int i = 0; i < index; i++) {
            PlanDraft.Place stop = stops.get(i);
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

    private boolean hasMiddayOrAfternoonStopAfter(List<PlanDraft.Place> stops, int index) {
        for (int i = index + 1; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
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

    private int slotSortOrder(PlanDraft.Place stop) {
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

    private PlanDraft.Place copyPlaceWithTimes(PlanDraft.Place s, String start, String end, int stay) {
        return new PlanDraft.Place(s.name(), s.addressLine(), s.suburb(), s.city(), s.state(), s.postcode(), s.country(), s.category(), stay, s.timeSlot(), start, end, s.mealType(), s.preferredArea(), s.cuisine(), s.vibe(), s.budgetLevel(), s.reason(), s.tip(), s.websiteUri(), s.googleMapsUri(), s.businessStatus(), s.url(), s.latitude(), s.longitude());
    }

    private int parseTimeMinutes(String val) {
        if (val == null || !val.matches("^\\d{2}:\\d{2}$")) return -1;
        return Integer.parseInt(val.substring(0, 2)) * 60 + Integer.parseInt(val.substring(3, 5));
    }

    private String formatMinutes(int min) {
        int n = Math.max(0, Math.min(min, 1439));
        return LocalTime.of(n / 60, n % 60).format(TIME_FORMATTER);
    }

    private void appendStageTiming(StringBuilder sb, String stage, long ms) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(stage).append("=").append(ms).append("ms");
    }

    private void logPlanStageCounts(StringBuilder sb, String att, String stg, PlanDraft d) {
        String c = d == null || d.daysPlan() == null ? "null" : "days=" + d.daysPlan().size();
        if (sb.length() > 0) sb.append(" || ");
        sb.append(att).append("/").append(stg).append(": ").append(c);
    }

    private PlanDraft withDays(PlanDraft draft, List<PlanDraft.DayPlan> days) {
        return new PlanDraft(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                days == null ? List.of() : days,
                draft.copyPolishStatus()
        );
    }

    private RouteChoice resolveRouteChoice(String origin, String destination, RouteRecommendationContext context, boolean rainy, Map<RouteChoiceCacheKey, RouteChoice> routeChoiceCache) {
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
        return ROUTE_CHOICE_REDIS_PREFIX + cacheKey.origin() + "|" + cacheKey.destination() + "|r=" + (cacheKey.rainy() ? "1" : "0") + "|k=" + (cacheKey.hasKids() ? "1" : "0");
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


    private record RouteChoiceCacheKey(String origin, String destination, boolean rainy, boolean hasKids) {}
    private record StopLocation(double lat, double lon, String addressLine) {}
    private interface RouteSummarySupplier { MapService.RouteSummary get(); }

    private boolean isLateDayViewStop(PlanDraft.Place stop) {
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

    private boolean hasThemeParkBeforeIndex(List<PlanDraft.Place> stops, int index) {
        for (int i = 0; i < index; i++) {
            if (isThemeParkLikeStop(stops.get(i))) return true;
        }
        return false;
    }

    private int maxAllowedGapMinutes(PlanDraft.Place previous, PlanDraft.Place current, boolean finalStopOfDay) {
        return maxAllowedGapMinutes(previous, current, finalStopOfDay, -1);
    }

    private int maxAllowedGapMinutes(PlanDraft.Place previous, PlanDraft.Place current, boolean finalStopOfDay, int previousEndMinutes) {
        String previousSlot = normalizeSlot(previous == null ? null : previous.timeSlot());
        String currentSlot = normalizeSlot(current == null ? null : current.timeSlot());
        if (isThemeParkLikeStop(previous) || isThemeParkLikeStop(current)) {
            if (finalStopOfDay && ("dinner".equals(currentSlot) || "evening".equals(currentSlot))) {
                return PlanProcessorService.THEME_PARK_DAY_DINNER_LATEST_START_MINUTES - (13 * 60 + 60);
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
                    ? Math.max(120, PlanProcessorService.DINNER_EARLIEST_START_MINUTES - previousEndMinutes)
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

    private boolean isMarketShoppingLikeStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String text = normalizeSlot(joinText(stop.name(), stop.category(), stop.reason(), stop.tip()));
        return text.contains("market") || text.contains("shopping") || text.contains("arcade") || text.contains("retail") || text.contains("bazaar");
    }


    private String normalizedAreaLabel(PlanDraft.Place stop) {
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

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
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

    private String displayArea(PlanDraft.Place stop) {
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

    private StopLocation existingStopLocation(PlanDraft.Place stop) {
        if (stop == null || stop.latitude() == null || stop.longitude() == null) {
            return null;
        }
        StopCoordinate coordinate = new StopCoordinate(stop.latitude(), stop.longitude());
        if (!placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
            return null;
        }
        return new StopLocation(stop.latitude(), stop.longitude(), null);
    }

    private StopCoordinate firstCoordinate(GeoResponse response) {
        if (response == null || response.features() == null || response.features().isEmpty()) {
            return null;
        }
        GeoResponse.Feature feature = response.features().get(0);
        if (feature == null || feature.geometry() == null || feature.geometry().coordinates() == null || feature.geometry().coordinates().size() < 2) {
            return null;
        }
        List<Double> coordinates = feature.geometry().coordinates();
        Double lon = coordinates.get(0);
        Double lat = coordinates.get(1);
        if (lat == null || lon == null) {
            return null;
        }
        return new StopCoordinate(lat, lon);
    }

    private boolean shouldRefreshCoordinate(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        if (stop.latitude() == null || stop.longitude() == null) {
            return true;
        }
        StopCoordinate existing = new StopCoordinate(stop.latitude(), stop.longitude());
        if (!placeHeuristicService.isCoordinatePlausibleForCity(existing, stop.city())) {
            return true;
        }
        return isRouteSuggestionOptional(stop) || isStrongPoiCandidate(stop);
    }

    private boolean isRouteSuggestionOptional(PlanDraft.Place stop) {
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

    private boolean isStrongPoiCandidate(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if (isStrictMealStop(stop) || "lodging".equals(category) || "hotel".equals(category)) {
            return false;
        }
        return switch (category) {
            case "museum", "gallery", "cultural", "park", "nature", "outdoor", "lookout", "viewpoint", "landmark", "attraction", "theme_park" -> true;
            default -> false;
        };
    }

    private StopLocation getCachedStopLocation(PlanDraft.Place stop) {
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

    private void cacheResolvedStopLocation(PlanDraft.Place stop, StopLocation location) {
        if (stop == null || location == null) {
            return;
        }
        STOP_LOCATION_L1_CACHE.put(stopCoordinateDedupeKey(stop), location);
        String aliasKey = stopCoordinateAliasKey(stop);
        if (!aliasKey.isBlank() && !aliasKey.startsWith("|")) {
            STOP_LOCATION_L1_CACHE.put(aliasKey, location);
        }
    }

    private String stopCoordinateDedupeKey(PlanDraft.Place stop) {
        if (stop == null) {
            return "null-stop";
        }
        return normalizeSlot(stop.name()) + "|" + normalizeSlot(stop.addressLine()) + "|" + normalizeSlot(stop.suburb()) + "|" + normalizeSlot(stop.city()) + "|" + normalizeSlot(stop.state()) + "|" + normalizeSlot(stop.postcode()) + "|" + normalizeSlot(stop.country()) + "|" + normalizeSlot(stop.preferredArea()) + "|" + normalizeSlot(stop.category()) + "|" + normalizeSlot(stop.timeSlot()) + "|" + normalizeSlot(stop.mealType()) + "|" + (stop.latitude() == null ? "" : stop.latitude()) + "|" + (stop.longitude() == null ? "" : stop.longitude());
    }

    private String stopCoordinateAliasKey(PlanDraft.Place stop) {
        if (stop == null) {
            return "null-stop-alias";
        }
        return normalizedPoiIdentity(stop.name()) + "|" + normalizeSlot(stop.city()) + "|" + normalizeSlot(displayArea(stop)) + "|" + normalizeCoordinateCategory(stop);
    }

    private String normalizeCoordinateCategory(PlanDraft.Place stop) {
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
        String outside = raw.replaceAll("\\([^)]*\\)", " ").trim();
        return outside.isBlank() ? raw : outside;
    }

    private boolean isLowSignalDuplicateToken(String token) {
        return switch (token) {
            case "the", "of", "and", "at", "in", "on", "for", "to", "a", "an", "visit", "stop", "area", "precinct", "near", "nearby" -> true;
            default -> false;
        };
    }

    private String normalizeNameForNarrativeMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private StopLocation joinNullable(CompletableFuture<StopLocation> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return null;
        }
    }

    private StopCoordinate joinCoordinate(CompletableFuture<StopCoordinate> future) {
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

}

