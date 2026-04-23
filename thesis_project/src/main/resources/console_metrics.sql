CREATE TABLE api_metric_daily (
    metric_date DATE NOT NULL,
    category VARCHAR(32) NOT NULL,
    endpoint VARCHAR(64) NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    redis_hits BIGINT NOT NULL DEFAULT 0,
    redis_misses BIGINT NOT NULL DEFAULT 0,
    db_hits BIGINT NOT NULL DEFAULT 0,
    external_calls BIGINT NOT NULL DEFAULT 0,
    external_failures BIGINT NOT NULL DEFAULT 0,
    total_latency_ms BIGINT NOT NULL DEFAULT 0,
    external_latency_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (metric_date, category, endpoint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE place MODIFY source VARCHAR(64) NOT NULL;

UPDATE place
SET latitude = NULL,
    longitude = NULL
WHERE name LIKE '%Coot-tha%'
   OR address LIKE '%Coot-tha%';