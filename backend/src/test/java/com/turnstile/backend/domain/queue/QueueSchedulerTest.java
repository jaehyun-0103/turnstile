package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 스케줄러의 빈자리 계산, 입장 전이, 만료 유저 삭제 로직을 검증하는 통합 테스트입니다.
 */
@DataRedisTest
@Import({RedisConfig.class, QueueRedisRepository.class, QueueScheduler.class})
class QueueSchedulerTest {

    @Autowired private QueueScheduler queueScheduler;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private static final String CONCERT_ID = "1";
    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";
    private static final String WAITING_QUEUE_KEY = "q:waiting:" + CONCERT_ID;
    private static final String ACTIVE_QUEUE_KEY = "q:active:" + CONCERT_ID;
    private static final String MAX_ACTIVE_CONFIG_KEY = "q:config:max_active:" + CONCERT_ID;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        // 테스트의 직관성을 위해 스케줄러의 프로퍼티 값을 강제 주입
        ReflectionTestUtils.setField(queueScheduler, "admissionBatchSize", 100L);
        ReflectionTestUtils.setField(queueScheduler, "expirationTime", 300L);
    }

    @Test
    @DisplayName("스케줄러 동작 시 만료된 유저는 삭제되고, 동적 수용량의 빈자리만큼 대기열 인원이 입장해야 한다")
    void should_remove_expired_users_and_admit_waiting_users_based_on_capacity() {
        // given: 동적 최대 허용 인원을 3명으로 세팅
        redisTemplate.opsForSet().add(ACTIVE_CONCERTS_KEY, CONCERT_ID);
        redisTemplate.opsForValue().set(MAX_ACTIVE_CONFIG_KEY, "3");

        long now = Instant.now().getEpochSecond();

        // given: 입장 완료된 유저 2명 (1명은 만료됨)
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", now - 60); // 만료
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "100", now + 60); // 유지

        // given: 대기열 유저 5명
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, String.valueOf(i), i);
        }

        // when: 정리 스케줄러(Cleanup)와 입장 스케줄러(Admission) 실행
        queueScheduler.scheduleCleanup(); // 만료 유저 1명 삭제 (현재 활성 1명)
        queueScheduler.scheduleAdmission(); // 빈자리 2명분 만큼 대기열에서 추출하여 입장 처리

        // then: 최대 정원 3명에 맞춰 정확히 유지 및 입장되었는지 검증
        assertThat(redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY)).isEqualTo(3);
        assertThat(redisTemplate.opsForZSet().score(ACTIVE_QUEUE_KEY, "99")).isNull(); // 만료자 삭제 확인
        assertThat(redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY)).isEqualTo(3); // 5명 중 2명 빠져나감
    }
}