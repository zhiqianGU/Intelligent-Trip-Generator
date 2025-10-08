package thesis.project.gu.config;


import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        var keySer   = RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer());
        var valueSer = RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer());


        // 统一的基础配置：String key + JSON value + 统一前缀 + 默认TTL
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(keySer)
                .serializeValuesWith(valueSer)
                .computePrefixWith(name -> "nav:" + name + ":")
                .disableCachingNullValues()
                .entryTtl(Duration.ofHours(1)); // 默认 TTL（可被下面每个缓存覆盖）

        Map<String, RedisCacheConfiguration> confMap = new HashMap<>(16, 1.0f);
        // 地图/路线
        confMap.put("geocode",         base.entryTtl(Duration.ofDays(1)));
        confMap.put("car_route",       base.entryTtl(Duration.ofMinutes(30)));
        confMap.put("walk_route",      base.entryTtl(Duration.ofMinutes(30)));
        confMap.put("transit_route",   base.entryTtl(Duration.ofMinutes(15)));
        confMap.put("car_summary",     base.entryTtl(Duration.ofMinutes(15)));
        confMap.put("walk_summary",    base.entryTtl(Duration.ofMinutes(30)));
        confMap.put("transit_summary", base.entryTtl(Duration.ofMinutes(10)));
        // 用户/列表（注意都用 base 而不是 defaultCacheConfig）
        confMap.put("user_lists",      base.entryTtl(Duration.ofMinutes(10)));
        confMap.put("list_items",      base.entryTtl(Duration.ofMinutes(10)));
        confMap.put("user_by_id",      base.entryTtl(Duration.ofMinutes(20)));
        confMap.put("user_by_login",   base.entryTtl(Duration.ofMinutes(3)));
        confMap.put("place_by_poi",    base.entryTtl(Duration.ofDays(30)));
        // 如果给 /map/suggestions 加缓存：
        // confMap.put("suggestions",   base.entryTtl(Duration.ofHours(6)));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(confMap)
                .build();
    }
}


