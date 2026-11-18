package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료된 점유 좌석과 예약 내역을 정리하는 스케줄러 로직을 검증하는 통합 테스트입니다.
 */
class SeatHoldSchedulerTest extends DatabaseIntegrationTest {

    @Test
    @DisplayName("점유 만료 시간이 지난 좌석과 연관 예약 데이터는 스케줄러에 의해 각각 AVAILABLE과 EXPIRED 상태로 회수되어야 한다")
    void expiredHoldReleasesSeatAndExpiresReservation() {
        // given: 첫 번째 좌석을 임시 점유한 뒤 점유 만료 시각을 과거 시점으로 세팅
        var reserved = reserve(userA, List.of(seatIds.getFirst()));
        jdbc.update("UPDATE seat SET hold_expires_at = ? WHERE id = ?", Timestamp.valueOf(now().minusMinutes(1)), seatIds.getFirst());

        // when: 스케줄러 강제 실행
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                new SeatHoldScheduler(seats, reservations, events).recoverExpiredSeats());

        // then: 좌석 상태가 AVAILABLE로 정상 회수되었는지 검증
        var recovered = seats.findById(seatIds.getFirst()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(recovered.getHoldUserId()).isNull();
        assertThat(recovered.getHoldExpiresAt()).isNull();

        // then: 해당 예약 건의 상태가 EXPIRED로 갱신되었는지 검증
        assertThat(reservations.findAllByGroupIdWithSeat(reserved.groupId())).hasSize(1).allMatch(r -> r.getStatus() == ReservationStatus.EXPIRED);
    }
}