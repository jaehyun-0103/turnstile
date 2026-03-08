package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
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

    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";
    private static final String CONCERT_ID = "1";
    private static final String WAITING_QUEUE_KEY = "q:waiting:" + CONCERT_ID;
    private static final String ACTIVE_QUEUE_KEY = "q:active:" + CONCERT_ID;
    private static final String MAX_ACTIVE_CONFIG_KEY = "q:config:max_active:" + CONCERT_ID;

    @BeforeEach
    void setUp() {
        // 테스트 독립성 보장을 위해 Redis 전체 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("만료된 진입 완료 유저는 삭제, 빈자리만큼 대기열에서 정확히 이동")
    void scheduleQueueTest() {
        // given: 3명의 동적 허용 인원
        redisTemplate.opsForSet().add(ACTIVE_CONCERTS_KEY, CONCERT_ID);
        redisTemplate.opsForValue().set(MAX_ACTIVE_CONFIG_KEY, "3");

        long now = Instant.now().getEpochSecond();

        // given: 진입 완료 상태 2명 (만료 1, 유효 1)
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", now - 60); // 만료
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "100", now + 60);  // 유효

        // given: 대기열 5명
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, String.valueOf(i), i);
        }

        // when: 스케줄러 1회 강제 실행
        queueScheduler.scheduleAdmission();

        // then: 검증
        // 만료 1명 삭제 후 1명 유지 -> 빈자리 2명 채워 총 3명 유지
        Long activeSize = redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY);
        assertThat(activeSize).isEqualTo(3);

        // 만료된 사용자는 진입 완료 상태에서 삭제
        Double expiredUserScore = redisTemplate.opsForZSet().score(ACTIVE_QUEUE_KEY, "99");
        assertThat(expiredUserScore).isNull();

        // 빈자리만큼 대기열에서 이동되어 대기열은 3명 남음
        Long waitingSize = redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY);
        assertThat(waitingSize).isEqualTo(3);
    }
}