package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest
@EnableCaching
@Import(RedisConfig.class)
class ConcertCacheIntegrationTest {

    @Autowired private ConcertService concertService;
    @Autowired private CacheManager cacheManager;
    @Autowired private StringRedisTemplate redisTemplate;

    @MockitoBean private SeatRepository seatRepository;

    @BeforeEach
    void clearCache() {
        // 테스트 간 격리를 위해 캐시 초기화
        cacheManager.getCache("seats:cache").clear();
    }

    @Test
    @DisplayName("최초 조회 시 DB 접근 후 재조회 시 캐시 사용 확인")
    void cacheMissHitVerification() {
        // given: Mock 데이터 세팅
        Long scheduleId = 1L;
        given(seatRepository.findAllByScheduleId(scheduleId))
                .willReturn(List.of(Seat.builder().seatNo(1).status(SeatStatus.AVAILABLE).build()));

        // when: 최초 조회(Miss)로 캐시에 적재하고, 재조회(Hit)로 캐시 작동 확인
        concertService.getSeats(scheduleId);
        concertService.getSeats(scheduleId);

        // then: DB 조회는 최초 1회만 수행
        verify(seatRepository, times(1)).findAllByScheduleId(scheduleId);
    }

    @Test
    @DisplayName("CountDownLatch 활용 대량 동시 요청 시 DB 조회 1회 제한 확인")
    void cacheStampedeProtectionTest() throws InterruptedException {
        // given: 30개 스레드 및 Mock 데이터 준비
        Long scheduleId = 2L;
        int threadCount = 30;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        given(seatRepository.findAllByScheduleId(scheduleId))
                .willReturn(List.of(Seat.builder().seatNo(1).status(SeatStatus.AVAILABLE).build()));

        // when: 30개 스레드 동시 실행
        AtomicInteger successCount = new AtomicInteger();
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    concertService.getSeats(scheduleId);
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // then: 모든 요청이 성공했는지 및 최소 1회 이상의 DB 호출이 있었는지 검증
        assertThat(successCount.get()).isEqualTo(threadCount);
        verify(seatRepository, atLeastOnce()).findAllByScheduleId(scheduleId);
    }

    @Test
    @DisplayName("캐시 저장 시 설정된 TTL(1분) 적용 확인")
    void cacheTtlVerification() {
        // given: 조회할 데이터 세팅
        Long scheduleId = 3L;
        given(seatRepository.findAllByScheduleId(scheduleId))
                .willReturn(List.of(Seat.builder().seatNo(1).status(SeatStatus.AVAILABLE).build()));

        // when: 조회 후 캐시 적재
        concertService.getSeats(scheduleId);

        // then: Redis 키의 유효시간(TTL)이 60초 이내인지 확인
        String cacheKey = "seats:cache:" + scheduleId;
        Long expire = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);

        assertThat(expire).isNotNull().isPositive().isLessThanOrEqualTo(60L);
    }
}