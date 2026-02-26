package com.turnstile.backend.domain.queue;

import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

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

    @BeforeEach
    void setUp() {
        // Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("1000명이 동시에 진입해도 Score(순번) 중복 없이 ZSET에 1000명이 정확히 등록")
    void concurrentEnterQueueTest() throws InterruptedException {
        // given: 1000명 동시 요청 환경 세팅
        int threadCount = 1000;
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when: 1000명의 유저가 동시에 대기열 진입 시도
        for (int i = 0; i < threadCount; i++) {
            final long userId = i;
            executorService.submit(() -> {
                try {
                    queueService.enterQueue(userId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 스레드가 끝날 때까지 대기

        // then: 검증
        // 카운터 값이 1000까지 올라갔는지 확인
        String finalCounter = redisTemplate.opsForValue().get("q:counter");
        assertThat(finalCounter).isEqualTo(String.valueOf(threadCount));

        // ZSET 안에 정확히 1000명의 데이터가 들어갔는지 확인
        Long zsetSize = redisTemplate.opsForZSet().size("q:waiting");
        assertThat(zsetSize).isEqualTo(threadCount);

        // 중복된 Score가 없는지 확인
        Set<String> users = redisTemplate.opsForZSet().range("q:waiting", 0, -1);
        assertThat(users).hasSize(threadCount);
    }
}