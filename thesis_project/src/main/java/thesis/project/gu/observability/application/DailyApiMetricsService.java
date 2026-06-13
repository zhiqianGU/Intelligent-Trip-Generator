package thesis.project.gu.observability.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.observability.persistence.ApiMetricDailyMapper;
import thesis.project.gu.observability.domain.ApiMetricDaily;

import java.time.LocalDate;
import java.util.List;

@Service
public class DailyApiMetricsService {
    private final ApiMetricDailyMapper apiMetricDailyMapper;

    public DailyApiMetricsService(ApiMetricDailyMapper apiMetricDailyMapper) {
        this.apiMetricDailyMapper = apiMetricDailyMapper;
    }

    public void record(
            String category,
            String endpoint,
            boolean success,
            long latencyMs,
            long redisHits,
            long redisMisses,
            long dbHits,
            long externalCalls,
            long externalFailures,
            long externalLatencyMs
    ) {
        ApiMetricDaily metric = new ApiMetricDaily();
        metric.setMetricDate(LocalDate.now());
        metric.setCategory(category);
        metric.setEndpoint(endpoint);
        metric.setRequestCount(1L);
        metric.setSuccessCount(success ? 1L : 0L);
        metric.setFailureCount(success ? 0L : 1L);
        metric.setRedisHits(Math.max(redisHits, 0));
        metric.setRedisMisses(Math.max(redisMisses, 0));
        metric.setDbHits(Math.max(dbHits, 0));
        metric.setExternalCalls(Math.max(externalCalls, 0));
        metric.setExternalFailures(Math.max(externalFailures, 0));
        metric.setTotalLatencyMs(Math.max(latencyMs, 0));
        metric.setExternalLatencyMs(Math.max(externalLatencyMs, 0));
        apiMetricDailyMapper.upsertDailyMetric(metric);
    }

    public List<ApiMetricDaily> recentDays(int days) {
        int boundedDays = Math.max(1, Math.min(days, 90));
        return apiMetricDailyMapper.selectRecentDays(LocalDate.now().minusDays(boundedDays - 1L));
    }
}
