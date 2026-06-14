package thesis.project.gu.routing.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

@Service
public class RouteCoordinateResolver {
    private final MapService mapService;
    private final GooglePlacesClient googlePlacesClient;
    private final PlaceHeuristicService placeHeuristicService;
    private final ExecutorService routeExecutor;

    public RouteCoordinateResolver(
            MapService mapService,
            GooglePlacesClient googlePlacesClient,
            PlaceHeuristicService placeHeuristicService,
            @Qualifier("routeExecutor") ExecutorService routeExecutor
    ) {
        this.mapService = mapService;
        this.googlePlacesClient = googlePlacesClient;
        this.placeHeuristicService = placeHeuristicService;
        this.routeExecutor = routeExecutor;
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

    public StopCoordinate resolveStopCoordinateSafely(PlanDraftResponse.Place stop) {
        return resolveStopCoordinateSafely(stop, false);
    }

    public StopCoordinate resolveStopCoordinateSafely(PlanDraftResponse.Place stop, boolean trustDraftCoordinates) {
        StopLocation location = resolveStopLocationSafely(stop, trustDraftCoordinates);
        return location == null ? null : new StopCoordinate(location.lat(), location.lon());
    }

    public boolean shouldTrustDraftCoordinates(PlanDraftResponse draft) {
        if (draft == null) {
            return false;
        }
        String status = draft.copyPolishStatus() == null ? "" : draft.copyPolishStatus().trim().toLowerCase(Locale.ROOT);
        return "deferred".equals(status)
                || "local-fast".equals(status)
                || status.startsWith("local-fast");
    }

    public boolean shouldTrustDraftCoordinates(PlanDraftResponse.DayPlan day) {
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

    public boolean isRouteSuggestionOptional(PlanDraftResponse.Place stop) {
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

    private StopCoordinate joinNullable(CompletableFuture<StopCoordinate> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return null;
        }
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
            if (isRouteSuggestionOptional(stop) && stop.name() != null && !stop.name().isBlank()) {
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
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
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

    private boolean isThemeParkLikeStop(PlanDraftResponse.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase(Locale.ROOT);
        return "theme_park".equals(category)
                || "amusement".equals(category)
                || "amusement_park".equals(category)
                || text.contains("theme park")
                || text.contains("amusement park")
                || text.contains("water park");
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

    private String normalizeSlot(String slot) {
        return slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record StopLocation(double lat, double lon, String addressLine) {}

    private record RankedPlaceCoordinate(GooglePlacesClient.PlaceCandidate candidate, int score) {}
}
