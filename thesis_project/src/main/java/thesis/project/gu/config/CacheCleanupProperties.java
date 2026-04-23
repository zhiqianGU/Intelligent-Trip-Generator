package thesis.project.gu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cache")
public record CacheCleanupProperties(
        boolean clearOnShutdown,
        String redisPrefix
) {
    public CacheCleanupProperties() {
        this(false, "nav:");
    }
}
