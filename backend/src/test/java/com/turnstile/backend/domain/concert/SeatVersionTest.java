package com.turnstile.backend.domain.concert;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SeatVersionTest {

    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private EntityManager em;

    private ConcertSchedule createTestSchedule() {
        Concert concert = concertRepository.save(Concert.builder().title("test_concert").build());
        return concertScheduleRepository.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(LocalDateTime.now())
                .totalSeats(50)
                .build());
    }

    @Test
    @DisplayName("데이터를 수정하면 version 필드가 자동으로 1 증가")
    @Transactional
    void versionIncrementTest() {
        // given: 좌석을 생성하고 초기 version 값을 저장
        ConcertSchedule schedule = createTestSchedule();
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule).seatNo(10).price(new BigDecimal("50000")).status(SeatStatus.AVAILABLE).build());

        Long firstVersion = seat.getVersion();

        // when: 동일한 좌석을 update하여 다시 저장
        seat = Seat.builder()
                .id(seat.getId()).schedule(schedule).seatNo(10).price(new BigDecimal("50000")).status(SeatStatus.HOLD)
                .version(seat.getVersion()).build();
        Seat updatedSeat = seatRepository.saveAndFlush(seat);

        // then: version 값이 1 증가했는지 검증
        assertThat(updatedSeat.getVersion()).isEqualTo(firstVersion + 1);
    }

    @Test
    @DisplayName("동일한 version으로 동시에 수정하면 낙관적 락 예외가 발생")
    @Transactional
    void optimisticLockConflictTest() {
        // given: 좌석을 저장하고 두 사용자가 동일한 version을 조회한 상태를 가정
        ConcertSchedule schedule = createTestSchedule();
        Seat seat = seatRepository.saveAndFlush(Seat.builder()
                .schedule(schedule).seatNo(20).price(new BigDecimal("50000")).status(SeatStatus.AVAILABLE).build());
        Long seatId = seat.getId();

        Seat userA = seatRepository.findById(seatId).get();
        Seat userB = seatRepository.findById(seatId).get();

        // when: A가 먼저 update하고, B가 이전 버전으로 update 시도
        userA = Seat.builder()
                .id(userA.getId()).schedule(schedule).seatNo(20).price(new BigDecimal("50000")).status(SeatStatus.HOLD)
                .version(userA.getVersion()).build();
        seatRepository.saveAndFlush(userA); // DB 버전 0 -> 1

        em.clear();

        Seat userBRequest = Seat.builder()
                .id(seatId).schedule(schedule).seatNo(20).price(new BigDecimal("50000")).status(SeatStatus.SOLD)
                .version(0L) // 명시적으로 이전 버전 전달
                .build();

        // then: 낙관적 락 예외 발생
        assertThatThrownBy(() -> seatRepository.saveAndFlush(userBRequest))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}