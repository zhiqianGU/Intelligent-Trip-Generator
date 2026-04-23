package thesis.project.gu.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Service
public class RuntimeMetricsService {
    private final DailyApiMetricsService dailyApiMetricsService;
    private final Instant startedAt = Instant.now();

    private final LongAdder geocodeRequests = new LongAdder();
    private final LongAdder geocodeRedisHits = new LongAdder();
    private final LongAdder geocodeDbHits = new LongAdder();
    private final LongAdder geocodeExternalCalls = new LongAdder();
    private final LongAdder geocodeExternalFailures = new LongAdder();
    private final LongAdder geocodeExternalLatencyMs = new LongAdder();
    private final LongAdder geocodeEndpointLatencyMs = new LongAdder();
    private final LongAdder geocodeRequestFailures = new LongAdder();

    private final LongAdder routeRequests = new LongAdder();
    private final LongAdder routeRedisHits = new LongAdder();
    private final LongAdder routeRedisMisses = new LongAdder();
    private final LongAdder routeFailures = new LongAdder();
    private final LongAdder routeEndpointLatencyMs = new LongAdder();
    private final LongAdder routeParallelWallMs = new LongAdder();
    private final LongAdder routeSerialEstimateMs = new LongAdder();
    private final LongAdder routeParallelSavedMs = new LongAdder();
    private final LongAdder routeTransitFallbacks = new LongAdder();
    private final LongAdder routeDirectLineFallbacks = new LongAdder();

    private final LongAdder externalRouteCalls = new LongAdder();
    private final LongAdder externalRouteFailures = new LongAdder();
    private final LongAdder externalRouteLatencyMs = new LongAdder();

    private final LongAdder externalSummaryCalls = new LongAdder();
    private final LongAdder externalSummaryFailures = new LongAdder();
    private final LongAdder externalSummaryLatencyMs = new LongAdder();

    private final LongAdder planGenerateRequests = new LongAdder();
    private final LongAdder planGenerateRedisHits = new LongAdder();
    private final LongAdder planGenerateFailures = new LongAdder();
    private final LongAdder planGenerateEndpointLatencyMs = new LongAdder();
    private final LongAdder planGenerateExternalCalls = new LongAdder();
    private final LongAdder planGenerateExternalFailures = new LongAdder();
    private final LongAdder planGenerateExternalLatencyMs = new LongAdder();

    private final LongAdder planViewRequests = new LongAdder();
    private final LongAdder planViewFailures = new LongAdder();
    private final LongAdder planViewLatencyMs = new LongAdder();
    private final LongAdder planPrewarmRequests = new LongAdder();
    private final LongAdder planPrewarmFailures = new LongAdder();
    private final LongAdder planPrewarmLatencyMs = new LongAdder();
    private final LongAdder planPrewarmGeocodeCalls = new LongAdder();
    private final LongAdder planPrewarmRouteCalls = new LongAdder();

    public RuntimeMetricsService(DailyApiMetricsService dailyApiMetricsService) {
        this.dailyApiMetricsService = dailyApiMetricsService;
    }

    public void recordGeocodeRequest(boolean redisHit, long latencyMs) {
        recordGeocodeRequest(redisHit, latencyMs, true);
    }

    public void recordGeocodeRequest(boolean redisHit, long latencyMs, boolean success) {
        geocodeRequests.increment();
        geocodeEndpointLatencyMs.add(Math.max(latencyMs, 0));
        if (redisHit) geocodeRedisHits.increment();
        if (!success) geocodeRequestFailures.increment();
        dailyApiMetricsService.record(
                "map",
                "geocode",
                success,
                latencyMs,
                redisHit ? 1 : 0,
                redisHit ? 0 : 1,
                0,
                0,
                0,
                0
        );
    }

    public void recordGeocodeDbHit() {
        geocodeDbHits.increment();
        dailyApiMetricsService.record("map", "geocode_db_hit", true, 0, 0, 0, 1, 0, 0, 0);
    }

    public void recordExternalGeocode(long latencyMs, boolean success) {
        geocodeExternalCalls.increment();
        geocodeExternalLatencyMs.add(Math.max(latencyMs, 0));
        if (!success) geocodeExternalFailures.increment();
        dailyApiMetricsService.record(
                "external",
                "geocode",
                success,
                latencyMs,
                0,
                0,
                0,
                1,
                success ? 0 : 1,
                latencyMs
        );
    }

    public void recordRouteRequest(boolean redisHit, long latencyMs, boolean success) {
        routeRequests.increment();
        routeEndpointLatencyMs.add(Math.max(latencyMs, 0));
        if (redisHit) routeRedisHits.increment();
        else routeRedisMisses.increment();
        if (!success) routeFailures.increment();
        dailyApiMetricsService.record(
                "map",
                "route",
                success,
                latencyMs,
                redisHit ? 1 : 0,
                redisHit ? 0 : 1,
                0,
                0,
                0,
                0
        );
    }

    public void recordRouteParallelMetrics(long parallelWallMs, long serialEstimateMs) {
        long parallel = Math.max(parallelWallMs, 0);
        long serial = Math.max(serialEstimateMs, 0);
        routeParallelWallMs.add(parallel);
        routeSerialEstimateMs.add(serial);
        routeParallelSavedMs.add(Math.max(serial - parallel, 0));
        dailyApiMetricsService.record(
                "map",
                "route_parallel",
                true,
                parallel,
                0,
                0,
                0,
                0,
                0,
                serial
        );
    }

    public void recordRouteFallback(String fallbackType) {
        if ("transit_to_walk".equals(fallbackType)) {
            routeTransitFallbacks.increment();
        } else if ("direct_line_hint".equals(fallbackType)) {
            routeDirectLineFallbacks.increment();
        }
        dailyApiMetricsService.record("map", fallbackType, true, 0, 0, 0, 0, 0, 0, 0);
    }

    public void recordExternalRoute(long latencyMs, boolean success) {
        externalRouteCalls.increment();
        externalRouteLatencyMs.add(Math.max(latencyMs, 0));
        if (!success) externalRouteFailures.increment();
        dailyApiMetricsService.record(
                "external",
                "route",
                success,
                latencyMs,
                0,
                0,
                0,
                1,
                success ? 0 : 1,
                latencyMs
        );
    }

    public void recordExternalSummary(long latencyMs, boolean success) {
        externalSummaryCalls.increment();
        externalSummaryLatencyMs.add(Math.max(latencyMs, 0));
        if (!success) externalSummaryFailures.increment();
        dailyApiMetricsService.record(
                "external",
                "route_summary",
                success,
                latencyMs,
                0,
                0,
                0,
                1,
                success ? 0 : 1,
                latencyMs
        );
    }

    public void recordPlanGenerateRequest(String endpoint, boolean redisHit, long latencyMs, boolean success) {
        planGenerateRequests.increment();
        planGenerateEndpointLatencyMs.add(Math.max(latencyMs, 0));
        if (redisHit) planGenerateRedisHits.increment();
        if (!success) planGenerateFailures.increment();
        dailyApiMetricsService.record(
                "plan",
                endpoint,
                success,
                latencyMs,
                redisHit ? 1 : 0,
                redisHit ? 0 : 1,
                0,
                0,
                0,
                0
        );
    }

    public void recordExternalPlanGenerate(long latencyMs, boolean success) {
        planGenerateExternalCalls.increment();
        planGenerateExternalLatencyMs.add(Math.max(latencyMs, 0));
        if (!success) planGenerateExternalFailures.increment();
        dailyApiMetricsService.record(
                "external",
                "plan_generate",
                success,
                latencyMs,
                0,
                0,
                0,
                1,
                success ? 0 : 1,
                latencyMs
        );
    }

    public void recordPlanViewRequest(String endpoint, long latencyMs, boolean success) {
        planViewRequests.increment();
        planViewLatencyMs.add(Math.max(latencyMs, 0));
        if (!success) planViewFailures.increment();
        dailyApiMetricsService.record(
                "plan",
                endpoint,
                success,
                latencyMs,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    public void recordPlanPrewarm(long latencyMs, boolean success, long geocodeCalls, long routeCalls) {
        planPrewarmRequests.increment();
        planPrewarmLatencyMs.add(Math.max(latencyMs, 0));
        planPrewarmGeocodeCalls.add(Math.max(geocodeCalls, 0));
        planPrewarmRouteCalls.add(Math.max(routeCalls, 0));
        if (!success) planPrewarmFailures.increment();
        dailyApiMetricsService.record(
                "plan",
                "prewarm",
                success,
                latencyMs,
                0,
                0,
                0,
                Math.max(routeCalls + geocodeCalls, 0),
                success ? 0 : 1,
                0
        );
    }

    public Map<String, Object> snapshot() {
        long geocodeReq = geocodeRequests.sum();
        long geocodeRedis = geocodeRedisHits.sum();
        long geocodeDb = geocodeDbHits.sum();
        long geocodeExternal = geocodeExternalCalls.sum();

        long routeReq = routeRequests.sum();
        long routeRedis = routeRedisHits.sum();
        long routeExternal = externalRouteCalls.sum();

        double avgGeocodeExternalMs = average(geocodeExternalLatencyMs.sum(), geocodeExternal);
        double avgGeocodeEndpointMs = average(geocodeEndpointLatencyMs.sum(), geocodeReq);
        double avgRouteExternalMs = average(externalRouteLatencyMs.sum(), routeExternal);
        double avgRouteEndpointMs = average(routeEndpointLatencyMs.sum(), routeReq);
        double avgSummaryExternalMs = average(externalSummaryLatencyMs.sum(), externalSummaryCalls.sum());
        double avgPlanGenerateEndpointMs = average(planGenerateEndpointLatencyMs.sum(), planGenerateRequests.sum());
        double avgPlanGenerateExternalMs = average(planGenerateExternalLatencyMs.sum(), planGenerateExternalCalls.sum());
        double avgPlanViewMs = average(planViewLatencyMs.sum(), planViewRequests.sum());

        long geocodeBaselineExternalCalls = geocodeRedis + geocodeDb + geocodeExternal;
        long geocodeSavedExternalCalls = geocodeBaselineExternalCalls - geocodeExternal;
        double geocodeEstimatedSavedMs = geocodeSavedExternalCalls * avgGeocodeExternalMs;

        long routeBaselineExternalCalls = routeReq;
        long routeSavedExternalCalls = routeBaselineExternalCalls - routeExternal;
        double routeEstimatedSavedMs = routeSavedExternalCalls * avgRouteExternalMs;

        Map<String, Object> geocode = new LinkedHashMap<>();
        geocode.put("requests", geocodeReq);
        geocode.put("redisHits", geocodeRedis);
        geocode.put("dbHits", geocodeDb);
        geocode.put("externalCalls", geocodeExternal);
        geocode.put("externalFailures", geocodeExternalFailures.sum());
        geocode.put("requestFailures", geocodeRequestFailures.sum());
        geocode.put("redisHitRatePct", percentage(geocodeRedis, geocodeReq));
        geocode.put("dbHitRatePct", percentage(geocodeDb, geocodeReq));
        geocode.put("externalCallRatePct", percentage(geocodeExternal, geocodeReq));
        geocode.put("avgExternalLatencyMs", avgGeocodeExternalMs);
        geocode.put("avgEndpointLatencyMs", avgGeocodeEndpointMs);
        geocode.put("baselineExternalCalls", geocodeBaselineExternalCalls);
        geocode.put("savedExternalCalls", geocodeSavedExternalCalls);
        geocode.put("estimatedSavedLatencyMs", geocodeEstimatedSavedMs);

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("requests", routeReq);
        route.put("redisHits", routeRedis);
        route.put("redisMisses", routeRedisMisses.sum());
        route.put("externalCalls", routeExternal);
        route.put("externalFailures", externalRouteFailures.sum());
        route.put("requestFailures", routeFailures.sum());
        route.put("redisHitRatePct", percentage(routeRedis, routeReq));
        route.put("externalCallRatePct", percentage(routeExternal, routeReq));
        route.put("avgExternalLatencyMs", avgRouteExternalMs);
        route.put("avgEndpointLatencyMs", avgRouteEndpointMs);
        route.put("avgParallelWallMs", average(routeParallelWallMs.sum(), routeReq));
        route.put("avgSerialEstimateMs", average(routeSerialEstimateMs.sum(), routeReq));
        route.put("avgParallelSavedMs", average(routeParallelSavedMs.sum(), routeReq));
        route.put("transitToWalkFallbacks", routeTransitFallbacks.sum());
        route.put("directLineFallbacks", routeDirectLineFallbacks.sum());
        route.put("baselineExternalCalls", routeBaselineExternalCalls);
        route.put("savedExternalCalls", routeSavedExternalCalls);
        route.put("estimatedSavedLatencyMs", routeEstimatedSavedMs);

        Map<String, Object> externalApi = new LinkedHashMap<>();
        externalApi.put("geocodeAvgLatencyMs", avgGeocodeExternalMs);
        externalApi.put("routeAvgLatencyMs", avgRouteExternalMs);
        externalApi.put("summaryAvgLatencyMs", avgSummaryExternalMs);
        externalApi.put("planGenerateAvgLatencyMs", avgPlanGenerateExternalMs);
        externalApi.put("summaryCalls", externalSummaryCalls.sum());
        externalApi.put("summaryFailures", externalSummaryFailures.sum());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("generateRequests", planGenerateRequests.sum());
        plan.put("generateRedisHits", planGenerateRedisHits.sum());
        plan.put("generateFailures", planGenerateFailures.sum());
        plan.put("generateExternalCalls", planGenerateExternalCalls.sum());
        plan.put("generateExternalFailures", planGenerateExternalFailures.sum());
        plan.put("generateAvgEndpointLatencyMs", avgPlanGenerateEndpointMs);
        plan.put("generateAvgExternalLatencyMs", avgPlanGenerateExternalMs);
        plan.put("viewRequests", planViewRequests.sum());
        plan.put("viewFailures", planViewFailures.sum());
        plan.put("viewAvgLatencyMs", avgPlanViewMs);
        plan.put("prewarmRequests", planPrewarmRequests.sum());
        plan.put("prewarmFailures", planPrewarmFailures.sum());
        plan.put("prewarmAvgLatencyMs", average(planPrewarmLatencyMs.sum(), planPrewarmRequests.sum()));
        plan.put("prewarmGeocodeCalls", planPrewarmGeocodeCalls.sum());
        plan.put("prewarmRouteCalls", planPrewarmRouteCalls.sum());

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("labelA", "Optimized: DB + Redis + External API");
        comparison.put("labelB", "Baseline: External API Only");
        comparison.put("geocodeExternalReductionPct", percentReduction(geocodeBaselineExternalCalls, geocodeExternal));
        comparison.put("routeExternalReductionPct", percentReduction(routeBaselineExternalCalls, routeExternal));
        comparison.put("planGenerateCacheHitPct", percentage(planGenerateRedisHits.sum(), planGenerateRequests.sum()));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("startedAt", startedAt.toString());
        root.put("capturedAt", Instant.now().toString());
        root.put("geocode", geocode);
        root.put("route", route);
        root.put("plan", plan);
        root.put("externalApi", externalApi);
        root.put("comparison", comparison);
        return root;
    }

    private double average(long total, long count) {
        if (count <= 0) return 0D;
        return (double) total / count;
    }

    private double percentReduction(long baseline, long actual) {
        if (baseline <= 0) return 0D;
        return ((double) (baseline - actual) / baseline) * 100D;
    }

    private double percentage(long numerator, long denominator) {
        if (denominator <= 0) return 0D;
        return ((double) numerator / denominator) * 100D;
    }
}
