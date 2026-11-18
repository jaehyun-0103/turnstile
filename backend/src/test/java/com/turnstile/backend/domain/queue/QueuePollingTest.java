package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 데이터 상태에 따른 대기열 폴링(Polling) 응답을 검증하는 통합 테스트입니다.
 */
class QueuePollingTest extends DatabaseIntegrationTest {
    @Autowired QueueService queueService;

    @Test
    @DisplayName("Redis 데이터 상태에 따라 ACTIVE, WAITING, INVALID 응답을 정확히 반환해야 한다")
    void pollingReturnsActiveWaitingRankAndInvalid() {
        // given: Redis에 대기/활성 유저 세팅
        redis.opsForZSet().add(queueKey("waiting"), "10", 1); // 대기 1순위
        redis.opsForZSet().add(queueKey("waiting"), "20", 2); // 대기 2순위
        redis.opsForZSet().add(queueKey("active"), "99", Instant.now().getEpochSecond() + 120); // 활성

        // when & then 1: 진입 완료 유저 폴링 -> ACTIVE 및 실제 암호화된 JWT 토큰 반환 검증
        var active = queueService.getQueueStatus(concert.getId(), 99L);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.accessToken()).isNotBlank();

        // when & then 2: 대기열 중간 유저 폴링 -> WAITING 반환 및 토큰 미발급 검증
        var waiting = queueService.getQueueStatus(concert.getId(), 20L);
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(waiting.rank()).isEqualTo(1L);
        assertThat(waiting.accessToken()).isNull();

        // when & then 3: 대기열에 없는 유저 폴링 -> INVALID 반환 및 토큰 미발급 검증
        var invalid = queueService.getQueueStatus(concert.getId(), 777L);
        assertThat(invalid.status()).isEqualTo("INVALID");
        assertThat(invalid.rank()).isEqualTo(-1L);
        assertThat(invalid.accessToken()).isNull();
    }
}