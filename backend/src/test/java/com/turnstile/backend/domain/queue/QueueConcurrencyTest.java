package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 대규모 동시 요청 환경에서 Lua 스크립트를 통한 대기열 진입의 원자성을 검증하는 통합 테스트입니다.
 */
@DataRedisTest
@Import({RedisConfig.class, QueueRedisRepository.class, QueueService.class})
class QueueConcurrencyTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private ConcertRepository concertRepository;

    private static final Long CONCERT_ID = 1L;
    private static final String COUNTER_KEY = "q:counter:" + CONCERT_ID;
    private static final String WAITING_QUEUE_KEY = "q:waiting:" + CONCERT_ID;
    private static final String ACTIVE_QUEUE_KEY = "q:active:" + CONCERT_ID;
    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("1000명이 동시에 진입 시도해도 누락 없이 정확히 1000명이 대기열에 등록되어야 한다")
    void should_register_exactly_1000_users_concurrently() throws InterruptedException {
        // given: 오픈 시간이 지난 정상 콘서트 모킹
        Concert mockConcert = Concert.builder()
                .title("test_concert")
                .ticketingStartAt(LocalDateTime.now().minusDays(1))
                .build();
        given(concertRepository.findById(CONCERT_ID)).willReturn(Optional.of(mockConcert));

        int threadCount = 1000;
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when: 1000개의 스레드가 일제히 대기열 진입 시도
        for (int i = 0; i < threadCount; i++) {
            final long userId = i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    queueService.enterQueue(new QueueEntryRequest(userId, CONCERT_ID));
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        // then: 순번 발급 카운터와 ZSET 데이터 크기가 모두 1000인지 검증
        assertThat(redisTemplate.opsForValue().get(COUNTER_KEY)).isEqualTo(String.valueOf(threadCount));

        // then: 백그라운드 스케줄러가 개입하여 일부 유저를 ACTIVE로 넘겼을 수 있으므로,
        // WAITING 큐와 ACTIVE 큐의 총합이 정확히 1000명인지 검증
        Long waitingCount = redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY);
        Long activeCount = redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY);

        long totalRegistered = (waitingCount == null ? 0 : waitingCount) + (activeCount == null ? 0 : activeCount);
        assertThat(totalRegistered).isEqualTo(threadCount);

        // then: 스케줄러 트리거 집합(Set)에 해당 콘서트가 정상적으로 활성화되었는지 검증
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE_CONCERTS_KEY, String.valueOf(CONCERT_ID))).isTrue();
    }
}