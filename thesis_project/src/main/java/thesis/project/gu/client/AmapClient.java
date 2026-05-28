package thesis.project.gu.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.common.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import thesis.project.gu.config.AmapProperties;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.response.GeoRouteResponse;
import thesis.project.gu.service.RuntimeMetricsService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class AmapClient {
    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private final RestTemplate restTemplate;
    private final AmapProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RuntimeMetricsService runtimeMetricsService;

    public AmapClient(
            RestTemplate restTemplate,
            AmapProperties props,
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            RuntimeMetricsService runtimeMetricsService
    ) {
        this.restTemplate = restTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
        this.runtimeMetricsService = runtimeMetricsService;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @CircuitBreaker(name = "geoapifyGeocode", fallbackMethod = "geocodeFallback")
    @RateLimiter(name = "geoapifyGeocode", fallbackMethod = "geocodeFallback")
    public GeoResponse geocode(String address, @Nullable String city) {
        long startedAt = System.currentTimeMillis();
        String base = props.baseUrl() == null ? "" : props.baseUrl().trim();
        String key = props.key() == null ? "" : props.key().trim();
        String addr = address == null ? "" : address.trim();

        if (key.isBlank()) {
            log.error("Geoapify apiKey is blank");
            throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify apiKey missing");
        }

        boolean hasCity = city != null && !city.isBlank();
        String cityValue = hasCity ? city.trim() : null;

        Double biasLon = null;
        Double biasLat = null;
        if (hasCity) {
            try {
                URI cityUri = UriComponentsBuilder.fromUriString(base + "/geocode/search")
                        .queryParam("apiKey", key)
                        .queryParam("format", "geojson")
                        .queryParam("type", "city")
                        .queryParam("filter", "countrycode:au")
                        .queryParam("limit", 1)
                        .queryParam("text", cityValue)
                        .encode(StandardCharsets.UTF_8)
                        .build()
                        .toUri();

                HttpRequest cityReq = HttpRequest.newBuilder()
                        .uri(cityUri)
                        .GET()
                        .header("Accept", "application/json")
                        .build();

                HttpResponse<String> cityResp = httpClient.send(cityReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (cityResp.statusCode() == 200) {
                    GeoResponse cityGeo = objectMapper.readValue(cityResp.body(), GeoResponse.class);
                    if (cityGeo.features() != null && !cityGeo.features().isEmpty()) {
                        var coord = cityGeo.features().get(0).geometry().coordinates();
                        if (coord != null && coord.size() >= 2) {
                            biasLon = coord.get(0);
                            biasLat = coord.get(1);
                        }
                    }
                } else {
                log.debug("City lookup HTTP {}: {}", cityResp.statusCode(), cityResp.body());
            }
        } catch (Exception ex) {
                log.debug("City lookup failed, fallback without proximity bias", ex);
            }
        }

        UriComponentsBuilder ub = UriComponentsBuilder.fromUriString(base + "/geocode/search")
                .queryParam("apiKey", key)
                .queryParam("format", "geojson")
                .queryParam("limit", 5)
                .queryParam("filter", "countrycode:au");

        ub.queryParam("text", hasCity ? addr + ", " + cityValue : addr);
        if (biasLon != null && biasLat != null) {
            ub.queryParam("bias", String.format("proximity:%s,%s", biasLon, biasLat));
        }

        URI uri = ub.encode(StandardCharsets.UTF_8).build().toUri();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .build();

        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            String body = resp.body();
            log.debug("Geoapify geocode URL: {}", uri);
            log.debug("Geoapify status={}, body={}", code, body);

            if (code != 200) {
                runtimeMetricsService.recordExternalGeocode(System.currentTimeMillis() - startedAt, false);
                log.debug("external_api category=geocode status=fail duration_ms={} http_status={}",
                        System.currentTimeMillis() - startedAt, code);
                throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify HTTP " + code + ": " + body);
            }

            GeoResponse result = objectMapper.readValue(body, GeoResponse.class);
            if (result == null || result.features() == null || result.features().isEmpty()) {
                runtimeMetricsService.recordExternalGeocode(System.currentTimeMillis() - startedAt, false);
                log.debug("external_api category=geocode status=empty duration_ms={}",
                        System.currentTimeMillis() - startedAt);
                throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify geocoding failed: empty result");
            }

            runtimeMetricsService.recordExternalGeocode(System.currentTimeMillis() - startedAt, true);
            log.debug("external_api category=geocode status=success duration_ms={}",
                    System.currentTimeMillis() - startedAt);
            return result;
        } catch (IOException | InterruptedException e) {
            runtimeMetricsService.recordExternalGeocode(System.currentTimeMillis() - startedAt, false);
            log.debug("external_api category=geocode status=exception duration_ms={}",
                    System.currentTimeMillis() - startedAt);
            log.debug("Geoapify geocode failed", e);
            throw new NavigatorException(ErrorCode.GEOCODE_FAIL);
        }
    }

    private GeoResponse geocodeFallback(String address, @Nullable String city, Throwable cause) {
        log.debug("Geoapify geocode degraded address={} city={} reason={}", address, city, cause.toString());
        throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify geocoding unavailable: " + cause.getMessage());
    }

    @CircuitBreaker(name = "geoapifyRoute", fallbackMethod = "routeFallback")
    @RateLimiter(name = "geoapifyRoute", fallbackMethod = "routeFallback")
    public GeoRouteResponse route(String mode, String originLatLon, String destLatLon) {
        long startedAt = System.currentTimeMillis();
        URI uri = buildRoutingUri(mode, originLatLon, destLatLon, true);
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
            log.debug("Geoapify route URL: {}", uri);
            log.debug("Geoapify status={}, body={}", resp.getStatusCodeValue(), resp.getBody());

            if (!resp.getStatusCode().is2xxSuccessful()) {
                runtimeMetricsService.recordExternalRoute(System.currentTimeMillis() - startedAt, false);
                log.debug("external_api category=route mode={} status=fail duration_ms={} http_status={}",
                        mode, System.currentTimeMillis() - startedAt, resp.getStatusCodeValue());
                throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify HTTP " + resp.getStatusCodeValue() + ": " + resp.getBody());
            }

            GeoRouteResponse result = objectMapper.readValue(resp.getBody(), GeoRouteResponse.class);
            runtimeMetricsService.recordExternalRoute(System.currentTimeMillis() - startedAt, true);
            log.debug("external_api category=route mode={} status=success duration_ms={}",
                    mode, System.currentTimeMillis() - startedAt);
            return result;
        } catch (IOException e) {
            runtimeMetricsService.recordExternalRoute(System.currentTimeMillis() - startedAt, false);
            log.debug("external_api category=route mode={} status=parse_error duration_ms={}",
                    mode, System.currentTimeMillis() - startedAt);
            throw new NavigatorException(ErrorCode.INTERNAL_ERROR, "Parse Geoapify route failed: " + e.getMessage());
        } catch (RuntimeException e) {
            if (!(e instanceof NavigatorException)) {
                runtimeMetricsService.recordExternalRoute(System.currentTimeMillis() - startedAt, false);
                log.debug("external_api category=route mode={} status=exception duration_ms={}",
                        mode, System.currentTimeMillis() - startedAt);
            }
            throw e;
        }
    }

    private GeoRouteResponse routeFallback(String mode, String originLatLon, String destLatLon, Throwable cause) {
        log.debug("Geoapify route degraded mode={} origin={} destination={} reason={}",
                mode, originLatLon, destLatLon, cause.toString());
        try {
            return directLineFallback(originLatLon, destLatLon, mode);
        } catch (RuntimeException e) {
            throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify route unavailable: " + cause.getMessage());
        }
    }

    private GeoRouteResponse directLineFallback(String originLatLon, String destLatLon, String requestedMode) {
        double[] from = parseLatLon(originLatLon);
        double[] to = parseLatLon(destLatLon);
        double distanceMeters = haversineMeters(from[0], from[1], to[0], to[1]);
        double durationSeconds = Math.max(0D, distanceMeters / 1.35D);
        GeoRouteResponse.Properties properties = new GeoRouteResponse.Properties(
                requestedMode == null || requestedMode.isBlank() ? "direct_line" : requestedMode + "_direct_line_hint",
                "metric",
                distanceMeters,
                "meters",
                durationSeconds,
                List.of()
        );
        GeoRouteResponse.Feature feature = new GeoRouteResponse.Feature(
                "Feature",
                properties,
                new GeoRouteResponse.Geometry("LineString", List.of(List.of(
                        List.of(from[1], from[0]),
                        List.of(to[1], to[0])
                )))
        );
        return new GeoRouteResponse("FeatureCollection", List.of(feature));
    }

    private double[] parseLatLon(String latLon) {
        if (latLon == null || latLon.isBlank()) {
            throw ErrorCode.PARAM_ERROR.ex("origin/destination cannot be blank");
        }
        String[] parts = latLon.split(",");
        if (parts.length != 2) {
            throw ErrorCode.PARAM_ERROR.ex("origin/destination must be lat,lon");
        }
        return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
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

    @CircuitBreaker(name = "geoapifyRoute", fallbackMethod = "routeRawFallback")
    @RateLimiter(name = "geoapifyRoute", fallbackMethod = "routeRawFallback")
    public JsonNode routeRaw(String mode, String originLatLon, String destLatLon) {
        long startedAt = System.currentTimeMillis();
        URI uri = buildRoutingUri(mode, originLatLon, destLatLon, true);
        int attempts = 0;
        while (true) {
            attempts++;
            ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
            int code = resp.getStatusCodeValue();
            String body = resp.getBody();
            log.debug("Geoapify {} summary URL: {}, status={}, bodySnippet={}", mode, uri, code,
                    body != null ? body.substring(0, Math.min(200, body.length())) : "null");

            if (code == 200 && body != null) {
                try {
                    JsonNode result = objectMapper.readTree(body);
                    runtimeMetricsService.recordExternalSummary(System.currentTimeMillis() - startedAt, true);
                    log.debug("external_api category=summary mode={} status=success duration_ms={} attempts={}",
                            mode, System.currentTimeMillis() - startedAt, attempts);
                    return result;
                } catch (IOException e) {
                    runtimeMetricsService.recordExternalSummary(System.currentTimeMillis() - startedAt, false);
                    log.debug("external_api category=summary mode={} status=parse_error duration_ms={} attempts={}",
                            mode, System.currentTimeMillis() - startedAt, attempts);
                    log.debug("Parse summary failed", e);
                    return null;
                }
            }

            if ((code == 429 || (code >= 500 && code < 600)) && attempts < 3) {
                try {
                    Thread.sleep(200L * attempts);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }

            runtimeMetricsService.recordExternalSummary(System.currentTimeMillis() - startedAt, false);
            log.debug("external_api category=summary mode={} status=fail duration_ms={} http_status={} attempts={}",
                    mode, System.currentTimeMillis() - startedAt, code, attempts);
            return null;
        }
    }

    private JsonNode routeRawFallback(String mode, String originLatLon, String destLatLon, Throwable cause) {
        log.debug("Geoapify route summary degraded mode={} origin={} destination={} reason={}",
                mode, originLatLon, destLatLon, cause.toString());
        return null;
    }

    private URI buildRoutingUri(String mode, String originLatLon, String destLatLon, boolean geojson) {
        String base = props.baseUrl().trim();
        String key = props.key().trim();
        return UriComponentsBuilder.fromUriString(base + "/routing")
                .queryParam("waypoints", originLatLon + "|" + destLatLon)
                .queryParam("mode", mode)
                .queryParam("details", "instruction_details")
                .queryParam("apiKey", key)
                .queryParam("format", geojson ? "geojson" : "json")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    public GeoRouteResponse drivingRoute(String originLatLon, String destLatLon) {
        return route("drive", originLatLon, destLatLon);
    }

    public GeoRouteResponse walkingRoute(String originLatLon, String destLatLon) {
        return route("walk", originLatLon, destLatLon);
    }

    public GeoRouteResponse transitRoute(String originLatLon, String destLatLon) {
        return route("transit", originLatLon, destLatLon);
    }

    public JsonNode drivingRouteRaw(String originLatLon, String destLatLon) {
        return routeRaw("drive", originLatLon, destLatLon);
    }

    public JsonNode walkingRouteRaw(String originLatLon, String destLatLon) {
        return routeRaw("walk", originLatLon, destLatLon);
    }

    public JsonNode transitRouteRaw(String originLatLon, String destLatLon) {
        return routeRaw("transit", originLatLon, destLatLon);
    }
}
