package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.support.RepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 상태 변경(점유, 만료, 회수)을 처리하는 사용자 정의 @Query 메서드들의 동작을 검증하는 데이터 계층 테스트입니다.
 */
class SeatRepositoryTest extends RepositoryIntegrationTest {
    @Autowired SeatRepository repository;
    @Autowired EntityManager entityManager;
    private Long seatId;
    private Long userId;
    private Long scheduleId;

    @BeforeEach
    void createData() {
        // 테스트용 사용자, 공연, 회차 및 AVAILABLE 상태의 좌석 데이터 생성
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        var user = User.builder().username("seat-query-user").name("테스트").balance(0L).build();
        entityManager.persist(user);
        var concert = Concert.builder().title("쿼리 테스트").ticketingStartAt(now.minusDays(1)).build();
        entityManager.persist(concert);
        var schedule = ConcertSchedule.builder().concert(concert).concertDate(now.plusDays(1)).totalSeats(1).build();
        entityManager.persist(schedule);
        var seat = Seat.builder().schedule(schedule).seatNo(1).price(1000L).status(SeatStatus.AVAILABLE).build();
        entityManager.persist(seat);
        entityManager.flush();
        seatId = seat.getId();
        userId = user.getId();
        scheduleId = schedule.getId();
    }

    @Test
    @DisplayName("좌석이 AVAILABLE 상태일 때만 HOLD로 상태가 안전하게 업데이트되어야 한다")
    void should_update_status_to_hold_when_seat_is_available() {
        // when & then: CAS 쿼리로 점유 시도 시 업데이트 성공(1) 및 HOLD 상태 변경 확인
        assertThat(repository.holdSeatsConditionally(List.of(seatId), userId, LocalDateTime.now().plusMinutes(5))).isEqualTo(1);
        assertThat(repository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.HOLD);
    }

    @Test
    @DisplayName("이미 누군가 점유한 HOLD 상태인 좌석에 접근하면 업데이트를 거부하고 0을 반환해야 한다")
    void should_return_zero_when_seat_is_already_held() {
        // given: 기존 사용자가 좌석을 먼저 점유한 상태 설정
        var expiry = LocalDateTime.now().plusMinutes(5);
        repository.holdSeatsConditionally(List.of(seatId), userId, expiry);

        // when & then: 다른 사용자가 동일 좌석 점유 시도 시 업데이트 실패(0) 확인
        assertThat(repository.holdSeatsConditionally(List.of(seatId), userId + 1, expiry)).isZero();
    }

    @Test
    @DisplayName("만료 시간이 지난 HOLD 상태의 좌석을 AVAILABLE 상태로 일괄 변경해야 한다")
    void should_release_expired_seats() {
        // given: 10분 전 만료된 시각으로 좌석 점유 상태 설정
        repository.holdSeatsConditionally(List.of(seatId), userId, LocalDateTime.now().minusMinutes(10));

        // when & then: 만료 좌석 일괄 회수 쿼리 실행 시 AVAILABLE 상태 및 점유 정보 초기화 확인
        assertThat(repository.releaseExpiredSeats(LocalDateTime.now())).isEqualTo(1);
        var seat = repository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldUserId()).isNull();
        assertThat(seat.getHoldExpiresAt()).isNull();
    }

    @Test
    @DisplayName("만료된 좌석이 존재하는 회차(Schedule) ID 목록을 정확히 식별해야 한다")
    void should_find_schedule_ids_by_expired_seats() {
        // given: 해당 회차에 속한 좌석을 만료 상태로 점유 설정
        repository.holdSeatsConditionally(List.of(seatId), userId, LocalDateTime.now().minusMinutes(10));

        // when & then: 만료 좌석이 존재하는 회차 ID 목록 조회 시 해당 회차 ID 반환 확인
        assertThat(repository.findScheduleIdsByExpiredSeats(LocalDateTime.now())).containsExactly(scheduleId);
    }
}