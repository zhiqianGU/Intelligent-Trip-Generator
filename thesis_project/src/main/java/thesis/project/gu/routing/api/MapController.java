package thesis.project.gu.routing.api;

import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.routing.api.dto.PlaceSuggestionDto;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.catalog.domain.Place;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;
import thesis.project.gu.routing.infrastructure.dto.GeoRouteResponse;
import thesis.project.gu.routing.application.MapService;
import thesis.project.gu.observability.application.RuntimeMetricsService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/map")
@Validated
public class MapController {

    private final MapService mapService;
    private final ExecutorService routeExecutor;
    private final CacheManager cacheManager;
    private final RuntimeMetricsService runtimeMetricsService;

    public MapController(
            MapService mapService,
            @Qualifier("routeExecutor")
            ExecutorService routeExecutor,
            CacheManager cacheManager,
            RuntimeMetricsService runtimeMetricsService
    ) {
        this.mapService = mapService;
        this.routeExecutor = routeExecutor;
        this.cacheManager = cacheManager;
        this.runtimeMetricsService = runtimeMetricsService;
    }

    @GetMapping(value = "/geocode", produces = MediaType.APPLICATION_JSON_VALUE)
    public GeoResponse geocode(
            @RequestParam @NotBlank(message = "address cannot be blank") String address,
            @RequestParam(required = false) String city
    ) {
        long startedAt = System.currentTimeMillis();
        boolean redisHit = isCacheHit("geocode", cacheKey(address, city));
        try {
            GeoResponse result = mapService.geocode(address, city);
            runtimeMetricsService.recordGeocodeRequest(redisHit, System.currentTimeMillis() - startedAt);
            return result;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordGeocodeRequest(redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PostMapping(value = "/geocode/persist", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Place> geocodeAndPersist(
            @RequestParam @NotBlank String address,
            @RequestParam(required = false) String city
    ) {
        return mapService.geocodeAndPersist(address, city);
    }

    @GetMapping(value = "/suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PlaceSuggestionDto> suggestions(
            @RequestParam @NotBlank(message = "address cannot be blank") String address,
            @RequestParam(required = false) String city
    ) {
        return mapService.suggestions(address, city);
    }

    @GetMapping("/route")
    public Map<String, Object> unifiedRoute(
            @RequestParam String type,
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam(required = false) String city
    ) {
        long startedAt = System.currentTimeMillis();
        boolean redisHit = isCacheHit(routeCacheName(type), STR."\{origin}:\{destination}");

        TimedFuture<GeoRouteResponse> mainFuture = timedSupplyAsync(
                () -> resolveMainRoute(type, origin, destination),
                routeExecutor
        );
        TimedFuture<MapService.RouteSummary> walkSummary = timedSupplyAsync(
                () -> safeSummary(() -> mapService.walk_summary(origin, destination)),
                routeExecutor
        );
        TimedFuture<MapService.RouteSummary> carSummary = timedSupplyAsync(
                () -> safeSummary(() -> mapService.car_summary(origin, destination)),
                routeExecutor
        );
        TimedFuture<MapService.RouteSummary> transitSummary = timedSupplyAsync(
                () -> safeSummary(() -> mapService.transit_summary(origin, destination)),
                routeExecutor
        );

        try {
            CompletableFuture.allOf(mainFuture.future(), walkSummary.future(), carSummary.future(), transitSummary.future()).join();

            GeoRouteResponse main = mainFuture.future().join();
            Map<String, Object> metadata = resolveRouteMetadata(type, main);

            long serialEstimateMs =
                    mainFuture.elapsedMs().join()
                            + walkSummary.elapsedMs().join()
                            + carSummary.elapsedMs().join()
                            + transitSummary.elapsedMs().join();
            long parallelWallMs = System.currentTimeMillis() - startedAt;

            runtimeMetricsService.recordRouteRequest(redisHit, parallelWallMs, true);
            runtimeMetricsService.recordRouteParallelMetrics(parallelWallMs, serialEstimateMs);

            Map<String, Object> result = new HashMap<>();
            result.put("main", main);
            result.put("walk_summary", walkSummary.future().join());
            result.put("car_summary", carSummary.future().join());
            result.put("transit_summary", transitSummary.future().join());
            result.put("route_meta", metadata);
            return result;
        } catch (CompletionException e) {
            runtimeMetricsService.recordRouteRequest(redisHit, System.currentTimeMillis() - startedAt, false);
            throw unwrapRouteException(e);
        } catch (RuntimeException e) {
            runtimeMetricsService.recordRouteRequest(redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    private GeoRouteResponse resolveMainRoute(String type, String origin, String destination) {
        if ("drive".equals(type)) {
            return mapService.car_route(origin, destination);
        }
        if ("walk".equals(type)) {
            return resolveWalkOrDirectLine(origin, destination);
        }
        if ("transit".equals(type)) {
            try {
                return mapService.transit_route(origin, destination);
            } catch (NavigatorException e) {
                GeoRouteResponse fallbackWalk = resolveWalkOrDirectLine(origin, destination);
                runtimeMetricsService.recordRouteFallback(
                        isDirectLineHint(fallbackWalk) ? "direct_line_hint" : "transit_to_walk"
                );
                return fallbackWalk;
            }
        }
        throw ErrorCode.PARAM_ERROR.ex(STR."unsupported type: \{type}");
    }

    private GeoRouteResponse resolveWalkOrDirectLine(String origin, String destination) {
        try {
            return mapService.walk_route(origin, destination);
        } catch (NavigatorException e) {
            runtimeMetricsService.recordRouteFallback("direct_line_hint");
            return mapService.directLineHint(origin, destination, "walk");
        }
    }

    private Map<String, Object> resolveRouteMetadata(String requestedType, GeoRouteResponse main) {
        String actualMode = extractMode(main);
        boolean directLine = isDirectLineHint(main);
        String fallbackType = null;
        if ("transit".equals(requestedType) && "walk".equals(actualMode)) {
            fallbackType = "transit_to_walk";
        } else if (directLine) {
            fallbackType = "direct_line_hint";
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requestedMode", requestedType);
        metadata.put("actualMode", actualMode);
        metadata.put("fallbackType", fallbackType);
        metadata.put("fallbackApplied", fallbackType != null);
        if (directLine) {
            metadata.put("hintMessage", "No routed path was available. Showing a direct line hint only.");
        } else if ("transit_to_walk".equals(fallbackType)) {
            metadata.put("hintMessage", "Transit route unavailable. Fell back to walking route.");
        }
        return metadata;
    }

    private boolean isDirectLineHint(GeoRouteResponse route) {
        String mode = extractMode(route);
        return mode != null && mode.contains("direct_line_hint");
    }

    private String extractMode(GeoRouteResponse route) {
        if (route == null || route.features() == null || route.features().isEmpty()) return null;
        GeoRouteResponse.Properties props = route.features().getFirst().properties();
        return props == null ? null : props.mode();
    }

    private MapService.RouteSummary safeSummary(Supplier<MapService.RouteSummary> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private RuntimeException unwrapRouteException(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(cause);
    }

    private boolean isCacheHit(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) return false;
        return cache.get(key) != null;
    }

    private String cacheKey(String address, String city) {
        String normalizedAddress = address == null ? "" : address.trim().toLowerCase();
        String normalizedCity = city == null ? "" : city.trim().toLowerCase();
        return normalizedAddress + "|" + normalizedCity;
    }

    private String routeCacheName(String type) {
        return switch (type) {
            case "drive" -> "car_route";
            case "walk" -> "walk_route";
            case "transit" -> "transit_route";
            default -> throw ErrorCode.PARAM_ERROR.ex("unsupported type: " + type);
        };
    }

    private <T> TimedFuture<T> timedSupplyAsync(Supplier<T> supplier, ExecutorService executor) {
        CompletableFuture<Long> elapsedMs = new CompletableFuture<>();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                return supplier.get();
            } finally {
                elapsedMs.complete(System.currentTimeMillis() - startedAt);
            }
        }, executor);
        return new TimedFuture<>(future, elapsedMs);
    }

    private record TimedFuture<T>(CompletableFuture<T> future, CompletableFuture<Long> elapsedMs) {
    }
}
