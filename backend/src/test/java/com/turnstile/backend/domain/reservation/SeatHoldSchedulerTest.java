package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long testSeatId;
    private User testUser;
    private final String testGroupId = "test-expired-group";

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            concertScheduleRepository.deleteAllInBatch();
            concertRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();

            testUser = userRepository.save(User.builder().username("scheduler_user").name("test").balance(0L).build());
            Concert concert = concertRepository.save(Concert.builder().title("테스트 콘서트").ticketingStartAt(LocalDateTime.now().minusDays(1)).build());
            ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder().concert(concert).concertDate(LocalDateTime.now().plusDays(10)).totalSeats(100).build());

            testSeatId = seatRepository.save(Seat.builder().schedule(schedule).seatNo(1).price(10000L).status(SeatStatus.AVAILABLE).build()).getId();
            return null;
        });
    }

    @Test
    @DisplayName("점유 만료 시간이 지난 좌석과 연관 예약 데이터는 스케줄러에 의해 각각 AVAILABLE과 EXPIRED 상태로 회수되어야 한다")
    void should_recover_expired_seats_and_expire_pending_reservations() {
        // given: 현재 시간 기준으로 이미 1분 전에 만료된 점유 좌석과 PENDING 예약 데이터 강제 세팅
        transactionTemplate.execute(status -> {
            Seat seat = seatRepository.findById(testSeatId).orElseThrow();
            LocalDateTime pastExpiredTime = LocalDateTime.now().minusMinutes(1);
            seatRepository.holdSeatConditionally(seat.getId(), testUser.getId(), pastExpiredTime);

            reservationRepository.save(Reservation.builder()
                    .groupId(testGroupId)
                    .user(testUser)
                    .seat(seat)
                    .status(ReservationStatus.PENDING)
                    .price(seat.getPrice())
                    .reservedAt(LocalDateTime.now().minusMinutes(6)) // 6분 전 예약
                    .build());
            return null;
        });

        // when: 스케줄러 수동 동작
        seatHoldScheduler.recoverExpiredSeats();

        // then: 좌석 상태가 AVAILABLE로 롤백되었는지 검증
        Seat recoveredSeat = seatRepository.findById(testSeatId).orElseThrow();
        assertThat(recoveredSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(recoveredSeat.getHoldUserId()).isNull();
        assertThat(recoveredSeat.getHoldExpiresAt()).isNull();

        // then: PENDING이던 예약 상태가 EXPIRED로 동기화되었는지 검증
        List<Reservation> reservations = reservationRepository.findAllByGroupId(testGroupId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }
}