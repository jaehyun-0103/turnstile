package com.turnstile.backend.domain.queue;

import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import({RedisConfig.class, QueueScheduler.class})
class QueueSchedulerTest {

    @Autowired private QueueScheduler queueScheduler;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private static final String WAITING_QUEUE_KEY = "q:waiting";
    private static final String ACTIVE_QUEUE_KEY = "q:active";
    private static final String MAX_ACTIVE_CONFIG_KEY = "q:config:max_active";

    @BeforeEach
    void setUp() {
        // Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("만료된 진입 완료 유저는 삭제, 빈자리만큼 대기열에서 정확히 이동")
    void scheduleQueueTest() {
        // given: 3명의 동적 허용 인원
        redisTemplate.opsForValue().set(MAX_ACTIVE_CONFIG_KEY, "3");

        long now = Instant.now().getEpochSecond();

        // given: 2명의 진입 완료 상태 (만료 1, 유효 1)
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "user_expired", now - 60); // 60초 전 만료
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "user_valid", now + 60);   // 60초 후 만료

        // given: 5명의 대기열
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "wait_user_" + i, i);
        }

        // when: 스케줄러 1회 강제 실행
        queueScheduler.scheduleQueue();

        // then: 검증
        // 만료된 사용자 1명 삭제 후,빈자리 이동으로 진입 완료 상태 3명
        Long activeSize = redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY);
        assertThat(activeSize).isEqualTo(3);

        // 만료된 사용자는 진입 완료 상태에서 삭제
        Double expiredUserScore = redisTemplate.opsForZSet().score(ACTIVE_QUEUE_KEY, "user_expired");
        assertThat(expiredUserScore).isNull();

        // 빈자리 이동으로 대기열 3명
        Long waitingSize = redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY);
        assertThat(waitingSize).isEqualTo(3);
    }
}