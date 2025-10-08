package thesis.project.gu.client;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


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
import thesis.project.gu.response.CarRouteResponse;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.response.GeoRouteResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;


@Component
public class AmapClient {
    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
    private final RestTemplate restTemplate;
    private final AmapProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public AmapClient(RestTemplate restTemplate, AmapProperties props, ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.restTemplate = restTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public GeoResponse geocode(String address, @Nullable String city) {
        String base = (props.baseUrl() == null ? "" : props.baseUrl().trim());
        String key  = (props.key() == null ? "" : props.key().trim());
        String addr = address == null ? "" : address.trim();

        if (key.isBlank()) {
            log.error("Geoapify apiKey is blank! Check config");
            throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify apiKey missing");
        }

        boolean hasCity = city != null && !city.isBlank();
        String c = hasCity ? city.trim() : null;

        // ---- (A) 如有城市，先取城市中心 lon/lat 用于 proximity bias ----
        Double biasLon = null, biasLat = null;
        if (hasCity) {
            try {
                URI cityUri = UriComponentsBuilder.fromUriString(base + "/geocode/search")
                        .queryParam("apiKey", key)
                        .queryParam("format", "geojson")
                        .queryParam("type", "city")
                        .queryParam("filter", "countrycode:au")
                        .queryParam("limit", 1)
                        .queryParam("text", c)
                        .encode(StandardCharsets.UTF_8)
                        .build().toUri();

                HttpRequest cityReq = HttpRequest.newBuilder()
                        .uri(cityUri).GET()
                        .header("Accept", "application/json").build();

                HttpResponse<String> cityResp = httpClient.send(cityReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (cityResp.statusCode() == 200) {
                    GeoResponse cityGeo = objectMapper.readValue(cityResp.body(), GeoResponse.class);
                    if (cityGeo.features() != null && !cityGeo.features().isEmpty()) {
                        // GeoJSON 坐标是 [lon, lat]
                        var coord = cityGeo.features().get(0).geometry().coordinates();
                        if (coord != null && coord.size() >= 2) {
                            biasLon = coord.get(0);
                            biasLat = coord.get(1);
                        }
                    }
                } else {
                    log.warn("City lookup HTTP {}: {}", cityResp.statusCode(), cityResp.body());
                }
            } catch (Exception ex) {
                log.warn("City lookup failed, fallback without proximity bias", ex);
            }
        }

        // ---- (B) 正式的 POI 搜索 ----
        UriComponentsBuilder ub = UriComponentsBuilder.fromUriString(base + "/geocode/search")
                .queryParam("apiKey", key)
                .queryParam("format", "geojson")
                .queryParam("limit", 5)
                .queryParam("filter", "countrycode:au");

        // 用 free-form，更适合“POI 名称 + 城市”
        if (hasCity) ub.queryParam("text", addr + ", " + c);
        else ub.queryParam("text", addr);

        // 正确的 bias 用法：proximity（若拿到了城市中心）
        if (biasLon != null && biasLat != null) {
            ub.queryParam("bias", String.format("proximity:%s,%s", biasLon, biasLat));
        }

        URI uri = ub.encode(StandardCharsets.UTF_8).build().toUri();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri).GET()
                .header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").build();

        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            String body = resp.body();
            log.debug("Geoapify Geocode URL: {}", uri);
            log.debug("Geoapify status={}, body={}", code, body);

            if (code != 200) {
                throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify HTTP " + code + ": " + body);
            }

            GeoResponse result = objectMapper.readValue(body, GeoResponse.class);
            if (result == null || result.features() == null || result.features().isEmpty()) {
                throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify geocoding failed: empty result");
            }
            return result;
        } catch (IOException | InterruptedException e) {
            log.error("Geoapify geocode failed", e);
            throw new NavigatorException(ErrorCode.GEOCODE_FAIL);
        }
    }



    public GeoRouteResponse route(String mode, String originLatLon, String destLatLon) {
        URI uri = buildRoutingUri(mode, originLatLon, destLatLon, true);
        var resp = restTemplate.getForEntity(uri, String.class);
        log.debug("Geoapify route URL: {}", uri);
        log.debug("Geoapify status={}, body={}", resp.getStatusCodeValue(), resp.getBody());

        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify HTTP " + resp.getStatusCodeValue() + ": " + resp.getBody());
        }
        try {
            return objectMapper.readValue(resp.getBody(), GeoRouteResponse.class);
        } catch (IOException e) {
            throw new NavigatorException(ErrorCode.INTERNAL_ERROR, "解析 Geoapify 路线失败: " + e.getMessage());
        }
    }

    // 原始 JSON（给摘要快速解析）
    public JsonNode routeRaw(String mode, String originLatLon, String destLatLon) {
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
                try { return objectMapper.readTree(body); }
                catch (IOException e) { log.warn("parse summary failed", e); return null; }
            }

            // 简单重试：429/5xx 退避
            if ((code == 429 || (code >= 500 && code < 600)) && attempts < 3) {
                try { Thread.sleep(200L * attempts); } catch (InterruptedException ignored) {}
                continue;
            }
            return null; // 其它非 200 直接返回 null，summary 就是 null
        }
    }

    private URI buildRoutingUri(String mode, String originLatLon, String destLatLon, boolean geojson) {
        String base = props.baseUrl().trim(); // https://api.geoapify.com/v1
        String key  = props.key().trim();
        return UriComponentsBuilder.fromUriString(base + "/routing")
                .queryParam("waypoints", originLatLon + "|" + destLatLon) // 注意：lat,lon|lat,lon
                .queryParam("mode", mode)
                .queryParam("details", "instruction_details") // 步骤更丰富
                .queryParam("apiKey", key)
                .queryParam("format", geojson ? "geojson" : "json")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    /* 便捷包装：保持你原来的方法名语义 */
    public GeoRouteResponse drivingRoute(String originLatLon, String destLatLon) { return route("drive", originLatLon, destLatLon); }
    public GeoRouteResponse walkingRoute(String originLatLon, String destLatLon) { return route("walk",  originLatLon, destLatLon); }
    public GeoRouteResponse transitRoute(String originLatLon, String destLatLon) { return route("transit", originLatLon, destLatLon); }

    public JsonNode drivingRouteRaw(String originLatLon, String destLatLon) { return routeRaw("drive", originLatLon, destLatLon); }
    public JsonNode walkingRouteRaw(String originLatLon, String destLatLon) { return routeRaw("walk",  originLatLon, destLatLon); }
    public JsonNode transitRouteRaw(String originLatLon, String destLatLon) { return routeRaw("transit",originLatLon, destLatLon); }


    private JsonNode readTree(String json) {
        try {
            return AmapClient.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new NavigatorException(ErrorCode.INTERNAL_ERROR, "解析高德响应失败: " + e.getMessage());
        }
    }

}

