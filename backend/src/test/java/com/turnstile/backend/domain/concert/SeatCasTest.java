package com.turnstile.backend.domain.concert;

import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
class SeatCasTest {

    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;

    @MockitoBean
    private CacheEvictor cacheEvictor;

    private ConcertSchedule createTestSchedule() {
        Concert concert = concertRepository.save(Concert.builder().title("test_concert").build());
        return concertScheduleRepository.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(LocalDateTime.now())
                .totalSeats(50)
                .build());
    }

    @Test
    @DisplayName("좌석이 AVAILABLE 상태일 때만 HOLD로 상태가 업데이트")
    void casSuccessTest() {
        // given: 예약 가능한 좌석 생성 (AVAILABLE)
        ConcertSchedule schedule = createTestSchedule();
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule).seatNo(10).price(50000L).status(SeatStatus.AVAILABLE).build());

        Long userId = 1L;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        // when: CAS 쿼리로 점유 시도
        int updatedRowCount = seatRepository.holdSeatConditionally(seat.getId(), userId, expiresAt);

        // then: 업데이트된 행의 수가 1이면서 상태도 HOLD로 변경
        assertThat(updatedRowCount).isEqualTo(1);

        Seat updatedSeat = seatRepository.findById(seat.getId()).get();
        assertThat(updatedSeat.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(updatedSeat.getHoldUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("이미 HOLD 상태인 좌석에 접근하면 0 반환")
    void casConflictTest() {
        // given: 누군가 이미 점유한 좌석 생성 (HOLD)
        ConcertSchedule schedule = createTestSchedule();
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule).seatNo(20).price(50000L).status(SeatStatus.HOLD).build());

        Long latecomerUserId = 2L;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        // when: 다른 유저가 점유된 좌석 점유 시도
        int updatedRowCount = seatRepository.holdSeatConditionally(seat.getId(), latecomerUserId, expiresAt);

        // then: 업데이트된 행의 수 반환 (0)
        assertThat(updatedRowCount).isEqualTo(0);
    }
}