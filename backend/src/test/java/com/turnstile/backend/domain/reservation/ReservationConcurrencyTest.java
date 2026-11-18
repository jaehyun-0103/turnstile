package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.turnstile.backend.support.ReservationPaymentTestSupport;

/**
 * 다중 스레드 환경에서 좌석 선점(CAS)의 동시성 제어와 원자성을 검증하는 통합 테스트입니다.
 */
class ReservationConcurrencyTest extends ReservationPaymentTestSupport {

    @RepeatedTest(3)
    @DisplayName("동일 사용자 중복 예약 방지: 한 사용자가 동시에 서로 다른 좌석 그룹을 예약 요청해도 단 1개의 결제 대기 그룹만 생성되어야 한다")
    void sameUserCannotCreateTwoPendingGroups() throws Exception {
        // when: 동일 사용자(userA)가 서로 다른 좌석 조합으로 동시에 예약 시도
        List<Object> results = race(
                () -> reserve(userA, seatIds.subList(0, 2)),
                () -> reserve(userA, seatIds.subList(2, 4))
        );

        // then: 단 1건만 예약에 성공하고 나머지는 ALREADY_HAS_PENDING_RESERVATION 예외로 거절되어야 함
        assertThat(results.stream().filter(ReservationResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(results).contains(ErrorCode.ALREADY_HAS_PENDING_RESERVATION);

        // then: 생성된 예약 내역과 HOLD 상태 좌석이 정확히 2개만 유지되는지 검증
        assertThat(reservations.count()).isEqualTo(2);
        assertThat(seats.findAll().stream().filter(s -> s.getStatus() == SeatStatus.HOLD).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("동시 점유 경합: 두 사용자가 동시에 동일한 좌석을 요청할 경우 단 1명만 선점에 성공해야 한다")
    void differentUsersCannotHoldSameSeat() throws Exception {
        // given: 두 사용자가 경합할 동일 좌석 지정
        List<Long> selected = List.of(seatIds.getFirst());

        // when: userA와 userB가 동시에 동일 좌석 점유 시도
        List<Object> results = race(
                () -> reserve(userA, selected),
                () -> reserve(userB, selected)
        );

        // then: 1명만 선점에 성공하고 다른 1명은 SEAT_ALREADY_HELD 예외로 실패해야 함
        assertThat(results.stream().filter(ReservationResponse.class::isInstance).count()).isEqualTo(1);

        assertThat(results).contains(ErrorCode.SEAT_ALREADY_HELD);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 점유 정합성: 100명이 동시에 동일 좌석을 요청할 경우 단 1명만 선점에 성공해야 한다")
    void oneHundredUsersCompetingForOneSeatHaveOneWinner() throws Exception {
        // given: 동시에 경합할 100명의 테스트 사용자 생성
        List<User> participants = new ArrayList<>();
        for (int i = 0; i < 100; i++) participants.add(createUser(10000L));

        // when: 100명의 유저가 첫 번째 좌석을 동시에 요청
        List<Object> results = com.turnstile.backend.support.ConcurrentTestSupport.run(100, index -> {
            try {
                return reserve(participants.get(index), List.of(seatIds.getFirst()));
            } catch (TurnstileException e) {
                return e.getErrorCode();
            }
        });

        // then: 단 1명만 성공하고 99명은 SEAT_ALREADY_HELD 예외로 실패해야 함
        assertThat(results.stream().filter(ReservationResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(ErrorCode.class::isInstance).toList()).hasSize(99).allMatch(code -> code == ErrorCode.SEAT_ALREADY_HELD);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(seats.findById(seatIds.getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HOLD);
    }

    @Test
    @DisplayName("연석 원자성 검증: 연석 예매 시도 중 하나라도 선점된 좌석이 포함되어 있다면 예외가 발생하고 전체 롤백되어야 한다")
    void partiallyUnavailableSeatGroupRollsBackAllHolds() {
        // given: 두 번째 좌석을 userB가 먼저 정상 점유(HOLD)한 상태로 세팅
        reserve(userB, List.of(seatIds.get(1)));

        // when & then: userA가 첫 번째(AVAILABLE)와 두 번째(HOLD) 좌석을 함께 예매 시도 시 거절 검증
        TurnstileException error = assertThrows(TurnstileException.class, () -> reserve(userA, seatIds.subList(0, 2)));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_HELD);

        // then: 롤백 검증 (첫 번째 좌석은 여전히 AVAILABLE 유지)
        assertThat(seats.findById(seatIds.getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seats.findById(seatIds.getFirst()).orElseThrow().getHoldUserId()).isNull();
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("연석 교차 경합: 두 유저가 서로 엇갈린 순서의 연석(A: 1->2, B: 2->1)을 동시에 예매 시도해도 교착 상태(Deadlock) 없이 안전하게 한쪽 트랜잭션이 롤백되어야 한다")
    void oppositeSeatOrderCompletesWithoutDeadlock() throws Exception {
        // given: 정방향(forward) 및 역방향(reverse) 좌석 요청 목록 구성
        List<Long> forward = seatIds.subList(0, 2); // A는 정방향 요청
        List<Long> reverse = List.of(forward.get(1), forward.getFirst()); // B는 역방향 요청

        // when: User A와 User B가 동시에 엇갈린 순서로 예약 로직 타격
        List<Object> results = race(() -> reserve(userA, forward), () -> reserve(userB, reverse));

        // then: DB 데드락이 발생하지 않고, 한 명은 성공하고 한 명은 예외로 실패해야 함
        assertThat(results.stream().filter(ReservationResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(results).contains(ErrorCode.SEAT_ALREADY_HELD);

        // then: 두 좌석 모두 한 명의 유저에게만 귀속되어야 함 (트랜잭션 롤백 무결성 보장)
        var first = seats.findById(forward.getFirst()).orElseThrow();
        var second = seats.findById(forward.get(1)).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(second.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(first.getHoldUserId()).isEqualTo(second.getHoldUserId());
        assertThat(reservations.count()).isEqualTo(2);
    }
}