package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
import com.turnstile.backend.global.config.RedisConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

    // 내부 설정 클래스
    @TestConfiguration
    static class TestMetricsConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final Long TARGET_CONCERT_ID = 1L;
    private static final String ACTIVE_CONCERTS_KEY = "queue:scheduler:active_concerts";
    private static final String WAITING_QUEUE_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":waiting";
    private static final String ACTIVE_QUEUE_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":active";
    private static final String MAX_ACTIVE_CONFIG_KEY = "queue:concert:" + TARGET_CONCERT_ID + ":capacity";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(queueScheduler, "admissionBatchSize", 100L);
        ReflectionTestUtils.setField(queueScheduler, "expirationTime", 300L);
    }

    @AfterEach
    void tearDown() {
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("스케줄러 동작 시 만료된 유저는 삭제되고, 동적 수용량의 빈자리만큼 대기열 인원이 입장해야 한다")
    void should_remove_expired_users_and_admit_waiting_users_based_on_capacity() {
        // given: 동적 최대 허용 인원을 3명으로 세팅
        redisTemplate.opsForSet().add(ACTIVE_CONCERTS_KEY, String.valueOf(TARGET_CONCERT_ID));
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
        queueScheduler.scheduleCleanup();
        queueScheduler.scheduleAdmission();

        // then: 최대 정원 3명에 맞춰 정확히 유지 및 입장되었는지 검증
        assertThat(redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY)).isEqualTo(3);
        assertThat(redisTemplate.opsForZSet().score(ACTIVE_QUEUE_KEY, "99")).isNull(); // 만료자 삭제 확인
        assertThat(redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY)).isEqualTo(3); // 5명 중 2명 빠져나감
    }
}