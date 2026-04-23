package thesis.project.gu.service;



import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.common.lang.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thesis.project.gu.client.AmapClient;
import thesis.project.gu.client.GoogleGeocodingClient;
import thesis.project.gu.dto.PlaceSuggestionDto;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.mapper.PlaceMapper;
import thesis.project.gu.model.Place;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.response.GeoRouteResponse;
import java.util.LinkedHashMap;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MapService {
    private static final Logger log = LoggerFactory.getLogger(MapService.class);
    private final AmapClient amap;
    @Getter
    private final CacheManager cacheManager;
    private final PlaceMapper placeMapper;
    private final RuntimeMetricsService runtimeMetricsService;
    private final GoogleGeocodingClient googleGeocodingClient;

    public MapService(AmapClient amap, CacheManager cacheManager, PlaceMapper placeMapper, RuntimeMetricsService runtimeMetricsService, GoogleGeocodingClient googleGeocodingClient) {
        this.amap = amap;
        this.cacheManager = cacheManager;
        this.placeMapper = placeMapper;
        this.runtimeMetricsService = runtimeMetricsService;
        this.googleGeocodingClient = googleGeocodingClient;
    }

    private static String norm(String s) {
        return (s == null) ? "" : s.trim().toLowerCase();
    }

    @Cacheable(
            cacheNames = "geocode",
            key = "T(java.lang.String).format('%s|%s', (#address?:'').trim().toLowerCase(), (#city?:'').trim().toLowerCase())",
            unless = "#result == null || #result.features() == null || #result.features().isEmpty()"
    )
    public GeoResponse geocode(String address, @Nullable String city) {
        GoogleGeocodePlan googlePlan = googleGeocodePlan(address, city);
        var cachedPlaces = placeMapper.findCachedGeocodeMatches(address, city);
        cachedPlaces = filterPlausibleCachedPlaces(cachedPlaces, city);
        if (cachedPlaces != null && !cachedPlaces.isEmpty()) {
            if (googlePlan == null || (!googlePlan.bypassPlaceCache() && cachedPlaces.stream().anyMatch(this::isGoogleGeocodingPlace))) {
                runtimeMetricsService.recordGeocodeDbHit();
                return toGeoResponse(cachedPlaces);
            }
            log.info("Bypassed cached coordinates for large-area geocode query={}", address);
        }

        if (googlePlan != null) {
            GeoResponse google = tryGoogleGeocodePlan(address, city, googlePlan, true);
            if (google != null) {
                return google;
            }
        }

        GeoResponse override = resolveAnchorOverride(address, city, null);
        if (override != null) {
            return override;
        }

        GeoResponse resp = fetchExternalGeocode(address, city);
        backfillPlaceCoordinates(address, city, resp);
        return resp;
    }

    public GeoResponse geocodeWithoutBackfill(String address, @Nullable String city) {
        GoogleGeocodePlan googlePlan = googleGeocodePlan(address, city);
        var cachedPlaces = placeMapper.findCachedGeocodeMatches(address, city);
        cachedPlaces = filterPlausibleCachedPlaces(cachedPlaces, city);
        if (cachedPlaces != null && !cachedPlaces.isEmpty()) {
            if (googlePlan == null || (!googlePlan.bypassPlaceCache() && cachedPlaces.stream().anyMatch(this::isGoogleGeocodingPlace))) {
                runtimeMetricsService.recordGeocodeDbHit();
                return toGeoResponse(cachedPlaces);
            }
            log.info("Bypassed cached coordinates for large-area geocode query={}", address);
        }
        if (googlePlan != null) {
            GeoResponse google = tryGoogleGeocodePlan(address, city, googlePlan, false);
            if (google != null) {
                return google;
            }
        }
        GeoResponse override = resolveAnchorOverride(address, city, null);
        if (override != null) {
            return override;
        }
        return fetchExternalGeocode(address, city);
    }

    /** 新增：地理编码并把候选写入 place 表，返回写入后的 Place 列表 */
    public GeoResponse geocodeFreshWithoutBackfill(String address, @Nullable String city) {
        GoogleGeocodePlan googlePlan = googleGeocodePlan(address, city);
        if (googlePlan != null) {
            GeoResponse google = tryGoogleGeocodePlan(address, city, googlePlan, false);
            if (google != null) {
                return google;
            }
        }
        return fetchExternalGeocode(address, city);
    }

    @Cacheable(
            cacheNames = "geocode_persist",
            key = "T(java.lang.String).format('%s|%s', (#address?:'').trim().toLowerCase(), (#city?:'').trim().toLowerCase())",
            unless = "#result == null || #result.isEmpty()"
    )
    @Transactional
    public List<Place> geocodeAndPersist(String address, @Nullable String city) {
        GeoResponse geo = fetchExternalGeocode(address, city);

        List<Place> toSave = new ArrayList<>();
        for (GeoResponse.Feature f : geo.features()) {
            var p = f.properties();
            var place = new Place();
            place.setName(safe(p.formatted()));   // Geoapify常有 name，退化到 formatted
            place.setAddress(p.formatted());
            place.setCity(p.city());
            place.setDistrict(null);                        // 可从 rank/区域里再取
            place.setCountry(p.country());
            place.setLatitude(round6(p.lat()));
            place.setLongitude(round6(p.lon()));
            place.setSource("GEOAPIFY");
            place.setExternalRef(p.placeId());

            toSave.add(place);
        }

        if (!toSave.isEmpty()) {
            // 批量 UPSERT；已存在则更新，不存在则插入
            placeMapper.batchUpsert(toSave);
        }

        // 回查 id（可选——如果你需要返回带 id 的列表）
        List<Place> saved = new ArrayList<>(toSave.size());
        for (Place p : toSave) {
            Place db = placeMapper.findBySourceAndExternalRef(p.getSource(), p.getExternalRef());
            if (db != null) saved.add(db);
        }
        return saved;
    }

    private GeoResponse fetchExternalGeocode(String address, @Nullable String city) {
        GeoResponse resp = amap.geocode(address, city);
        if (resp == null || resp.features() == null || resp.features().isEmpty()) {
            throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify geocoding failed: empty result");
        }
        return resp;
    }

    private GoogleGeocodePlan googleGeocodePlan(String address, @Nullable String city) {
        String query = safe(address);
        if (query == null) return null;

        String normalized = norm(query);
        String normalizedCity = norm(city);
        if ("brisbane".equals(normalizedCity)) {
            if (normalized.contains("brisbane botanic gardens mt coot-tha")
                    || normalized.contains("brisbane botanic gardens mount coot-tha")
                    || normalized.contains("brisbane botanic gardens mt coot tha")
                    || normalized.contains("brisbane botanic gardens mount coot tha")) {
                return GoogleGeocodePlan.bypassCache(List.of(
                        new GoogleGeocodeCandidate("Brisbane Botanic Gardens Carpark, Brisbane", "GOOANC", GoogleGeocodeAcceptance.ANY)
                ));
            }
            if (normalized.contains("mount coot-tha lookout")
                    || normalized.contains("mount coot-tha summit lookout")
                    || normalized.contains("mt coot-tha lookout")
                    || (normalized.contains("mt coot-tha") && normalized.contains("lookout"))
                    || (normalized.contains("mount coot-tha") && normalized.contains("lookout"))) {
                return GoogleGeocodePlan.bypassCache(List.of(
                        new GoogleGeocodeCandidate(query, "GOOGEO", GoogleGeocodeAcceptance.MOUNT_COOT_THA_LOOKOUT),
                        new GoogleGeocodeCandidate("Mount Coot-tha Lookout Car Park, Brisbane", "GOOANC", GoogleGeocodeAcceptance.ANY)
                ));
            }
        }

        if (isStrongPoiGeocodeQuery(query)) {
            return GoogleGeocodePlan.bypassCache(List.of(
                    new GoogleGeocodeCandidate(query, "GOOGEO", GoogleGeocodeAcceptance.ANY)
            ));
        }

        if (isLargeAreaOrAmbiguous(query)) {
            return GoogleGeocodePlan.useCache(List.of(
                    new GoogleGeocodeCandidate(query, "GOOGEO", GoogleGeocodeAcceptance.ANY)
            ));
        }
        return null;
    }

    private boolean isStrongPoiGeocodeQuery(String value) {
        String text = norm(value);
        return text.contains("museum")
                || text.contains("gallery")
                || text.contains("goma")
                || text.contains("qagoma")
                || text.contains("planetarium")
                || text.contains("sciencentre")
                || text.contains("science centre")
                || text.contains("aquarium")
                || text.contains("art gallery")
                || text.contains("shrine")
                || text.contains("memorial")
                || text.contains("monument");
    }

    private boolean isLargeAreaOrAmbiguous(String value) {
        String text = norm(value);
        return text.contains("harbour")
                || text.contains("parklands")
                || isNaturalParkQuery(text)
                || text.contains(" botanic ")
                || text.contains("botanic gardens")
                || text.contains(" gardens")
                || text.endsWith("garden")
                || text.endsWith("gardens")
                || text.contains("lookout")
                || text.contains("summit")
                || text.contains("mount ")
                || text.contains("mt ")
                || text.contains("mountain")
                || text.contains("beach")
                || text.contains("reserve")
                || text.contains("riverwalk")
                || text.contains("waterfront")
                || text.contains("precinct")
                || text.contains("trail")
                || text.contains("national park");
    }

    private boolean isNaturalParkQuery(String text) {
        return (text.endsWith(" park") || text.contains(" park,") || text.contains(" park "))
                && !text.contains("car park")
                && !text.contains("parking")
                && !text.contains("park hotel")
                && !text.contains("parkroyal");
    }

    private void backfillPlaceCoordinates(String address, @Nullable String city, GeoResponse response) {
        if (response == null || response.features() == null || response.features().isEmpty()) return;

        var top = response.features().get(0);
        if (top == null || top.properties() == null) return;
        Double latitude = round6(top.properties().lat());
        Double longitude = round6(top.properties().lon());
        if (latitude == null || longitude == null) return;

        var targets = placeMapper.findGeocodeBackfillTargets(address, city);
        if (targets == null || targets.isEmpty()) return;

        for (Place target : targets) {
            if (target.getId() == null) continue;
            if (target.getLatitude() != null && target.getLongitude() != null) continue;
            placeMapper.updateCoordinatesById(target.getId(), latitude, longitude);
        }
    }

    private GeoResponse tryGoogleGeocodePlan(String originalQuery, @Nullable String city, GoogleGeocodePlan plan, boolean writeCache) {
        for (GoogleGeocodeCandidate candidate : plan.candidates()) {
            GeoResponse google = googleGeocodingClient.geocode(candidate.query(), city, candidate.source());
            if (google == null || google.features() == null || google.features().isEmpty()) {
                continue;
            }
            if (!isAcceptedGoogleGeocode(candidate, google)) {
                log.info("Rejected Google geocode candidate query={} original={} reason=semantic-mismatch",
                        candidate.query(), originalQuery);
                continue;
            }
            if (writeCache) {
                upsertGeocodeCache(originalQuery, city, google, candidate.source());
            }
            return google;
        }
        return null;
    }

    private boolean isAcceptedGoogleGeocode(GoogleGeocodeCandidate candidate, GeoResponse response) {
        if (candidate.acceptance() == GoogleGeocodeAcceptance.ANY) {
            return true;
        }
        GeoResponse.Feature top = response.features().getFirst();
        if (top == null || top.properties() == null) {
            return false;
        }
        String formatted = norm(top.properties().formatted());
        if (candidate.acceptance() == GoogleGeocodeAcceptance.MOUNT_COOT_THA_LOOKOUT) {
            return (formatted.contains("coot-tha") || formatted.contains("coot tha"))
                    && (formatted.contains("lookout") || formatted.contains("summit"));
        }
        return true;
    }

    private record GoogleGeocodePlan(List<GoogleGeocodeCandidate> candidates, boolean bypassPlaceCache) {
        private static GoogleGeocodePlan useCache(List<GoogleGeocodeCandidate> candidates) {
            return new GoogleGeocodePlan(candidates, false);
        }

        private static GoogleGeocodePlan bypassCache(List<GoogleGeocodeCandidate> candidates) {
            return new GoogleGeocodePlan(candidates, true);
        }
    }

    private record GoogleGeocodeCandidate(String query, String source, GoogleGeocodeAcceptance acceptance) {}

    private enum GoogleGeocodeAcceptance {
        ANY,
        MOUNT_COOT_THA_LOOKOUT
    }

    private void upsertGeocodeCache(String originalQuery, @Nullable String city, GeoResponse response, String source) {
        if (response == null || response.features() == null || response.features().isEmpty()) return;
        GeoResponse.Feature top = response.features().getFirst();
        if (top == null || top.properties() == null) return;
        GeoResponse.Properties properties = top.properties();
        Double latitude = round6(properties.lat());
        Double longitude = round6(properties.lon());
        if (latitude == null || longitude == null) return;

        Place place = new Place();
        place.setName(safe(originalQuery));
        place.setAddress(safe(properties.formatted(), originalQuery));
        place.setCity(safe(properties.city(), city));
        place.setCountry(properties.country());
        place.setLatitude(latitude);
        place.setLongitude(longitude);
        place.setSource(source);
        place.setExternalRef(source + ":" + norm(originalQuery) + "|" + norm(city));
        try {
            placeMapper.upsert(place);
        } catch (RuntimeException e) {
            log.warn("Skip geocode cache upsert source={} query={} reason={}", source, originalQuery, e.getMessage());
        }
    }

    private boolean isGoogleGeocodingPlace(Place place) {
        String source = place == null || place.getSource() == null ? "" : place.getSource().trim().toUpperCase();
        return source.startsWith("GOO");
    }

    private List<Place> filterPlausibleCachedPlaces(List<Place> places, @Nullable String city) {
        if (places == null || places.isEmpty()) {
            return places;
        }
        return places.stream()
                .filter(place -> isPlaceCoordinatePlausibleForCity(place, city))
                .toList();
    }

    private boolean isPlaceCoordinatePlausibleForCity(Place place, @Nullable String requestedCity) {
        if (place == null || place.getLatitude() == null || place.getLongitude() == null) {
            return false;
        }
        String city = safe(requestedCity, place.getCity());
        if (city == null) {
            return true;
        }
        String normalizedCity = norm(city);
        double lat = place.getLatitude();
        double lon = place.getLongitude();
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

    private GeoResponse toGeoResponse(List<Place> places) {
        var features = places.stream()
                .filter(Objects::nonNull)
                .filter(place -> place.getLatitude() != null && place.getLongitude() != null)
                .map(place -> new GeoResponse.Feature(
                        "Feature",
                        new GeoResponse.Geometry(
                                "Point",
                                List.of(place.getLongitude(), place.getLatitude())
                        ),
                        new GeoResponse.Properties(
                                firstNonBlank(place.getAddress(), place.getName()),
                                place.getCountry(),
                                null,
                                null,
                                place.getCity(),
                                null,
                                null,
                                null,
                                round6(place.getLatitude()),
                                round6(place.getLongitude()),
                                "database_cache",
                                null,
                                firstNonBlank(place.getExternalRef(), String.valueOf(place.getId()))
                        )
                ))
                .toList();
        return new GeoResponse("FeatureCollection", features);
    }


    static String safe(String... cands) {
        for (String s : cands) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }

    private static Double round6(Double v) {
        if (v == null) return null;
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    public GeoResponse geocode(String address) { return geocode(address, null); }

    public GeoResponse geocodePlaceName(String name, @Nullable String city, @Nullable String country) {
        GeoResponse override = resolveAnchorOverride(name, city, country);
        if (override != null) {
            return override;
        }
        return geocode(name, city);
    }



    @Cacheable(cacheNames = "car_route", key = "#origin + ':' + #destination")
    public GeoRouteResponse car_route(String origin, String destination) {
        GeoRouteResponse r = amap.drivingRoute(origin, destination);
        ensureNonEmpty(r, "car");
        return r;
    }

    /* 主路线：walk */
    @Cacheable(cacheNames = "walk_route", key = "#origin + ':' + #destination")
    public GeoRouteResponse walk_route(String origin, String destination) {
        GeoRouteResponse r = amap.walkingRoute(origin, destination);
        ensureNonEmpty(r, "walk");
        return r;
    }

    /* 主路线：transit（市内，跨城暂不考虑） */
    @Cacheable(cacheNames = "transit_route", key = "#origin + ':' + #destination")
    public GeoRouteResponse transit_route(String origin, String destination) {
        GeoRouteResponse r = amap.transitRoute(origin, destination);
        ensureNonEmpty(r, "transit");
        return r;
    }

    public GeoRouteResponse directLineHint(String origin, String destination, String requestedMode) {
        double[] from = parseLatLon(origin);
        double[] to = parseLatLon(destination);

        List<List<Double>> line = List.of(
                List.of(from[1], from[0]),
                List.of(to[1], to[0])
        );

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
                new GeoRouteResponse.Geometry("LineString", List.of(line))
        );
        return new GeoRouteResponse("FeatureCollection", List.of(feature));
    }

    private static void ensureNonEmpty(GeoRouteResponse r, String mode) {
        if (r == null || r.features() == null || r.features().isEmpty()) {
            throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify " + mode + " empty route");
        }
        var p = r.features().get(0).properties();
        if (p == null || p.distance() == null || p.time() == null) {
            throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify " + mode + " 属性缺失");
        }
    }

    public List<PlaceSuggestionDto> suggestions(@NotBlank(message = "address cannot be blank") String address, String city) {
        GeoResponse resp = geocode(address, city);
        if (resp == null || resp.features() == null || resp.features().isEmpty()) {
            return List.of();
        }

        return resp.features().stream()
                .filter(Objects::nonNull)
                .map(f -> {
                    var p = f.properties();
                    if (p == null || p.lat() == null || p.lon() == null) {
                        return null;
                    }

                    String poiId = firstNonBlank(
                            p.placeId(),
                            p.formatted(),
                            p.lat() + "," + p.lon()
                    );

                    String name = firstNonBlank(
                            p.formatted(),
                            joinNonBlank(", ", p.housenumber(), p.street(), p.city(), p.state(), p.country()),
                            address
                    );

                    return new PlaceSuggestionDto(
                            poiId,
                            name,
                            BigDecimal.valueOf(p.lat()),
                            BigDecimal.valueOf(p.lon()),
                            address
                    );
                })
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                dto -> dto.name() + "|" + dto.lat() + "|" + dto.lng(),
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new
                        ),
                        m -> m.values().stream().limit(10).toList()
                ));
    }

    private String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /* ====== 摘要：distance / time （字符串 or 数值都行，这里输出字符串） ====== */

    public record RouteSummary(String distanceMeters, String durationSeconds) {}

    @Cacheable(cacheNames = "walk_summary", key = "#origin + ':' + #destination", unless = "#result == null")
    public RouteSummary walk_summary(String origin, String destination) { return summarize(amap.walkingRouteRaw(origin, destination)); }

    @Cacheable(cacheNames = "car_summary", key = "#origin + ':' + #destination", unless = "#result == null")
    public RouteSummary car_summary(String origin, String destination) { return summarize(amap.drivingRouteRaw(origin, destination)); }

    @Cacheable(cacheNames = "transit_summary", key = "#origin + ':' + #destination", unless = "#result == null")
    public RouteSummary transit_summary(String origin, String destination) { return summarize(amap.transitRouteRaw(origin, destination)); }

    private RouteSummary summarize(JsonNode root) {
        try {
            JsonNode feats = root.path("features");
            if (!feats.isArray() || feats.size() == 0) return null;

            JsonNode props = feats.get(0).path("properties");
            if (props.isMissingNode()) return null;

            JsonNode d = props.path("distance");
            JsonNode t = props.path("time");

            if (!d.isNumber() || !t.isNumber()) return null;

            int dist = (int) Math.round(d.asDouble());  // 米
            int time = (int) Math.round(t.asDouble());  // 秒（walk/drive 可能是 2426.789）

            return new RouteSummary(String.valueOf(dist), String.valueOf(time));
        } catch (Exception e) {
            log.warn("summarize failed", e);
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private String joinNonBlank(String delimiter, String... vals) {
        return Arrays.stream(vals)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(delimiter));
    }

    private GeoResponse resolveAnchorOverride(String name, @Nullable String city, @Nullable String country) {
        return null;
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
}
