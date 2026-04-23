package thesis.project.gu.Controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.service.CacheSerive;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache")
public class CacheAdminController {
    private final CacheSerive cacheSerive;

    public CacheAdminController(CacheSerive cacheSerive) {
        this.cacheSerive = cacheSerive;
    }

    @DeleteMapping("/geocode-persist")
    public Map<String, Object> evictGeocodePersist(
            @RequestParam String address,
            @RequestParam(required = false) String city
    ) {
        cacheSerive.evictGeocodePersist(address, city);
        String key = String.format("%s|%s", normalize(address).toLowerCase(), normalize(city).toLowerCase());
        return Map.of("cache", "geocode_persist", "key", key, "status", "evicted");
    }

    @DeleteMapping("/geocode-persist/all")
    public Map<String, Object> evictGeocodePersistAll() {
        cacheSerive.evictGeocodePersistAll();
        return Map.of("cache", "geocode_persist", "status", "cleared");
    }

    @DeleteMapping("/geocode/all")
    public Map<String, Object> evictGeocodeAll() {
        cacheSerive.evictGeocodeAll();
        return Map.of("cache", "geocode", "status", "cleared");
    }

    @DeleteMapping("/routes/all")
    public Map<String, Object> evictRoutesAll() {
        cacheSerive.evictRouteCachesAll();
        return Map.of("cache", "routes", "status", "cleared");
    }

    @DeleteMapping("/benchmark/all")
    public Map<String, Object> evictBenchmarkAll() {
        cacheSerive.evictAllBenchmarkCaches();
        return Map.of("cache", "benchmark", "status", "cleared");
    }

    @PostMapping("/ai-plan-raw/evict")
    public Map<String, Object> evictAiPlanRaw(@RequestBody CreatePlanReq req) {
        cacheSerive.evictAiPlanRaw(req);
        return Map.of("cache", "ai_plan_raw", "status", "evicted");
    }

    @DeleteMapping("/ai-plan-raw/all")
    public Map<String, Object> evictAiPlanRawAll() {
        cacheSerive.evictAiPlanRawAll();
        return Map.of("cache", "ai_plan_raw", "status", "cleared");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
