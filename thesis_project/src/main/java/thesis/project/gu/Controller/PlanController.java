package thesis.project.gu.Controller;

import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.client.GooglePlacesClient;
import thesis.project.gu.client.WeatherApiClient;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.model.TripPlanSummary;
import thesis.project.gu.model.StopCoordinate;
import thesis.project.gu.model.RouteRecommendationContext;
import thesis.project.gu.model.RouteChoice;
import thesis.project.gu.model.ModeSummary;
import thesis.project.gu.model.RouteSegmentSuggestion;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.response.PlanDraftResponse;
import thesis.project.gu.service.MapService;
import thesis.project.gu.service.PlanPrewarmService;
import thesis.project.gu.service.PlanProcessorService;
import thesis.project.gu.service.PlanService;
import thesis.project.gu.service.PlaceHeuristicService;
import thesis.project.gu.service.RuntimeMetricsService;
import thesis.project.gu.service.TripAiService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {
    private final PlanService planService;
    private final TripAiService aiService;
    private final CacheManager cacheManager;
    private final RuntimeMetricsService runtimeMetricsService;
    private final PlanPrewarmService planPrewarmService;
    private final MapService mapService;
    private final WeatherApiClient weatherApiClient;
    private final GooglePlacesClient googlePlacesClient;
    private final PlanProcessorService planProcessorService;
    private final PlaceHeuristicService placeHeuristicService;
    private final ExecutorService routeExecutor;
    private final com.github.benmanes.caffeine.cache.Cache<String, RouteDaySuggestion> routeSuggestionDayCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public PlanController(
            PlanService planService,
            TripAiService aiService,
            CacheManager cacheManager,
            RuntimeMetricsService runtimeMetricsService,
            PlanPrewarmService planPrewarmService,
            MapService mapService,
            WeatherApiClient weatherApiClient,
            GooglePlacesClient googlePlacesClient,
            PlanProcessorService planProcessorService,
            PlaceHeuristicService placeHeuristicService,
            @Qualifier("routeExecutor") ExecutorService routeExecutor
    ) {
        this.planService = planService;
        this.aiService = aiService;
        this.cacheManager = cacheManager;
        this.runtimeMetricsService = runtimeMetricsService;
        this.planPrewarmService = planPrewarmService;
        this.mapService = mapService;
        this.weatherApiClient = weatherApiClient;
        this.googlePlacesClient = googlePlacesClient;
        this.planProcessorService = planProcessorService;
        this.placeHeuristicService = placeHeuristicService;
        this.routeExecutor = routeExecutor;
    }

    @PostMapping(value = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PlanDraftResponse draft(
            @RequestBody @Valid CreatePlanReq req,
            @RequestHeader(name = "X-Defer-Copy-Polish", required = false) Boolean deferCopyPolish
    )
            throws Exception {
        long startedAt = System.currentTimeMillis();
        boolean redisHit = isAiPlanCacheHit(req);
        try {
            PlanDraftResponse result = planProcessorService.generateDraft(req, redisHit, Boolean.TRUE.equals(deferCopyPolish));
            runtimeMetricsService.recordPlanGenerateRequest("draft", redisHit, System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (NoApiKeyException | InputRequiredException | JsonProcessingException e) {
            runtimeMetricsService.recordPlanGenerateRequest("draft", redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanGenerateRequest("draft", redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PostMapping(value = "/route-suggestions/day", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RouteDaySuggestion routeSuggestionDay(@RequestBody RouteSuggestionDayRequest request) {
        PlanDraftResponse draft = request == null ? null : request.draft();
        Integer requestedDayIndex = request == null ? null : request.dayIndex();
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || requestedDayIndex == null) {
            return new RouteDaySuggestion(requestedDayIndex == null ? 0 : requestedDayIndex, List.of());
        }
        PlanDraftResponse.DayPlan day = draft.daysPlan().stream()
                .filter(candidate -> candidate != null && candidate.dayIndex() == requestedDayIndex)
                .findFirst()
                .orElse(null);
        if (day == null) {
            return new RouteDaySuggestion(requestedDayIndex, List.of());
        }
        RouteRecommendationContext recommendationContext = routeRecommendationContext(
                draft,
                request == null ? null : request.departureDate()
        );
        return cachedRouteSuggestionForDay(draft, day, recommendationContext, request.departureDate());
    }

    private RouteDaySuggestion cachedRouteSuggestionForDay(
            PlanDraftResponse draft,
            PlanDraftResponse.DayPlan day,
            RouteRecommendationContext recommendationContext,
            String departureDate
    ) {
        String cacheKey = routeSuggestionDayCacheKey(draft, day, departureDate);
        if (cacheKey.isBlank()) {
            return routeSuggestionForDay(draft, day, recommendationContext);
        }
        return routeSuggestionDayCache.get(cacheKey, ignored -> routeSuggestionForDay(draft, day, recommendationContext));
    }

    private RouteDaySuggestion routeSuggestionForDay(
            PlanDraftResponse draft,
            PlanDraftResponse.DayPlan day,
            RouteRecommendationContext recommendationContext
    ) {
        if (day == null) {
            return new RouteDaySuggestion(0, List.of());
        }
        List<PlanDraftResponse.Place> stops = day.stops() == null ? List.of() : day.stops();
        if (stops.size() < 2) {
            return new RouteDaySuggestion(day.dayIndex(), resolveHotelStartRouteSuggestion(day.dayIndex(), day, recommendationContext));
        }
        List<StopCoordinate> coordinates = resolveStopCoordinatesInParallel(stops, shouldTrustDraftCoordinates(draft));
        List<RouteSegmentSuggestion> segments = new ArrayList<>(resolveHotelStartRouteSuggestion(day.dayIndex(), day, recommendationContext));
        segments.addAll(resolveRouteSuggestions(day.dayIndex(), stops, coordinates, recommendationContext));
        return new RouteDaySuggestion(day.dayIndex(), segments);
    }

    private String routeSuggestionDayCacheKey(PlanDraftResponse draft, PlanDraftResponse.DayPlan day, String departureDate) {
        if (draft == null || day == null) {
            return "";
        }
        StringBuilder source = new StringBuilder(512)
                .append("city=").append(nullToEmpty(draft.city()))
                .append("|days=").append(draft.days())
                .append("|pace=").append(nullToEmpty(draft.pace()))
                .append("|kids=").append(draft.party() == null ? "" : draft.party().kids())
                .append("|departure=").append(nullToEmpty(departureDate))
                .append("|copy=").append(nullToEmpty(draft.copyPolishStatus()))
                .append("|day=").append(day.dayIndex());
        appendRouteCachePlace(source, "hotel", day.hotel());
        if (day.stops() != null) {
            for (int i = 0; i < day.stops().size(); i++) {
                appendRouteCachePlace(source, "stop-" + i, day.stops().get(i));
            }
        }
        return "route-suggestion-day:" + sha256Url(source.toString());
    }

    private void appendRouteCachePlace(StringBuilder source, String label, PlanDraftResponse.Place place) {
        source.append('|').append(label).append('=');
        if (place == null) {
            source.append("null");
            return;
        }
        source.append(nullToEmpty(place.name()))
                .append('@').append(nullToEmpty(place.addressLine()))
                .append('@').append(nullToEmpty(place.startTime()))
                .append('-').append(nullToEmpty(place.endTime()))
                .append('@').append(place.latitude() == null ? "" : roundCoordinate(place.latitude()))
                .append(',')
                .append(place.longitude() == null ? "" : roundCoordinate(place.longitude()));
    }

    private String sha256Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @PostMapping(value = "/weather", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public WeatherForecastResponse weatherForecast(@RequestBody WeatherForecastRequest request) {
        if (request == null || request.city() == null || request.city().isBlank()
                || request.departureDate() == null || request.departureDate().isBlank()
                || request.days() == null || request.days() < 1) {
            return new WeatherForecastResponse(false, List.of());
        }
        LocalDate departureDate = parseDate(request.departureDate());
        if (departureDate == null) {
            return new WeatherForecastResponse(false, List.of());
        }
        WeatherApiClient.Forecast forecast = weatherApiClient.forecast(request.city(), request.departureDate(), request.days());

        List<WeatherDaySummary> days = new ArrayList<>();
        for (int i = 0; i < request.days(); i++) {
            LocalDate date = departureDate.plusDays(i);
            WeatherApiClient.WeatherDay weatherDay = forecast == null || forecast.isEmpty() ? null : forecast.days().get(date.toString());
            if (weatherDay == null) {
                days.add(defaultSunnyWeatherDay(i + 1, date));
                continue;
            }
            days.add(new WeatherDaySummary(
                    i + 1,
                    date.toString(),
                    weatherDay.condition(),
                    weatherDay.dailyChanceOfRain(),
                    weatherDay.avgTempC(),
                    weatherDay.maxTempC(),
                    weatherDay.minTempC(),
                    forecast.rainyAt(date, null)
            ));
        }
        return new WeatherForecastResponse(true, days);
    }

    private WeatherDaySummary defaultSunnyWeatherDay(int dayIndex, LocalDate date) {
        return new WeatherDaySummary(
                dayIndex,
                date == null ? "" : date.toString(),
                "Sunny",
                0,
                null,
                null,
                null,
                false
        );
    }

    private RouteRecommendationContext routeRecommendationContext(PlanDraftResponse draft, String departureDateRaw) {
        int kids = draft.party() == null || draft.party().kids() == null ? 0 : draft.party().kids();
        LocalDate departureDate = parseDate(departureDateRaw);
        WeatherApiClient.Forecast forecast = weatherApiClient.forecast(draft.city(), departureDateRaw, draft.days());
        return new RouteRecommendationContext(kids > 0, departureDate, forecast);
    }

    private List<RouteSegmentSuggestion> resolveHotelStartRouteSuggestion(
            int dayIndex,
            PlanDraftResponse.DayPlan day,
            RouteRecommendationContext recommendationContext
    ) {
        if (day == null || day.hotel() == null || day.stops() == null || day.stops().isEmpty()) {
            return List.of();
        }
        PlanDraftResponse.Place hotel = day.hotel();
        PlanDraftResponse.Place firstStop = day.stops().getFirst();
        boolean trustDraftCoordinates = shouldTrustDraftCoordinates(day);
        StopCoordinate hotelCoordinate = resolveStopCoordinateSafely(hotel, trustDraftCoordinates);
        StopCoordinate firstStopCoordinate = resolveStopCoordinateSafely(firstStop, trustDraftCoordinates);
        List<StopCoordinate> coordinates = new ArrayList<>();
        coordinates.add(hotelCoordinate);
        coordinates.add(firstStopCoordinate);
        return List.of(resolveRouteSuggestion(
                dayIndex,
                List.of(hotel, firstStop),
                coordinates,
                1,
                -1,
                recommendationContext
        ));
    }

    private List<RouteSegmentSuggestion> resolveRouteSuggestions(
            int dayIndex,
            List<PlanDraftResponse.Place> stops,
            List<StopCoordinate> coordinates,
            RouteRecommendationContext recommendationContext
    ) {
        List<RouteSegmentSuggestion> fallbackSegments = new ArrayList<>();
        for (int i = 1; i < stops.size(); i++) {
            fallbackSegments.add(emptyRouteSegment(stops, i));
        }
        if (coordinates == null || coordinates.size() != stops.size()) {
            return fallbackSegments;
        }

        List<CompletableFuture<RouteSegmentSuggestion>> futures = new ArrayList<>();
        for (int i = 1; i < stops.size(); i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(
                    () -> resolveRouteSuggestion(dayIndex, stops, coordinates, index, index - 1, recommendationContext),
                    routeExecutor
            ));
        }
        return futures.stream()
                .map(future -> {
                    try {
                        return future.join();
                    } catch (CompletionException e) {
                        return null;
                    }
                })
                .filter(segment -> segment != null)
                .toList();
    }

    private RouteSegmentSuggestion resolveRouteSuggestion(
            int dayIndex,
            List<PlanDraftResponse.Place> stops,
            List<StopCoordinate> coordinates,
            int index,
            int segmentIndex,
            RouteRecommendationContext recommendationContext
    ) {
        PlanDraftResponse.Place fromStop = stops.get(index - 1);
        PlanDraftResponse.Place toStop = stops.get(index);
        StopCoordinate from = coordinates.get(index - 1);
        StopCoordinate to = coordinates.get(index);
        if (isThemeParkLunchToContinuation(fromStop, toStop, from, to)) {
            ModeSummary walk = new ModeSummary("walk", 1, 0);
            return new RouteSegmentSuggestion(
                    segmentIndex,
                    safeStopName(fromStop),
                    safeStopName(toStop),
                    "walk",
                    walk.durationMinutes(),
                    walk.distanceMeters(),
                    walk,
                    null,
                    null,
                    "This is a theme park dining break followed by the same park visit; no separate routed transfer is needed.",
                    false
            );
        }
        if (from == null || to == null) {
            if (isRouteSuggestionOptional(fromStop) || isRouteSuggestionOptional(toStop)) {
                return emptyRouteSegment(stops, index, segmentIndex, "Optional scenic connector has unstable coordinates; check it manually on the map.");
            }
            return emptyRouteSegment(stops, index, segmentIndex, "Missing coordinates for one or both stops.");
        }

        double straightLineMeters = haversineMeters(from.lat(), from.lon(), to.lat(), to.lon());
        if (straightLineMeters < 30) {
            ModeSummary walk = new ModeSummary("walk", 1, Math.max(1, (int) Math.round(straightLineMeters)));
            return new RouteSegmentSuggestion(
                    segmentIndex,
                    safeStopName(fromStop),
                    safeStopName(toStop),
                    "walk",
                    walk.durationMinutes(),
                    walk.distanceMeters(),
                    walk,
                    null,
                    null,
                    "These stops are in the same precinct; no separate routed transfer is needed.",
                    false
            );
        }

        String origin = from.asLatLon();
        String destination = to.asLatLon();
        boolean rainy = isRainyDuringSegment(recommendationContext, dayIndex, fromStop, toStop);
        RouteChoice routeChoice = resolveRouteChoice(origin, destination, recommendationContext, rainy);
        ModeSummary walk = routeChoice.walk();
        ModeSummary transit = routeChoice.transit();
        ModeSummary car = routeChoice.car();
        ModeSummary recommended = routeChoice.recommended();

        String hint = null;
        if (recommended == null) {
            hint = "Route suggestion unavailable";
        } else if ("car".equals(recommended.mode()) && transit == null) {
            hint = "Transit summary unavailable; car/taxi is the practical fallback.";
        } else if ("walk".equals(recommended.mode()) && rainy) {
            hint = "Rain is possible around this segment; walking remains reasonable but keep transit/taxi as a backup.";
        } else if ("walk".equals(recommended.mode()) && recommended.durationMinutes() > 30) {
            hint = "Walking is possible but long; consider transit or taxi.";
        }

        return new RouteSegmentSuggestion(
                segmentIndex,
                safeStopName(fromStop),
                safeStopName(toStop),
                recommended == null ? null : recommended.mode(),
                recommended == null ? null : recommended.durationMinutes(),
                recommended == null ? null : recommended.distanceMeters(),
                walk,
                transit,
                car,
                hint,
                false
        );
    }

    private boolean isThemeParkLunchToContinuation(
            PlanDraftResponse.Place fromStop,
            PlanDraftResponse.Place toStop,
            StopCoordinate from,
            StopCoordinate to
    ) {
        if (!hasMealSlot(fromStop, "lunch") || !isThemeParkContinuationStop(toStop)) {
            return false;
        }
        if (from != null && to != null && haversineMeters(from.lat(), from.lon(), to.lat(), to.lon()) <= 2_000) {
            return true;
        }
        String fromMapsUri = nullToEmpty(fromStop.googleMapsUri());
        String toMapsUri = nullToEmpty(toStop.googleMapsUri());
        if (!fromMapsUri.isBlank() && fromMapsUri.equals(toMapsUri)) {
            return true;
        }
        String fromArea = themeParkAnchorArea(fromStop);
        String toArea = themeParkAnchorArea(toStop);
        if (!fromArea.isBlank() && fromArea.equals(toArea)) {
            return true;
        }
        String fromName = placeHeuristicService.normalizeSearchText(fromStop.name());
        String toName = placeHeuristicService.normalizeSearchText(toStop.name());
        return placeHeuristicService.commonSignificantTokenCount(fromName, toName) > 0;
    }

    private boolean isThemeParkContinuationStop(PlanDraftResponse.Place stop) {
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

    private String themeParkAnchorArea(PlanDraftResponse.Place stop) {
        if (stop == null) {
            return "";
        }
        String area = normalizeSlot(stop.preferredArea());
        if (area.isEmpty()) {
            area = normalizeSlot(stop.suburb());
        }
        return area;
    }

    private RouteSegmentSuggestion emptyRouteSegment(List<PlanDraftResponse.Place> stops, int index) {
        return emptyRouteSegment(stops, index, index - 1, "Route suggestion unavailable");
    }

    private RouteSegmentSuggestion emptyRouteSegment(List<PlanDraftResponse.Place> stops, int index, String hint) {
        return emptyRouteSegment(stops, index, index - 1, hint);
    }

    private RouteSegmentSuggestion emptyRouteSegment(List<PlanDraftResponse.Place> stops, int index, int segmentIndex, String hint) {
        return new RouteSegmentSuggestion(
                segmentIndex,
                safeStopName(stops.get(index - 1)),
                safeStopName(stops.get(index)),
                null,
                null,
                null,
                null,
                null,
                null,
                hint,
                false
        );
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

    private RouteChoice resolveRouteChoice(
            String origin,
            String destination,
            RouteRecommendationContext context,
            boolean rainy
    ) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return new RouteChoice(null, null, null, null);
        }
        ModeSummary walk = modeSummary("walk", () -> mapService.walk_summary(origin, destination));
        ModeSummary transit = modeSummary("transit", () -> mapService.transit_summary(origin, destination));
        ModeSummary car = normalizeCarSummary(modeSummary("car", () -> mapService.car_summary(origin, destination)));
        ModeSummary recommended = recommendMode(walk, transit, car, context, rainy);
        return new RouteChoice(walk, transit, car, recommended);
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadius = 6371000D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private boolean isRouteSuggestionOptional(PlanDraftResponse.Place stop) {
        if (stop == null) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
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

    private boolean isRainyDuringSegment(
            RouteRecommendationContext context,
            int dayIndex,
            PlanDraftResponse.Place fromStop,
            PlanDraftResponse.Place toStop
    ) {
        if (context == null || context.departureDate() == null || context.forecast() == null || context.forecast().isEmpty()) {
            return false;
        }
        LocalDate date = context.departureDate().plusDays(Math.max(1, dayIndex) - 1L);
        LocalTime time = parseLocalTime(toStop == null ? null : toStop.startTime());
        if (time == null) {
            time = parseLocalTime(fromStop == null ? null : fromStop.endTime());
        }
        return context.forecast().rainyAt(date, time);
    }

    private ModeSummary recommendMode(
            ModeSummary walk,
            ModeSummary transit,
            ModeSummary car,
            RouteRecommendationContext context,
            boolean rainy
    ) {
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
            if (walk != null
                    && walk.durationMinutes() <= walkCompareThreshold
                    && transit.durationMinutes() - walk.durationMinutes() > -8) {
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

    @PostMapping("/raw")
    public Map<String, Object> generateRaw(
            @RequestBody @Valid CreatePlanReq req,
            @RequestHeader(name = "X-Defer-Copy-Polish", required = false) Boolean deferCopyPolish,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) throws Exception {
        long startedAt = System.currentTimeMillis();
        boolean redisHit = isAiPlanCacheHit(req);
        try {
            PlanDraftResponse draft = planProcessorService.generateDraft(req, redisHit, Boolean.TRUE.equals(deferCopyPolish));

            String preview;
            try {
                preview = aiService.render(draft, req.budget());
            } catch (Exception e) {
                preview = "(Tip) Preview rendering failed, but the structured draft was parsed successfully.";
            }

            Long aiRawId = 666666L;
            Long planId = null;
            if (userId != null) {
                planId = aiService.saveDraftPlan(userId, draft, req.budget(), null, req.style(), req.pace(), req.departureDate());
                if (planId != null) {
                    planPrewarmService.prewarmPlanAsync(planId, draft.city());
                }
            }

            runtimeMetricsService.recordPlanGenerateRequest("raw", redisHit, System.currentTimeMillis() - startedAt, true);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("aiRawId", aiRawId);
            response.put("planId", planId);
            response.put("draft", draft);
            response.put("preview", preview);
            return response;
        } catch (Exception e) {
            runtimeMetricsService.recordPlanGenerateRequest("raw", redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PostMapping(value = "/copy-polish", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PlanDraftResponse copyPolish(
            @RequestBody CopyPolishRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        PlanDraftResponse draft = request == null ? null : request.draft();
        if (draft == null) {
            return null;
        }
        PlanDraftResponse polished = planProcessorService.applyCopyPolishPatch(draft);
        Long planId = request == null ? null : request.planId();
        if (userId != null && planId != null && planId > 0 && polished != null) {
            planService.updatePlanCopy(userId, planId, polished);
        }
        return polished;
    }

    @PatchMapping("/{planId}/favorite")
    public Map<String, Object> updateFavorite(
            @PathVariable long planId,
            @RequestParam boolean value,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        if (userId == null) {
            throw new NavigatorException(ErrorCode.UNAUTHORIZED, "Login required");
        }

        planService.setFavorite(userId, planId, value);
        return Map.of("planId", planId, "favorite", value, "status", "ok");
    }

    @GetMapping("/me")
    public PlanService.Paged<TripPlanSummary> myPlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean favorite,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
            PlanService.Paged<TripPlanSummary> result = planService.listMyPlans(userId, page, size, favorite);
            runtimeMetricsService.recordPlanViewRequest("list", System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanViewRequest("list", System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @GetMapping("/me/favorites")
    public PlanService.Paged<TripPlanSummary> myFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
            PlanService.Paged<TripPlanSummary> result = planService.listMyPlans(userId, page, size, true);
            runtimeMetricsService.recordPlanViewRequest("favorites", System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanViewRequest("favorites", System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @GetMapping("/{planId}")
    public PlanService.PlanDetail myPlanDetail(
            @PathVariable long planId,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
            PlanService.PlanDetail result = planService.getMyPlanDetail(userId, planId);
            runtimeMetricsService.recordPlanViewRequest("detail", System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanViewRequest("detail", System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PatchMapping("/{planId}/title")
    public Map<String, Object> renamePlan(
            @PathVariable long planId,
            @RequestParam(required = false) String title,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
        planService.renamePlan(userId, planId, title);
        return Map.of("planId", planId, "title", title, "status", "ok");
    }

    @DeleteMapping("/{planId}")
    public Map<String, Object> deletePlan(
            @PathVariable long planId,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        if (userId == null) throw ErrorCode.UNAUTHORIZED.ex("Login required");
        planService.deletePlan(userId, planId);
        return Map.of("planId", planId, "status", "deleted");
    }

    private boolean isAiPlanCacheHit(CreatePlanReq req) {
        Cache cache = cacheManager.getCache("ai_plan_raw");
        if (cache == null) return false;
        String key = planService.buildAiPlanCacheKey(req);
        return cache.get(key) != null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String roundCoordinate(Double value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private boolean shouldRefreshCoordinate(PlanDraftResponse.Place stop) {
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

    private String safeStopName(PlanDraftResponse.Place stop) {
        if (stop == null || stop.name() == null) {
            return null;
        }
        return stop.name().trim();
    }

    private String displayArea(PlanDraftResponse.Place stop) {
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

    public List<StopCoordinate> resolveStopCoordinatesInParallel(List<PlanDraftResponse.Place> stops) {
        return resolveStopCoordinatesInParallel(stops, false);
    }

    public List<StopCoordinate> resolveStopCoordinatesInParallel(List<PlanDraftResponse.Place> stops, boolean trustDraftCoordinates) {
        if (stops == null || stops.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<StopCoordinate>> futures = stops.stream()
                .map(stop -> CompletableFuture.supplyAsync(() -> resolveStopCoordinateSafely(stop, trustDraftCoordinates), routeExecutor))
                .toList();
        return futures.stream().map(this::joinNullable).toList();
    }

    private StopCoordinate joinNullable(CompletableFuture<StopCoordinate> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
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

    public StopCoordinate resolveStopCoordinateSafely(PlanDraftResponse.Place stop) {
        return resolveStopCoordinateSafely(stop, false);
    }

    public StopCoordinate resolveStopCoordinateSafely(PlanDraftResponse.Place stop, boolean trustDraftCoordinates) {
        StopLocation location = resolveStopLocationSafely(stop, trustDraftCoordinates);
        return location == null ? null : new StopCoordinate(location.lat(), location.lon());
    }

    private StopLocation resolveStopLocationSafely(PlanDraftResponse.Place stop) {
        return resolveStopLocationSafely(stop, false);
    }

    private StopLocation resolveStopLocationSafely(PlanDraftResponse.Place stop, boolean trustDraftCoordinates) {
        StopLocation existingLocation = existingStopLocation(stop);
        try {
            if (trustDraftCoordinates && existingLocation != null) {
                return existingLocation;
            }
            if (stop != null && stop.latitude() != null && stop.longitude() != null && !shouldRefreshCoordinate(stop)) {
                return existingLocation;
            }
            if (stop == null) {
                return null;
            }

            if (isStrongPoiCandidate(stop)) {
                StopLocation placesLocation = resolveStrongPoiLocationWithPlaces(stop);
                if (placesLocation != null && placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(placesLocation.lat(), placesLocation.lon()), stop.city())) {
                    return placesLocation;
                }
            }
            if (placeHeuristicService.isNavigationAnchorCandidate(stop.name())) {
                StopLocation placesLocation = resolveNavigationAnchorLocationWithPlaces(stop);
                if (placesLocation != null && placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(placesLocation.lat(), placesLocation.lon()), stop.city())) {
                    return placesLocation;
                }
            }

            GeoResponse response = null;
            if (isRouteSuggestionOptional(stop) && stop != null && stop.name() != null && !stop.name().isBlank()) {
                response = mapService.geocodeWithoutBackfill(stop.name(), stop.city());
                StopCoordinate coordinate = placeHeuristicService.coordinateFromGeocode(response);
                if (coordinate != null && placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    return new StopLocation(coordinate.lat(), coordinate.lon(), null);
                }
            }

            boolean navigationAnchorCandidate = placeHeuristicService.isNavigationAnchorCandidate(stop.name())
                    || placeHeuristicService.isParkStopForCoordinateRefresh(stop);
            List<String> candidates = placeHeuristicService.geocodeCandidates(stop, isStrongPoiCandidate(stop), navigationAnchorCandidate);
            for (String candidate : candidates) {
                response = mapService.geocode(candidate, stop.city());
                StopCoordinate coordinate = placeHeuristicService.coordinateFromGeocode(response);
                if (coordinate != null && placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
                    return new StopLocation(coordinate.lat(), coordinate.lon(), null);
                }
            }
        } catch (RuntimeException e) {
            return existingLocation;
        }
        return existingLocation;
    }

    private StopLocation existingStopLocation(PlanDraftResponse.Place stop) {
        if (stop == null || stop.latitude() == null || stop.longitude() == null) {
            return null;
        }
        StopCoordinate coordinate = new StopCoordinate(stop.latitude(), stop.longitude());
        if (!placeHeuristicService.isCoordinatePlausibleForCity(coordinate, stop.city())) {
            return null;
        }
        return new StopLocation(stop.latitude(), stop.longitude(), null);
    }

    private boolean shouldTrustDraftCoordinates(PlanDraftResponse draft) {
        if (draft == null) {
            return false;
        }
        String status = draft.copyPolishStatus() == null ? "" : draft.copyPolishStatus().trim().toLowerCase(Locale.ROOT);
        return "deferred".equals(status)
                || "local-fast".equals(status)
                || status.startsWith("local-fast");
    }

    private boolean shouldTrustDraftCoordinates(PlanDraftResponse.DayPlan day) {
        if (day == null) {
            return false;
        }
        return Stream.concat(
                        Stream.of(day.hotel()),
                        day.stops() == null ? Stream.empty() : day.stops().stream()
                )
                .filter(Objects::nonNull)
                .anyMatch(stop -> stop.latitude() != null && stop.longitude() != null);
    }

    private StopLocation resolveNavigationAnchorLocationWithPlaces(PlanDraftResponse.Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank() || !googlePlacesClient.isEnabled()) {
            return null;
        }
        for (String query : navigationAnchorPlaceSearchQueries(stop)) {
            StopLocation location = googlePlacesClient.searchText(query, stop.city()).stream()
                    .filter(candidate -> Double.isFinite(candidate.lat()) && Double.isFinite(candidate.lng()))
                    .filter(candidate -> placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(candidate.lat(), candidate.lng()), stop.city()))
                    .map(candidate -> new RankedPlaceCoordinate(candidate, scoreNavigationAnchorCandidate(stop, candidate)))
                    .filter(candidate -> candidate.score() >= 120)
                    .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                    .map(candidate -> new StopLocation(
                            candidate.candidate().lat(),
                            candidate.candidate().lng(),
                            candidate.candidate().formattedAddress()
                    ))
                    .findFirst()
                    .orElse(null);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    private StopLocation resolveStrongPoiLocationWithPlaces(PlanDraftResponse.Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank() || !googlePlacesClient.isEnabled()) {
            return null;
        }
        for (String query : strongPoiPlaceSearchQueries(stop)) {
            StopLocation location = googlePlacesClient.searchText(query, stop.city()).stream()
                    .filter(candidate -> Double.isFinite(candidate.lat()) && Double.isFinite(candidate.lng()))
                    .filter(candidate -> placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(candidate.lat(), candidate.lng()), stop.city()))
                    .map(candidate -> new RankedPlaceCoordinate(candidate, scoreStrongPoiPlaceCandidate(stop, candidate)))
                    .filter(candidate -> isAcceptableStrongPoiPlaceCandidate(stop, candidate))
                    .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                    .map(candidate -> new StopLocation(
                            candidate.candidate().lat(),
                            candidate.candidate().lng(),
                            candidate.candidate().formattedAddress()
                    ))
                    .findFirst()
                    .orElse(null);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    private List<String> strongPoiPlaceSearchQueries(PlanDraftResponse.Place stop) {
        List<String> queries = new ArrayList<>();
        String name = stop.name() == null ? "" : stop.name().trim();
        String coreName = placeHeuristicService.corePoiName(name);
        String address = stop.addressLine() == null ? "" : stop.addressLine().trim();
        if (!coreName.isBlank() && !address.isBlank()) {
            addUnique(queries, coreName + ", " + address);
        }
        if (!name.isBlank() && !address.isBlank() && !name.equalsIgnoreCase(coreName)) {
            addUnique(queries, name + ", " + address);
        }
        if (!coreName.isBlank()) {
            addUnique(queries, coreName);
        }
        if (!name.isBlank()) {
            addUnique(queries, name);
        }
        return queries;
    }

    private List<String> navigationAnchorPlaceSearchQueries(PlanDraftResponse.Place stop) {
        List<String> queries = new ArrayList<>();
        String name = stop.name() == null ? "" : stop.name().trim();
        String city = stop.city() == null ? "" : stop.city().trim();
        if (!name.isBlank() && !city.isBlank()) {
            addUnique(queries, name + ", " + city);
        }
        if (!name.isBlank()) {
            addUnique(queries, name);
        }
        return queries;
    }

    private boolean isAcceptableStrongPoiPlaceCandidate(PlanDraftResponse.Place stop, RankedPlaceCoordinate ranked) {
        if (ranked == null || ranked.candidate() == null || ranked.score() < 120) {
            return false;
        }
        String expectedName = placeHeuristicService.normalizeSearchText(stop.name());
        String candidateName = placeHeuristicService.normalizeSearchText(ranked.candidate().name());
        if (!expectedName.isBlank()
                && !candidateName.isBlank()
                && !(candidateName.contains(expectedName) || expectedName.contains(candidateName))) {
            return false;
        }
        String expectedAddress = placeHeuristicService.normalizeSearchText(stop.addressLine());
        if (hasSpecificAddressAnchor(expectedAddress)) {
            String candidateAddress = placeHeuristicService.normalizeSearchText(ranked.candidate().formattedAddress());
            return placeHeuristicService.commonSignificantTokenCount(expectedAddress, candidateAddress) > 0;
        }
        return true;
    }

    private boolean hasSpecificAddressAnchor(String expectedAddress) {
        if (expectedAddress == null || expectedAddress.isBlank()) {
            return false;
        }
        for (String token : expectedAddress.split("\\s+")) {
            if (token.length() >= 4 && !placeHeuristicService.isLowSignalPoiToken(token)) {
                return true;
            }
        }
        return false;
    }

    private int scoreStrongPoiPlaceCandidate(PlanDraftResponse.Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String expectedName = placeHeuristicService.normalizeSearchText(stop.name());
        String expectedAddress = placeHeuristicService.normalizeSearchText(stop.addressLine());
        String candidateText = placeHeuristicService.normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        int score = placeHeuristicService.commonSignificantTokenCount(expectedName, candidateText) * 80;
        score += placeHeuristicService.commonSignificantTokenCount(expectedAddress, candidateText) * 15;
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("museum") || types.contains("tourist_attraction") || types.contains("art_gallery")) {
            score += 80;
        }
        if (types.contains("point_of_interest") || types.contains("establishment")) {
            score += 20;
        }
        return score;
    }

    private int scoreNavigationAnchorCandidate(PlanDraftResponse.Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String expectedName = placeHeuristicService.normalizeSearchText(stop.name());
        String candidateText = placeHeuristicService.normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        int score = placeHeuristicService.commonSignificantTokenCount(expectedName, candidateText) * 70;
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("tourist_attraction")
                || types.contains("point_of_interest")
                || types.contains("establishment")
                || types.contains("park")) {
            score += 50;
        }
        String candidateName = placeHeuristicService.normalizeSearchText(candidate.name());
        if (!expectedName.isBlank() && !candidateName.isBlank() && (expectedName.contains(candidateName) || candidateName.contains(expectedName))) {
            score += 80;
        }
        return score;
    }

    private boolean isStrongPoiCandidate(PlanDraftResponse.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
        return "museum".equals(category)
                || name.contains("museum")
                || name.contains("gallery")
                || name.contains("goma")
                || name.contains("qagoma")
                || name.contains("planetarium")
                || name.contains("sciencentre")
                || name.contains("art gallery")
                || name.contains("shrine")
                || name.contains("memorial")
                || name.contains("monument");
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

    private int parseTimeMinutes(String value) {
        if (value == null) return -1;
        String text = value.trim();
        if (!text.matches("^\\d{2}:\\d{2}$")) return -1;
        int hours = Integer.parseInt(text.substring(0, 2));
        int minutes = Integer.parseInt(text.substring(3, 5));
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return -1;
        return hours * 60 + minutes;
    }

    private LocalTime parseLocalTime(String value) {
        int minutes = parseTimeMinutes(value);
        if (minutes < 0) {
            return null;
        }
        return LocalTime.of(minutes / 60, minutes % 60);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String normalizeSlot(String slot) {
        return slot == null ? "" : slot.trim().toLowerCase();
    }

    private boolean isStrictMealStop(PlanDraftResponse.Place stop) {
        if (stop == null) return false;
        String mealType = normalizeSlot(stop.mealType());
        String timeSlot = normalizeSlot(stop.timeSlot());
        return "lunch".equals(mealType)
                || "dinner".equals(mealType)
                || "lunch".equals(timeSlot)
                || "dinner".equals(timeSlot);
    }

    private boolean hasMealSlot(PlanDraftResponse.Place stop, String slot) {
        return stop != null && (slot.equals(normalizeSlot(stop.mealType())) || slot.equals(normalizeSlot(stop.timeSlot())));
    }

    private boolean isThemeParkLikeStop(PlanDraftResponse.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase();
        return "theme_park".equals(category)
                || "amusement".equals(category)
                || "amusement_park".equals(category)
                || text.contains("theme park")
                || text.contains("amusement park")
                || text.contains("water park");
    }

    private interface RouteSummarySupplier {
        MapService.RouteSummary get();
    }

    private record StopLocation(double lat, double lon, String addressLine) {}

    private record RankedPlaceCoordinate(GooglePlacesClient.PlaceCandidate candidate, int score) {}

    public record RouteSuggestionDayRequest(PlanDraftResponse draft, Integer dayIndex, Integer budget, String departureDate) {}

    public record CopyPolishRequest(Long planId, PlanDraftResponse draft) {}

    public record WeatherForecastRequest(String city, String departureDate, Integer days) {}

    public record WeatherForecastResponse(boolean available, List<WeatherDaySummary> days) {}

    public record WeatherDaySummary(
            int dayIndex,
            String date,
            String condition,
            int chanceOfRain,
            Double avgTempC,
            Double maxTempC,
            Double minTempC,
            boolean rainy
    ) {}

    public record RouteDaySuggestion(int dayIndex, List<RouteSegmentSuggestion> segments) {}
}
