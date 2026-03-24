package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 상태 변경(점유, 만료, 회수)을 처리하는 사용자 정의 @Query 메서드들의 동작을 검증하는 데이터 계층 테스트입니다.
 */
@DataJpaTest
@Import(JpaConfig.class)
class SeatRepositoryTest {

    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;

    @MockitoBean private CacheEvictor cacheEvictor;

    private ConcertSchedule createTestSchedule() {
        Concert concert = concertRepository.save(Concert.builder()
                .title("test_concert")
                .ticketingStartAt(LocalDateTime.now().minusDays(1))
                .build());
        return concertScheduleRepository.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(LocalDateTime.now())
                .totalSeats(50)
                .build());
    }

    @Test
    @DisplayName("좌석이 AVAILABLE 상태일 때만 HOLD로 상태가 안전하게 업데이트되어야 한다")
    void should_update_status_to_hold_when_seat_is_available() {
        // given: 예약 가능한 좌석 생성
        ConcertSchedule schedule = createTestSchedule();
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule).seatNo(10).price(50000L).status(SeatStatus.AVAILABLE).build());

        Long userId = 1L;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        // when: CAS 쿼리로 점유 시도
        int updatedRowCount = seatRepository.holdSeatConditionally(seat.getId(), userId, expiresAt);

        // then: 업데이트 성공(1) 및 상태 변경 확인
        assertThat(updatedRowCount).isEqualTo(1);
        Seat updatedSeat = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(updatedSeat.getStatus()).isEqualTo(SeatStatus.HOLD);
    }

    @Test
    @DisplayName("이미 누군가 점유한 HOLD 상태인 좌석에 접근하면 업데이트를 거부하고 0을 반환해야 한다")
    void should_return_zero_when_seat_is_already_held() {
        // given: 이미 점유된 좌석 생성
        ConcertSchedule schedule = createTestSchedule();
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule).seatNo(20).price(50000L).status(SeatStatus.HOLD).build());

        Long latecomerUserId = 2L;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        // when: 다른 유저가 해당 좌석 점유 시도
        int updatedRowCount = seatRepository.holdSeatConditionally(seat.getId(), latecomerUserId, expiresAt);

        // then: 업데이트 실패(0) 확인
        assertThat(updatedRowCount).isEqualTo(0);
    }

    @Test
    @DisplayName("만료 시간이 지난 HOLD 상태의 좌석을 AVAILABLE 상태로 일괄 변경해야 한다")
    void should_release_expired_seats() {
        // given: 이미 만료 시간이 10분 지난 임시 점유 좌석 생성
        ConcertSchedule schedule = createTestSchedule();
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(10);

        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule)
                .seatNo(30)
                .price(50000L)
                .status(SeatStatus.HOLD)
                .holdUserId(1L)
                .holdExpiresAt(pastTime)
                .build());

        // when: 현재 시간 기준으로 만료된 좌석 일괄 회수 쿼리 실행
        int releasedCount = seatRepository.releaseExpiredSeats(LocalDateTime.now());

        // then: 업데이트 성공(1) 및 상태가 AVAILABLE로 초기화되었는지 확인
        assertThat(releasedCount).isEqualTo(1);
        Seat updatedSeat = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(updatedSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(updatedSeat.getHoldUserId()).isNull();
        assertThat(updatedSeat.getHoldExpiresAt()).isNull();
    }

    @Test
    @DisplayName("만료된 좌석이 존재하는 회차(Schedule) ID 목록을 정확히 식별해야 한다")
    void should_find_schedule_ids_by_expired_seats() {
        // given: 만료된 좌석을 가진 회차 생성
        ConcertSchedule schedule = createTestSchedule();
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(10);

        seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule)
                .seatNo(40)
                .price(50000L)
                .status(SeatStatus.HOLD)
                .holdExpiresAt(pastTime)
                .build());

        // when: 만료 좌석이 있는 회차 ID 목록 조회 쿼리 실행
        List<Long> scheduleIds = seatRepository.findScheduleIdsByExpiredSeats(LocalDateTime.now());

        // then: 해당 회차 ID가 결과에 포함되어 있는지 확인
        assertThat(scheduleIds).hasSize(1);
        assertThat(scheduleIds.getFirst()).isEqualTo(schedule.getId());
    }
}