package com.turnstile.backend.global;

import com.turnstile.backend.global.common.CacheEvictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 공통 캐시 무효화 컴포넌트의 동작을 검증하는 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class CacheEvictorUnitTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @InjectMocks
    private CacheEvictor cacheEvictor;

    @Test
    @DisplayName("좌석 캐시 무효화 시 현재 로직에서는 Redis 명령 없이 로그만 기록한다")
    void evictSeatCacheShouldOnlyLogWithoutRedisInteraction() {
        // given: 특정 회차 식별자가 주어짐
        Long scheduleId = 100L;

        // when: 캐시 무효화 메서드를 실행함
        cacheEvictor.evictSeatCache(scheduleId);

        // then: Redis 호출이 없음을 검증함
        verifyNoInteractions(redisTemplate);
    }
}