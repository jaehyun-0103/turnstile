package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.scheduler.ReservationCleanupScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReservationCleanupSchedulerTest {

    @Autowired private ReservationCleanupScheduler cleanupScheduler;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 다른 테스트에 영향을 주지 않도록 테스트용 예약 그룹 데이터만 삭제
        jdbcTemplate.update("DELETE FROM reservation WHERE group_id IN ('cleanup-old', 'cleanup-recent')");
    }

    @Test
    @DisplayName("스케줄러 동작 시 EXPIRED 상태가 된 지 24시간이 지난 데이터만 삭제되고 최근 데이터는 보존되어야 한다")
    void should_delete_only_expired_reservations_older_than_24_hours() {
        // given: 1번 유저, 30번/31번 좌석을 타겟으로 세팅
        Long userId = 1L;
        Long seatId1 = 30L;
        Long seatId2 = 31L;

        // 삭제 대상: 25시간 전에 EXPIRED 된 데이터
        LocalDateTime oldTime = LocalDateTime.now().minusHours(25);
        jdbcTemplate.update("INSERT INTO reservation (group_id, user_id, seat_id, price, status, reserved_at, created_at, updated_at) " +
                "VALUES ('cleanup-old', ?, ?, 150000, 'EXPIRED', ?, ?, ?)", userId, seatId1, oldTime, oldTime, oldTime);

        // 보존 대상: 2시간 전에 EXPIRED 된 데이터
        LocalDateTime recentTime = LocalDateTime.now().minusHours(2);
        jdbcTemplate.update("INSERT INTO reservation (group_id, user_id, seat_id, price, status, reserved_at, created_at, updated_at) " +
                "VALUES ('cleanup-recent', ?, ?, 150000, 'EXPIRED', ?, ?, ?)", userId, seatId2, recentTime, recentTime, recentTime);

        // when: 스케줄러 강제 실행
        cleanupScheduler.cleanupOldExpiredReservations();

        // then: 25시간 전 데이터는 삭제되었는지 검증
        List<Reservation> oldReservations = reservationRepository.findAllByGroupIdWithSeat("cleanup-old");
        assertThat(oldReservations).isEmpty();

        // then: 2시간 전 데이터는 지워지지 않고 보존되었는지 검증
        List<Reservation> recentReservations = reservationRepository.findAllByGroupIdWithSeat("cleanup-recent");
        assertThat(recentReservations).hasSize(1);
    }
}