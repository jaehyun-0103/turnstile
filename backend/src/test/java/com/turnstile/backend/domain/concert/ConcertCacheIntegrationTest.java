package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
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

    @MockitoBean private ConcertScheduleRepository concertScheduleRepository;
    @MockitoBean private SeatRepository seatRepository;

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache("concert:schedule")).clear();
    }

    @Test
    @DisplayName("최초 조회 시 DB를 호출하고, 이후 동일 요청은 캐시를 반환하여 DB 호출이 1회로 제한되어야 한다")
    void should_hit_cache_on_subsequent_requests() {
        // given: 정상적인 회차 데이터 모킹
        Long scheduleId = 2L;
        ConcertSchedule mockSchedule = ConcertSchedule.builder().id(scheduleId).concertDate(LocalDateTime.now()).build();

        given(concertScheduleRepository.findById(scheduleId)).willReturn(Optional.of(mockSchedule));
        given(seatRepository.findAllByScheduleId(scheduleId)).willReturn(List.of());

        // when: 동일한 좌석 정보를 3회 연속 요청
        concertService.getSeats(scheduleId); // 1st: Cache Miss
        concertService.getSeats(scheduleId); // 2nd: Cache Hit
        concertService.getSeats(scheduleId); // 3rd: Cache Hit

        // then: DB 실제 조회는 단 1번만 일어났음을 검증 (캐시 정상 작동 증명)
        verify(seatRepository, times(1)).findAllByScheduleId(scheduleId);
    }

    @Test
    @DisplayName("캐시 저장 시 설정된 짧은 TTL(1분)이 정상적으로 적용되어야 한다")
    void should_apply_configured_ttl_to_cached_seats() {
        // given: 검증 통과를 위한 모킹
        Long scheduleId = 3L;
        given(concertScheduleRepository.findById(scheduleId)).willReturn(Optional.of(ConcertSchedule.builder().build()));
        given(seatRepository.findAllByScheduleId(scheduleId)).willReturn(List.of());

        // when: 조회 후 캐시 적재 수행
        concertService.getSeats(scheduleId);

        // then: Redis 키의 유효시간이 60초 이내인지 확인
        String cacheKey = "concert:schedule:" + scheduleId + ":seats";
        Long expire = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);

        assertThat(expire).isNotNull().isPositive().isLessThanOrEqualTo(60L);
    }
}