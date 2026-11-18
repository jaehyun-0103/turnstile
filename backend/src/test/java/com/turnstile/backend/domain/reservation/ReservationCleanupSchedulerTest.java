package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.scheduler.ReservationCleanupScheduler;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료 후 일정 시간이 지난 예약 데이터를 정리하는 스케줄러의 동작을 검증하는 통합 테스트입니다.
 */
class ReservationCleanupSchedulerTest extends DatabaseIntegrationTest {

    /**
     * 지정된 예약 상태와 수정 시각(updated_at)을 가진 테스트용 예약 데이터를 생성합니다.
     */
    private Reservation createReservation(ReservationStatus status, int seatIndex, LocalDateTime updatedAt) {
        var reservation = reservations.save(Reservation.builder()
                .groupId(UUID.randomUUID().toString()).user(userA)
                .seat(seats.findById(seatIds.get(seatIndex)).orElseThrow())
                .status(status).price(1000L).reservedAt(updatedAt).build());
        jdbc.update("UPDATE reservation SET updated_at = ? WHERE id = ?", Timestamp.valueOf(updatedAt), reservation.getId());
        return reservation;
    }

    @Test
    @DisplayName("스케줄러 동작 시 EXPIRED 상태가 된 지 24시간이 지난 데이터만 삭제되고 최근 데이터 및 다른 상태의 데이터는 보존되어야 한다")
    void deletesOnlyExpiredRowsWithOldRecordedUpdatedAt() {
        // given: 삭제 대상(25시간 전 EXPIRED) 및 보존 대상(2시간 전 EXPIRED, 25시간 전 PENDING/PAID) 데이터 세팅
        var old = createReservation(ReservationStatus.EXPIRED, 0, now().minusHours(25)); // 삭제 대상: 25시간 전 EXPIRED
        var recent = createReservation(ReservationStatus.EXPIRED, 1, now().minusHours(2)); // 보존 대상: 2시간 전 EXPIRED
        var pending = createReservation(ReservationStatus.PENDING, 2, now().minusHours(25)); // 보존 대상: 25시간 전 PENDING
        var paid = createReservation(ReservationStatus.PAID, 3, now().minusHours(25)); // 보존 대상: 25시간 전 PAID

        // when: 스케줄러 강제 실행
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                new ReservationCleanupScheduler(reservations).cleanupOldExpiredReservations());

        // then: 25시간 전 EXPIRED 데이터는 삭제되었는지 검증
        assertThat(reservations.findById(old.getId())).isEmpty();

        // then: 2시간 전 EXPIRED 데이터 및 PENDING, PAID 상태 데이터는 보존되었는지 검증
        assertThat(reservations.findAll()).extracting(Reservation::getId).containsExactlyInAnyOrder(recent.getId(), pending.getId(), paid.getId());
    }
}