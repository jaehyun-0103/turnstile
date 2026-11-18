package com.turnstile.backend.domain.queue;

import com.turnstile.backend.support.ConcurrentTestSupport;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대규모 동시 요청 환경에서 Lua 스크립트를 통한 대기열 진입의 원자성을 검증하는 통합 테스트입니다.
 */
class QueueConcurrencyTest extends DatabaseIntegrationTest {

    @Test
    @DisplayName("1000명이 동시에 진입 시도해도 누락 없이 중복되지 않는 순번으로 대기열에 등록되어야 한다")
    void oneThousandConcurrentEntriesHaveUniqueRanksWithoutLoss() throws Exception {
        // when: 1000개의 스레드가 일제히 대기열 진입 시도
        var tickets = ConcurrentTestSupport.run(1000, index ->
                queue.enterQueue(String.valueOf(concert.getId()), String.valueOf(index + 1)));

        // then: 순번 발급 카운터 및 중복 없는 고유 티켓 발급 검증
        assertThat(tickets).hasSize(1000).allMatch(ticket -> ticket > 0);
        assertThat(tickets.stream().distinct().count()).isEqualTo(1000);
        assertThat(redis.opsForValue().get(queueKey("ticket_counter"))).isEqualTo("1000");

        // then: WAITING 큐에 1000명이 등록되고 ACTIVE 큐는 비어있는지 검증
        assertThat(queue.getWaitingUserCount(String.valueOf(concert.getId()))).isEqualTo(1000);
        assertThat(queue.getActiveUserCount(String.valueOf(concert.getId()))).isZero();

        // then: 스케줄러 트리거 집합(Set)에 대상 콘서트가 정상적으로 활성화되었는지 검증
        assertThat(redis.opsForSet().isMember("queue:scheduler:active_concerts", String.valueOf(concert.getId()))).isTrue();
    }
}