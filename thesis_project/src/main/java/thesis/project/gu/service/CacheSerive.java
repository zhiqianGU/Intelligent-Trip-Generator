package thesis.project.gu.service;

import io.micrometer.common.lang.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import thesis.project.gu.req.CreatePlanReq;

@Service
public class CacheSerive {

    @CacheEvict(
            cacheNames = "geocode_persist",
            key = "T(java.lang.String).format('%s|%s', (#address?:'').trim().toLowerCase(), (#city?:'').trim().toLowerCase())"
    )
    public void evictGeocodePersist(String address, @Nullable String city) {
    }

    @CacheEvict(cacheNames = "geocode_persist", allEntries = true)
    public void evictGeocodePersistAll() {
    }

    @CacheEvict(cacheNames = "geocode", allEntries = true)
    public void evictGeocodeAll() {
    }

    @CacheEvict(cacheNames = "car_route", allEntries = true)
    public void evictCarRouteAll() {
    }

    @CacheEvict(cacheNames = "walk_route", allEntries = true)
    public void evictWalkRouteAll() {
    }

    @CacheEvict(cacheNames = "transit_route", allEntries = true)
    public void evictTransitRouteAll() {
    }

    @CacheEvict(cacheNames = "car_summary", allEntries = true)
    public void evictCarSummaryAll() {
    }

    @CacheEvict(cacheNames = "walk_summary", allEntries = true)
    public void evictWalkSummaryAll() {
    }

    @CacheEvict(cacheNames = "transit_summary", allEntries = true)
    public void evictTransitSummaryAll() {
    }

    @CacheEvict(cacheNames = "ai_plan_raw", keyGenerator = "aiPlanKeyGen")
    public void evictAiPlanRaw(CreatePlanReq req) {
    }

    @CacheEvict(cacheNames = "ai_plan_raw", allEntries = true)
    public void evictAiPlanRawAll() {
    }

    public void evictRouteCachesAll() {
        evictCarRouteAll();
        evictWalkRouteAll();
        evictTransitRouteAll();
        evictCarSummaryAll();
        evictWalkSummaryAll();
        evictTransitSummaryAll();
    }

    public void evictAllBenchmarkCaches() {
        evictGeocodePersistAll();
        evictGeocodeAll();
        evictRouteCachesAll();
        evictAiPlanRawAll();
    }
}
