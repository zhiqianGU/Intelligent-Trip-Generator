package thesis.project.gu.routing.application;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.routing.domain.ModeSummary;
import thesis.project.gu.routing.domain.RouteChoice;
import thesis.project.gu.routing.domain.RouteDaySuggestion;
import thesis.project.gu.routing.domain.RouteRecommendationContext;
import thesis.project.gu.routing.domain.RouteSegmentSuggestion;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.weather.infrastructure.WeatherApiClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

@Service
public class RouteSuggestionService {
    private final WeatherApiClient weatherApiClient;
    private final PlaceHeuristicService placeHeuristicService;
    private final RouteSuggestionCacheKeyBuilder cacheKeyBuilder;
    private final RouteModeRecommendationService routeModeRecommendationService;
    private final RouteCoordinateResolver routeCoordinateResolver;
    private final ExecutorService routeExecutor;
    private final com.github.benmanes.caffeine.cache.Cache<String, RouteDaySuggestion> routeSuggestionDayCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public RouteSuggestionService(
            WeatherApiClient weatherApiClient,
            PlaceHeuristicService placeHeuristicService,
            RouteSuggestionCacheKeyBuilder cacheKeyBuilder,
            RouteModeRecommendationService routeModeRecommendationService,
            RouteCoordinateResolver routeCoordinateResolver,
            @Qualifier("routeExecutor") ExecutorService routeExecutor
    ) {
        this.weatherApiClient = weatherApiClient;
        this.placeHeuristicService = placeHeuristicService;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.routeModeRecommendationService = routeModeRecommendationService;
        this.routeCoordinateResolver = routeCoordinateResolver;
        this.routeExecutor = routeExecutor;
    }

    public RouteDaySuggestion buildRouteSuggestionDay(PlanDraftResponse draft, Integer dayIndex, String departureDate) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || dayIndex == null) {
            return new RouteDaySuggestion(dayIndex == null ? 0 : dayIndex, List.of());
        }
        PlanDraftResponse.DayPlan day = draft.daysPlan().stream()
                .filter(candidate -> candidate != null && candidate.dayIndex() == dayIndex)
                .findFirst()
                .orElse(null);
        if (day == null) {
            return new RouteDaySuggestion(dayIndex, List.of());
        }
        RouteRecommendationContext recommendationContext = routeRecommendationContext(draft, departureDate);
        return cachedRouteSuggestionForDay(draft, day, recommendationContext, departureDate);
    }

    private RouteDaySuggestion cachedRouteSuggestionForDay(
            PlanDraftResponse draft,
            PlanDraftResponse.DayPlan day,
            RouteRecommendationContext recommendationContext,
            String departureDate
    ) {
        String cacheKey = cacheKeyBuilder.buildDayKey(draft, day, departureDate);
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
        List<StopCoordinate> coordinates = routeCoordinateResolver.resolveStopCoordinatesInParallel(
                stops,
                routeCoordinateResolver.shouldTrustDraftCoordinates(draft)
        );
        List<RouteSegmentSuggestion> segments = new ArrayList<>(resolveHotelStartRouteSuggestion(day.dayIndex(), day, recommendationContext));
        segments.addAll(resolveRouteSuggestions(day.dayIndex(), stops, coordinates, recommendationContext));
        return new RouteDaySuggestion(day.dayIndex(), segments);
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
        boolean trustDraftCoordinates = routeCoordinateResolver.shouldTrustDraftCoordinates(day);
        StopCoordinate hotelCoordinate = routeCoordinateResolver.resolveStopCoordinateSafely(hotel, trustDraftCoordinates);
        StopCoordinate firstStopCoordinate = routeCoordinateResolver.resolveStopCoordinateSafely(firstStop, trustDraftCoordinates);
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
            if (routeCoordinateResolver.isRouteSuggestionOptional(fromStop) || routeCoordinateResolver.isRouteSuggestionOptional(toStop)) {
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
        RouteChoice routeChoice = routeModeRecommendationService.resolveRouteChoice(origin, destination, recommendationContext, rainy);
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

    private String safeStopName(PlanDraftResponse.Place stop) {
        if (stop == null || stop.name() == null) {
            return null;
        }
        return stop.name().trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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

}
