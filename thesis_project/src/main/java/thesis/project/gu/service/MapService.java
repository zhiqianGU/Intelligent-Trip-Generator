package thesis.project.gu.service;



import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.common.lang.Nullable;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thesis.project.gu.client.AmapClient;
import thesis.project.gu.dto.RouteSummary;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.mapper.PlaceMapper;
import thesis.project.gu.model.Place;
import thesis.project.gu.response.CarRouteResponse;
import thesis.project.gu.response.GeoResponse;
import thesis.project.gu.response.GeoRouteResponse;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class MapService {
    private static final Logger log = LoggerFactory.getLogger(MapService.class);
    private final AmapClient amap;
    @Getter
    private final CacheManager cacheManager;
    private final PlaceMapper placeMapper;

    public MapService(AmapClient amap, CacheManager cacheManager, PlaceMapper placeMapper) {
        this.amap = amap;
        this.cacheManager = cacheManager;
        this.placeMapper = placeMapper;
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
        GeoResponse resp = amap.geocode(address, city);
        if (resp == null || resp.features() == null || resp.features().isEmpty()) {
            throw new NavigatorException(ErrorCode.GEOCODE_FAIL, "Geoapify geocoding failed: empty result");
        }
        return resp;
    }

    /** 新增：地理编码并把候选写入 place 表，返回写入后的 Place 列表 */
    @Transactional
    public List<Place> geocodeAndPersist(String address, @Nullable String city) {
        GeoResponse geo = geocode(address, city);

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

    private static String safe(String... cands) {
        for (String s : cands) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }

    private static Double round6(Double v) {
        if (v == null) return null;
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    public GeoResponse geocode(String address) { return geocode(address, null); }



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

    private static void ensureNonEmpty(GeoRouteResponse r, String mode) {
        if (r == null || r.features() == null || r.features().isEmpty()) {
            throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify " + mode + " 路线为空");
        }
        var p = r.features().get(0).properties();
        if (p == null || p.distance() == null || p.time() == null) {
            throw new NavigatorException(ErrorCode.ROUTE_FAIL, "Geoapify " + mode + " 属性缺失");
        }
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
}
