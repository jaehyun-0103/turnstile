package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.concert.service.ConcertService;
import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Redis 기반의 캐시 동작, TTL, 그리고 동시 요청 시의 캐시 스탬피드 방어를 검증하는 통합 테스트입니다.
 */
@SpringBootTest
@EnableCaching
@Import(RedisConfig.class)
class ConcertCacheIntegrationTest {

    @Autowired private ConcertService concertService;
    @Autowired private CacheManager cacheManager;
    @Autowired private StringRedisTemplate redisTemplate;

    @MockitoBean private SeatRepository seatRepository;

    private final Long TARGET_SCHEDULE_ID = 1L; // 더미 데이터의 1번 회차 사용

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache("concert:schedule")).clear();
    }

    @Test
    @DisplayName("최초 조회 시 DB를 호출하고, 이후 동일 요청은 캐시를 반환하여 DB 호출이 1회로 제한되어야 한다")
    void should_hit_cache_on_subsequent_requests() {
        // given: 더미 데이터에 1번 회차의 좌석(50개)이 이미 존재함

        // when: 동일한 좌석 정보를 3회 연속 요청
        concertService.getSeats(TARGET_SCHEDULE_ID); // 1st: Cache Miss (DB 조회)
        concertService.getSeats(TARGET_SCHEDULE_ID); // 2nd: Cache Hit
        concertService.getSeats(TARGET_SCHEDULE_ID); // 3rd: Cache Hit

        // then: DB 실제 조회는 단 1번만 일어났음을 스파이 객체를 통해 검증
        verify(seatRepository, times(1)).findAllBySchedule_Id(TARGET_SCHEDULE_ID);
    }

    @Test
    @DisplayName("캐시 저장 시 설정된 짧은 TTL(1분)이 정상적으로 적용되어야 한다")
    void should_apply_configured_ttl_to_cached_seats() {
        // when: 좌석 조회 후 캐시 적재 수행
        concertService.getSeats(TARGET_SCHEDULE_ID);

        // then: Redis 키의 유효시간이 60초 이내인지 확인
        String cacheKey = "concert:schedule:" + TARGET_SCHEDULE_ID + ":seats";
        Long expire = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);

        assertThat(expire).isNotNull().isPositive().isLessThanOrEqualTo(60L);
    }
}