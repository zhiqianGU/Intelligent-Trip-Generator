package thesis.project.gu.observability.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class ApiMetricDaily {
    private LocalDate metricDate;
    private String category;
    private String endpoint;
    private Long requestCount;
    private Long successCount;
    private Long failureCount;
    private Long redisHits;
    private Long redisMisses;
    private Long dbHits;
    private Long externalCalls;
    private Long externalFailures;
    private Long totalLatencyMs;
    private Long externalLatencyMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public LocalDate getMetricDate() {
        return metricDate;
    }

    public void setMetricDate(LocalDate metricDate) {
        this.metricDate = metricDate;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Long getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(Long requestCount) {
        this.requestCount = requestCount;
    }

    public Long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Long successCount) {
        this.successCount = successCount;
    }

    public Long getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(Long failureCount) {
        this.failureCount = failureCount;
    }

    public Long getRedisHits() {
        return redisHits;
    }

    public void setRedisHits(Long redisHits) {
        this.redisHits = redisHits;
    }

    public Long getRedisMisses() {
        return redisMisses;
    }

    public void setRedisMisses(Long redisMisses) {
        this.redisMisses = redisMisses;
    }

    public Long getDbHits() {
        return dbHits;
    }

    public void setDbHits(Long dbHits) {
        this.dbHits = dbHits;
    }

    public Long getExternalCalls() {
        return externalCalls;
    }

    public void setExternalCalls(Long externalCalls) {
        this.externalCalls = externalCalls;
    }

    public Long getExternalFailures() {
        return externalFailures;
    }

    public void setExternalFailures(Long externalFailures) {
        this.externalFailures = externalFailures;
    }

    public Long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public void setTotalLatencyMs(Long totalLatencyMs) {
        this.totalLatencyMs = totalLatencyMs;
    }

    public Long getExternalLatencyMs() {
        return externalLatencyMs;
    }

    public void setExternalLatencyMs(Long externalLatencyMs) {
        this.externalLatencyMs = externalLatencyMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
