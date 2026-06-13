package thesis.project.gu.infrastructure.cache;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import thesis.project.gu.infrastructure.cache.CacheCleanupProperties;

import java.util.Set;

@Component
public class RedisCacheShutdownCleaner {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheShutdownCleaner.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final CacheCleanupProperties cacheCleanupProperties;

    public RedisCacheShutdownCleaner(
            StringRedisTemplate stringRedisTemplate,
            CacheCleanupProperties cacheCleanupProperties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheCleanupProperties = cacheCleanupProperties;
    }

    @PreDestroy
    public void clearProjectRedisCachesOnShutdown() {
        if (!cacheCleanupProperties.clearOnShutdown()) {
            return;
        }

        String prefix = cacheCleanupProperties.redisPrefix();
        String pattern = (prefix == null || prefix.isBlank() ? "nav:" : prefix) + "*";
        try {
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                log.info("Redis shutdown cleanup enabled but no keys matched pattern={}", pattern);
                return;
            }
            Long deleted = stringRedisTemplate.delete(keys);
            log.info("Redis shutdown cleanup deleted {} keys for pattern={}", deleted, pattern);
        } catch (RuntimeException e) {
            log.warn("Redis shutdown cleanup failed for pattern={}", pattern, e);
        }
    }
}
