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

@DataRedisTest
@Import({RedisConfig.class, QueueRedisRepository.class, QueueScheduler.class})
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

        // 원래 100이지만, 테스트의 직관성을 위해 리플렉션으로 Batch Size를 2로 줄입니다.
        ReflectionTestUtils.setField(queueScheduler, "admissionBatchSize", 2L);
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
        queueScheduler.scheduleCleanup();
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

    @Test
    @DisplayName("만료된 유저는 삭제, 빈자리가 Batch Size보다 작으면 빈자리만큼만 입장")
    void scheduleQueueCleanupAndCapacityTest() {
        // 테스트 격리를 위해 다시 100으로 설정
        ReflectionTestUtils.setField(queueScheduler, "admissionBatchSize", 100L);

        // given: 동적 허용 인원 3명 세팅
        redisTemplate.opsForSet().add(ACTIVE_CONCERTS_KEY, CONCERT_ID);
        redisTemplate.opsForValue().set(MAX_ACTIVE_CONFIG_KEY, "3");

        long now = Instant.now().getEpochSecond();

        // given: 진입 완료 상태 2명 (만료 1, 유효 1) -> 빈자리는 현재 1자리
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", now - 60); // 만료
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "100", now + 60);  // 유효

        // given: 대기열 5명
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, String.valueOf(i), i);
        }

        // when: 정리 스케줄러 실행 후 입장 스케줄러 실행
        queueScheduler.scheduleCleanup(); // 만료 1명 지워짐 -> 빈자리 2명 됨
        queueScheduler.scheduleAdmission(); // 빈자리(2) vs Batch Size(100) -> 2명 입장

        // then: 검증
        // 원래 유효 1명 + 신규 2명 = 총 3명
        Long activeSize = redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY);
        assertThat(activeSize).isEqualTo(3);

        // 만료된 사용자는 삭제되었는지 확인
        Double expiredUserScore = redisTemplate.opsForZSet().score(ACTIVE_QUEUE_KEY, "99");
        assertThat(expiredUserScore).isNull();

        // 대기열 5명 중 2명만 빠져나가서 3명 남음
        Long waitingSize = redisTemplate.opsForZSet().size(WAITING_QUEUE_KEY);
        assertThat(waitingSize).isEqualTo(3);
    }
}