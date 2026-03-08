package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
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

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import({RedisConfig.class, QueueService.class})
class QueueConcurrencyTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoBean private JwtProvider jwtProvider; // QueueService 로드를 위한 가짜 bean

    private static final Long CONCERT_ID = 1L;
    private static final String COUNTER_KEY = "q:counter:" + CONCERT_ID;
    private static final String WAITING_QUEUE_KEY = "q:waiting:" + CONCERT_ID;
    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    @BeforeEach
    void setUp() {
        // 테스트 독립성 보장을 위해 Redis 전체 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("1000명이 동시에 진입해도 대기열에 1000명이 정확히 등록")
    void concurrentEnterQueueTest() throws InterruptedException {
        // given: 1000명 동시 요청 환경 세팅
        int threadCount = 1000;
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when: 1000명의 유저가 동시에 특정 콘서트 대기열 진입 시도
        for (int i = 0; i < threadCount; i++) {
            final long userId = i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();   // 준비 완료
                    startLatch.await();       // 동시에 시작 대기

                    QueueEntryRequest request = new QueueEntryRequest(userId, CONCERT_ID);
                    queueService.enterQueue(request);

                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();     // 모든 스레드 준비될 때까지 대기
        startLatch.countDown(); // 동시에 시작
        doneLatch.await();      // 모든 작업 끝날 때까지 대기
        executorService.shutdown();

        // then: 검증
        // 해당 콘서트의 카운터 값이 1000까지 올라갔는지 확인
        String finalCounter = redisTemplate.opsForValue().get(COUNTER_KEY);
        assertThat(finalCounter).isEqualTo(String.valueOf(threadCount));

        // 해당 콘서트 ZSET 안에 정확히 1000명의 데이터가 들어갔는지 확인
        Long zsetSize = redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY);
        assertThat(zsetSize).isEqualTo(threadCount);

        // 중복된 사용자 없이 1000명 정확히 저장되었는지 확인
        Set<String> users = redisTemplate.opsForZSet().range(WAITING_QUEUE_KEY, 0, -1);
        assertThat(users).hasSize(threadCount);

        // 스케줄러가 인식할 수 있도록 활성화된 콘서트 목록에 잘 등록되었는지 확인
        Boolean isConcertActive = redisTemplate.opsForSet().isMember(ACTIVE_CONCERTS_KEY, String.valueOf(CONCERT_ID));
        assertThat(isConcertActive).isTrue();
    }
}