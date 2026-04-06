package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.service.QueueService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 데이터 상태에 따른 대기열 폴링(Polling) 응답을 검증하는 통합 테스트입니다.
 */
@SpringBootTest
class QueuePollingTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private static final Long TARGET_CONCERT_ID = 1L;
    private static final String WAITING_QUEUE_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":waiting";
    private static final String ACTIVE_QUEUE_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":active";

    @AfterEach
    void tearDown() {
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("Redis 데이터 상태에 따라 ACTIVE, WAITING, INVALID 응답을 정확히 반환해야 한다")
    void should_return_correct_status_based_on_redis_state() {
        // given: Redis에 대기/활성 유저 세팅
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "10", 1); // 대기 1순위
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "20", 2); // 대기 2순위
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", 1700000000); // 활성

        // when & then 1: 진입 완료 유저 폴링 -> ACTIVE 및 실제 암호화된 JWT 토큰 반환 검증
        QueueStatusResponse activeRes = queueService.getQueueStatus(TARGET_CONCERT_ID, 99L);
        assertThat(activeRes.status()).isEqualTo("ACTIVE");
        assertThat(activeRes.accessToken()).isNotBlank();

        // when & then 2: 대기열 중간 유저 폴링 -> WAITING 반환
        QueueStatusResponse waitingRes = queueService.getQueueStatus(TARGET_CONCERT_ID, 20L);
        assertThat(waitingRes.status()).isEqualTo("WAITING");
        assertThat(waitingRes.rank()).isEqualTo(1L);

        // when & then 3: 대기열에 없는 유저 폴링 -> INVALID 반환
        QueueStatusResponse invalidRes = queueService.getQueueStatus(TARGET_CONCERT_ID, 777L);
        assertThat(invalidRes.status()).isEqualTo("INVALID");
        assertThat(invalidRes.rank()).isEqualTo(-1L);
    }
}