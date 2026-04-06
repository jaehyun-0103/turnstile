package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 데이터베이스 환경에서 결제 완료 후 잔액, 좌석, 예약 상태가 원자적으로 변경되는지 검증하는 통합 테스트입니다.
 */
@SpringBootTest
class PaymentIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private UserRepository userRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private Long testUserId;
    private Long testSeatId;
    private Long testConcertId;
    private final String testGroupId = "integration-group-123";

    @BeforeEach
    void setUp() {
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        cleanUpDatabase();

        transactionTemplate.execute(status -> {
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            concertScheduleRepository.deleteAllInBatch();
            concertRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();

            User user = userRepository.save(User.builder().username("tester").name("테스터").balance(200000L).build());
            testUserId = user.getId();

            Concert concert = concertRepository.save(Concert.builder().title("아이유 콘서트").ticketingStartAt(LocalDateTime.now().minusDays(1)).build());
            testConcertId = concert.getId();

            ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder().concert(concert).concertDate(LocalDateTime.now().plusDays(10)).totalSeats(100).build());

            Seat seat = seatRepository.save(Seat.builder().schedule(schedule).seatNo(1).price(150000L).status(SeatStatus.HOLD).holdUserId(user.getId()).holdExpiresAt(LocalDateTime.now().plusMinutes(5)).build());
            testSeatId = seat.getId();

            reservationRepository.save(Reservation.builder().groupId(testGroupId).user(user).seat(seat).status(ReservationStatus.PENDING).price(150000L).reservedAt(LocalDateTime.now()).build());
            return null;
        });
    }

    // 테스트 종료 직후 데이터베이스 정리
    @AfterEach
    void tearDown() {
        cleanUpDatabase();
    }

    // 외래키 참조 무결성을 위해 자식 엔티티부터 부모 엔티티 순으로 안전하게 삭제
    private void cleanUpDatabase() {
        transactionTemplate.execute(status -> {
            paymentRepository.deleteAllInBatch(); // 생성된 Payment 내역 가장 먼저 삭제
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            concertScheduleRepository.deleteAllInBatch();
            concertRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
            return null;
        });
    }

    @Test
    @DisplayName("결제 성공 시 유저 잔액이 차감되고 좌석은 SOLD, 예약은 PAID 상태로 정확히 전이되어야 한다")
    void should_complete_payment_flow_atomically() {
        // given: 20만원 보유 유저가 15만원 결제 요청
        PaymentRequest request = new PaymentRequest(testGroupId, 150000L, testConcertId);

        // when: 결제 프로세스 실행
        PaymentResponse response = paymentService.processPayment(testUserId, request);

        // then: 잔액 차감 검증
        assertThat(response.balance()).isEqualTo(50000L);
        assertThat(userRepository.findById(testUserId).orElseThrow().getBalance()).isEqualTo(50000L);

        // then: 좌석 상태 검증
        assertThat(seatRepository.findById(testSeatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);

        // then: 예약 상태 검증
        assertThat(reservationRepository.findAllByGroupIdWithSeat(testGroupId).getFirst().getStatus()).isEqualTo(ReservationStatus.PAID);
    }
}