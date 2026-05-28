package thesis.project.gu.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableCaching
public class CacheConfig {
    private static final double TTL_JITTER_RATIO = 0.10D;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        var keySer = RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer());
        var valueSer = RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer());

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(keySer)
                .serializeValuesWith(valueSer)
                .computePrefixWith(name -> "nav:" + name + ":")
                .disableCachingNullValues()
                .entryTtl(ttlWithJitter(Duration.ofHours(1)));

        Map<String, RedisCacheConfiguration> confMap = new HashMap<>(16, 1.0f);
        confMap.put("geocode", base.entryTtl(ttlWithJitter(Duration.ofDays(1))));
        confMap.put("car_route", base.entryTtl(ttlWithJitter(Duration.ofMinutes(30))));
        confMap.put("walk_route", base.entryTtl(ttlWithJitter(Duration.ofMinutes(30))));
        confMap.put("transit_route", base.entryTtl(ttlWithJitter(Duration.ofMinutes(15))));
        confMap.put("car_summary", base.entryTtl(ttlWithJitter(Duration.ofMinutes(15))));
        confMap.put("walk_summary", base.entryTtl(ttlWithJitter(Duration.ofMinutes(30))));
        confMap.put("transit_summary", base.entryTtl(ttlWithJitter(Duration.ofMinutes(10))));
        confMap.put("user_lists", base.entryTtl(ttlWithJitter(Duration.ofMinutes(10))));
        confMap.put("list_items", base.entryTtl(ttlWithJitter(Duration.ofMinutes(10))));
        confMap.put("user_by_id", base.entryTtl(ttlWithJitter(Duration.ofMinutes(20))));
        confMap.put("user_by_login", base.entryTtl(ttlWithJitter(Duration.ofMinutes(3))));
        confMap.put("place_by_poi", base.entryTtl(ttlWithJitter(Duration.ofDays(30))));
        confMap.put("google_places_text_search", base.entryTtl(ttlWithJitter(Duration.ofDays(1))));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(confMap)
                .build();
    }

    private static RedisCacheWriter.TtlFunction ttlWithJitter(Duration baseTtl) {
        return (key, value) -> jitter(baseTtl);
    }

    private static Duration jitter(Duration baseTtl) {
        long baseMillis = baseTtl == null ? 0L : baseTtl.toMillis();
        if (baseMillis <= 0L) {
            return baseTtl;
        }
        long jitterRange = Math.max(1L, Math.round(baseMillis * TTL_JITTER_RATIO));
        long offset = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1L);
        return Duration.ofMillis(Math.max(1_000L, baseMillis + offset));
    }
}
