package com.turnstile.backend.global;

import com.turnstile.backend.global.common.CacheEvictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
    @DisplayName("좌석 캐시 무효화 시 해당 회차의 정확한 Redis 캐시 키만 삭제한다")
    void deletesOnlyExactSeatCacheKey() {
        // given: 대상 회차의 캐시 키 삭제 성공 응답이 설정됨
        given(redisTemplate.delete("concert:schedule:100:seats")).willReturn(true);

        // when: 캐시 무효화 메서드를 실행함
        cacheEvictor.evictSeatCache(100L);

        // then: 지정된 단일 캐시 키만 삭제되고 추가 Redis 호출이 없음을 검증함
        verify(redisTemplate).delete("concert:schedule:100:seats");
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    @DisplayName("삭제 대상 좌석 캐시가 이미 존재하지 않아도 예외 없이 정상 종료한다")
    void absentCacheIsNotAnError() {
        // given: 대상 회차의 캐시 키가 이미 부재한 상태(false 반환)가 설정됨
        given(redisTemplate.delete("concert:schedule:100:seats")).willReturn(false);

        // when & then: 예외를 발생시키지 않고 정상적으로 처리됨을 검증함
        assertDoesNotThrow(() -> cacheEvictor.evictSeatCache(100L));
    }

    @Test
    void redisFailureIsPassedToCaller() {
        // given: Redis 삭제 요청 시 연결 실패 예외가 발생하도록 설정됨
        given(redisTemplate.delete("concert:schedule:100:seats")).willThrow(new RedisConnectionFailureException("test failure"));

        // when & then: 발생한 Redis 예외가 호출부로 그대로 전파됨을 검증함
        assertThrows(
                RedisConnectionFailureException.class,
                () -> cacheEvictor.evictSeatCache(100L)
        );
    }
}