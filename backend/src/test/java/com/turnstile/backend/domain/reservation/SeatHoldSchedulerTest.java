package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료된 점유 좌석과 예약 내역을 정리하는 스케줄러 로직을 검증하는 통합 테스트입니다.
 */
@SpringBootTest
class SeatHoldSchedulerTest {

    @Autowired private SeatHoldScheduler seatHoldScheduler;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 테스트로 인해 발생한 예약 건만 지우고 10번 좌석을 다시 원상 복구
        reservationRepository.deleteAllInBatch();
        jdbcTemplate.update("UPDATE seat SET status = 'AVAILABLE', hold_user_id = NULL, hold_expires_at = NULL WHERE id = 10");
    }

    @Test
    @DisplayName("점유 만료 시간이 지난 좌석과 연관 예약 데이터는 스케줄러에 의해 각각 AVAILABLE과 EXPIRED 상태로 회수되어야 한다")
    void should_recover_expired_seats_and_expire_pending_reservations() {
        // given: 동시성 테스트와 겹치지 않는 10번 좌석 사용 (1번 유저가 선점했다고 가정)
        Long targetSeatId = 10L;
        Long targetUserId = 1L;
        String testGroupId = "test-expired-group";

        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(6);

        // 시간(과거) 조작이 필수적인 스케줄러 테스트 특성상, 강제로 과거 시간의 예약 건을 DB에 세팅
        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = ?, hold_expires_at = ? WHERE id = ?",
                targetUserId, pastTime, targetSeatId);

        jdbcTemplate.update("INSERT INTO reservation (group_id, user_id, seat_id, price, status, reserved_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?, NOW(6), NOW(6))",
                testGroupId, targetUserId, targetSeatId, 150000L, pastTime);

        // when: 스케줄러 강제 실행
        seatHoldScheduler.recoverExpiredSeats();

        // then: 좌석 상태가 AVAILABLE로 정상 회수되었는지 검증
        Seat recoveredSeat = seatRepository.findById(targetSeatId).orElseThrow();
        assertThat(recoveredSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(recoveredSeat.getHoldUserId()).isNull();
        assertThat(recoveredSeat.getHoldExpiresAt()).isNull();

        // then: 해당 예약 건의 상태가 EXPIRED로 갱신되었는지 검증
        List<Reservation> reservations = reservationRepository.findAllByGroupIdWithSeat(testGroupId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.getFirst().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }
}