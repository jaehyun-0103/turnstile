package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 스케줄러의 빈자리 계산, 입장 전이, 만료 유저 삭제 로직을 검증하는 통합 테스트입니다.
 */
class QueueSchedulerTest extends DatabaseIntegrationTest {
    @Autowired Clock clock;

    @Test
    @DisplayName("스케줄러 동작 시 만료된 유저는 삭제되고, 동적 수용량의 빈자리만큼 대기열 인원이 입장해야 한다")
    void cleanupAndAdmissionRespectConfiguredCapacity() {
        // given: 동적 최대 허용 인원을 3명으로 세팅
        redis.opsForSet().add("queue:scheduler:active_concerts", String.valueOf(concert.getId()));
        redis.opsForValue().set(queueKey("capacity"), "3");

        long now = Instant.now().getEpochSecond();

        // given: 입장 완료된 유저 2명 (1명은 만료됨)
        redis.opsForZSet().add(queueKey("active"), "99", now - 60); // 만료
        redis.opsForZSet().add(queueKey("active"), "100", now + 120); // 유지

        // given: 대기열 유저 5명
        for (int i = 1; i <= 5; i++) redis.opsForZSet().add(queueKey("waiting"), String.valueOf(i), i);

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            var scheduler = new QueueScheduler(queue, metrics, clock);
            ReflectionTestUtils.setField(scheduler, "admissionBatchSize", 100L);
            ReflectionTestUtils.setField(scheduler, "expirationTime", 300L);

            // when: 정리 스케줄러(Cleanup)와 입장 스케줄러(Admission) 실행
            scheduler.scheduleCleanup();
            scheduler.scheduleAdmission();

            // then: 최대 정원 3명에 맞춰 정확히 유지 및 입장되었는지 검증
            assertThat(queue.getActiveUserCount(String.valueOf(concert.getId()))).isEqualTo(3);
            assertThat(redis.opsForZSet().score(queueKey("active"), "99")).isNull(); // 만료자 삭제 확인
            assertThat(queue.getWaitingUserCount(String.valueOf(concert.getId()))).isEqualTo(3); // 5명 중 2명 빠져나감
        } finally {
            metrics.close();
        }
    }
}