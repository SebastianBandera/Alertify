package app.alertify.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.service.ConfigurationCacheNames;

@Configuration(proxyBeanMethods = false)
public class AlertifyRedisCacheConfiguration {

    @Bean
    RedisCacheManagerBuilderCustomizer configurationCacheCustomizer(
            @Value("${spring.cache.redis.time-to-live}") Duration timeToLive,
            @Value("${spring.cache.redis.key-prefix}") String keyPrefix) {
        var valueSerializer = RedisSerializationContext.SerializationPair.fromSerializer(
            new JacksonJsonRedisSerializer<>(ConfigurationResponse.class)
        );
        CacheKeyPrefix prefix = cacheName -> keyPrefix + cacheName + "::";
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(timeToLive)
            .computePrefixWith(prefix)
            .serializeValuesWith(valueSerializer)
            .disableCachingNullValues();

        return builder -> builder
            .withCacheConfiguration(ConfigurationCacheNames.BY_ID, configuration)
            .withCacheConfiguration(ConfigurationCacheNames.BY_NAME, configuration);
    }
}
