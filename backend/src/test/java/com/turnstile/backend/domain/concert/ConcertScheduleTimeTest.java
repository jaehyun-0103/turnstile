package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 콘서트 회차의 예매 가능 시간 경계 조건(오픈 시각 포함, 공연 시작 시각 미포함)을 검증하는 단위 테스트입니다.
 */
class ConcertScheduleTimeTest {

    private final LocalDateTime open = LocalDateTime.of(2026, 11, 8, 12, 0);
    private final LocalDateTime start = open.plusHours(1);

    private ConcertSchedule schedule() {
        Concert concert = Concert.builder()
                .ticketingStartAt(open)
                .build();

        return ConcertSchedule.builder()
                .concert(concert)
                .concertDate(start)
                .build();
    }

    @Test
    @DisplayName("예매 오픈 시각 정각부터 예매가 가능하고 직전 시각에는 불가능해야 한다")
    void should_include_opening_time_as_reservable() {
        // given: 오픈 시각이 설정된 콘서트 회차 생성
        ConcertSchedule schedule = schedule();

        // when & then: 오픈 시각 경계값(직전, 정각, 직후)에 따른 예매 가능 여부 검증
        assertThat(schedule.isReservable(open.minusNanos(1))).isFalse();
        assertThat(schedule.isReservable(open)).isTrue();
        assertThat(schedule.isReservable(open.plusNanos(1))).isTrue();
    }

    @Test
    @DisplayName("공연 시작 시각 정각부터는 예매가 불가능하고 직전 시각까지만 가능해야 한다")
    void should_exclude_concert_start_time_from_reservable() {
        // given: 공연 시작 시각이 설정된 콘서트 회차 생성
        ConcertSchedule schedule = schedule();

        // when & then: 공연 시작 시각 경계값(직전, 정각, 직후)에 따른 예매 가능 여부 검증
        assertThat(schedule.isReservable(start.minusNanos(1))).isTrue();
        assertThat(schedule.isReservable(start)).isFalse();
        assertThat(schedule.isReservable(start.plusNanos(1))).isFalse();
    }
}