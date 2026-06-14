package thesis.project.gu.routing.application;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.routing.domain.ModeSummary;
import thesis.project.gu.routing.domain.RouteChoice;
import thesis.project.gu.routing.domain.RouteDaySuggestion;
import thesis.project.gu.routing.domain.RouteRecommendationContext;
import thesis.project.gu.routing.domain.RouteSegmentSuggestion;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;
import thesis.project.gu.weather.infrastructure.WeatherApiClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

@Service
public class RouteSuggestionService {
    private final MapService mapService;
    private final WeatherApiClient weatherApiClient;
    private final GooglePlacesClient googlePlacesClient;
    private final PlaceHeuristicService placeHeuristicService;
    private final RouteSuggestionCacheKeyBuilder cacheKeyBuilder;
    private final RouteModeRecommendationService routeModeRecommendationService;
    private final ExecutorService routeExecutor;
    private final com.github.benmanes.caffeine.cache.Cache<String, RouteDaySuggestion> routeSuggestionDayCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public RouteSuggestionService(
            MapService mapService,
            WeatherApiClient weatherApiClient,
            GooglePlacesClient googlePlacesClient,
            PlaceHeuristicService placeHeuristicService,
            RouteSuggestionCacheKeyBuilder cacheKeyBuilder,
            RouteModeRecommendationService routeModeRecommendationService,
            @Qualifier("routeExecutor") ExecutorService routeExecutor
    ) {
        this.mapService = mapService;
        this.weatherApiClient = weatherApiClient;
        this.googlePlacesClient = googlePlacesClient;
        this.placeHeuristicService = placeHeuristicService;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.routeModeRecommendationService = routeModeRecommendationService;
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
        List<StopCoordinate> coordinates = resolveStopCoordinatesInParallel(stops, shouldTrustDraftCoordinates(draft));
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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

    private record StopLocation(double lat, double lon, String addressLine) {}

    private record RankedPlaceCoordinate(GooglePlacesClient.PlaceCandidate candidate, int score) {}
}
