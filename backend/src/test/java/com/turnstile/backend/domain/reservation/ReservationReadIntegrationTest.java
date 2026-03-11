package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.reservation.dto.MyReservationResponse;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ReservationReadIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder().username("buyer").name("구매자").balance(1000L).build());

        Concert concert = concertRepository.save(Concert.builder()
                .title("아이유 콘서트").ticketingStartAt(LocalDateTime.now().minusDays(1)).build());

        ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder()
                .concert(concert).concertDate(LocalDateTime.of(2024, 10, 25, 19, 0)).totalSeats(100).build());

        Seat seat = seatRepository.save(Seat.builder()
                .schedule(schedule).seatNo(1).price(150000L).build());

        reservationRepository.save(Reservation.builder()
                .groupId("req-uuid-1234").user(testUser).seat(seat)
                .status(ReservationStatus.PENDING).price(150000L).reservedAt(LocalDateTime.of(2024, 10, 15, 12, 0))
                .build());
    }

    @Test
    @DisplayName("내 예약 확인: 로그인한 사용자의 예약 목록이 명세서와 일치하게 조회되어야 한다.")
    void getMyReservationsTest() {
        // when
        List<MyReservationResponse> result = reservationService.getMyReservations(testUser.getId());

        // then
        assertThat(result).hasSize(1);
        MyReservationResponse response = result.get(0);
        assertThat(response.concertTitle()).isEqualTo("아이유 콘서트");
        assertThat(response.groupId()).isEqualTo("req-uuid-1234");
        assertThat(response.seatNo()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.concertDate()).isEqualTo(LocalDateTime.of(2024, 10, 25, 19, 0));
    }
}