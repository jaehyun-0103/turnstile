package com.turnstile.backend.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 데이터 구조 및 캐시 만료 정책을 정의하는 설정 클래스입니다.
 */
@EnableCaching
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Redis CLI 데이터 확인 및 문자열 연산을 위해 String 직렬화 통일
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        return template;
    }

    /**
     * 대기열 진입 및 상태 확인을 위한 Redis 스크립트 빈을 등록합니다.
     */
    @Bean
    public RedisScript<Long> enterQueueScript() {
        return RedisScript.of(new ClassPathResource("scripts/enter_queue.lua"), Long.class);
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RedisScript<List<String>> transitionQueueScript() {
        return RedisScript.of(new ClassPathResource("scripts/transition_queue.lua"), (Class<List<String>>) (Class) List.class);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 캐시 데이터 역직렬화 시 구체적인 객체 타입을 식별하기 위해 타입 정보를 함께 저장하도록 설정
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.turnstile.backend")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.lang")
                        .allowIfSubType("java.time")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                // CacheEvictor에서 관리하는 키 패턴과 일치하도록 구분자 설정
                .computePrefixWith(cacheName -> cacheName + ":");

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("concert:info", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("concert:schedules", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("concert:schedule", defaultConfig.entryTtl(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}