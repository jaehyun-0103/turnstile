package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.service.QueueService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대규모 동시 요청 환경에서 Lua 스크립트를 통한 대기열 진입의 원자성을 검증하는 통합 테스트입니다.
 */
@SpringBootTest
class QueueConcurrencyTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    // 더미 데이터의 예매 진행 중인 1번 콘서트 타겟팅
    private static final Long TARGET_CONCERT_ID = 1L;
    private static final String COUNTER_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":ticket_counter";
    private static final String WAITING_QUEUE_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":waiting";
    private static final String ACTIVE_QUEUE_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":active";
    private static final String ACTIVE_CONCERTS_KEY = "queue:scheduler:active_concerts";

    @AfterEach
    void tearDown() {
        // 테스트 후 레디스 데이터만 깔끔하게 비워서 다음 테스트와의 격리성 보장
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("1000명이 동시에 진입 시도해도 누락 없이 정확히 1000명이 대기열에 등록되어야 한다")
    void should_register_exactly_1000_users_concurrently() throws InterruptedException {
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
                    // Mock 객체 없이 실제 DB의 1번 콘서트 상태를 검증하고 대기열 진입
                    queueService.enterQueue(userId, new QueueEntryRequest(TARGET_CONCERT_ID));
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

        // then: 순번 발급 카운터 검증
        assertThat(redisTemplate.opsForValue().get(COUNTER_KEY)).isEqualTo(String.valueOf(threadCount));

        // then: WAITING 큐와 ACTIVE 큐의 총합이 1000명인지 검증
        Long waitingCount = redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY);
        Long activeCount = redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY);

        long totalRegistered = (waitingCount == null ? 0 : waitingCount) + (activeCount == null ? 0 : activeCount);
        assertThat(totalRegistered).isEqualTo(threadCount);

        // then: 스케줄러 트리거 집합(Set)에 1번 콘서트가 정상적으로 활성화되었는지 검증
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE_CONCERTS_KEY, String.valueOf(TARGET_CONCERT_ID))).isTrue();
    }
}