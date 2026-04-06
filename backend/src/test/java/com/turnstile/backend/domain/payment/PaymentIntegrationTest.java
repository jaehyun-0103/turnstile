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
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 테스트로 인해 생성된 결제/예약 내역 삭제 및 20번 좌석, 2번 유저 잔액(50만원) 복구
        paymentRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        jdbcTemplate.update("UPDATE seat SET status = 'AVAILABLE', hold_user_id = NULL, hold_expires_at = NULL WHERE id = 20");
        jdbcTemplate.update("UPDATE users SET balance = 500000 WHERE id = 2");
    }

    @Test
    @DisplayName("결제 성공 시 유저 잔액이 차감되고 좌석은 SOLD, 예약은 PAID 상태로 정확히 전이되어야 한다")
    void should_complete_payment_flow_atomically() {
        // given: 다른 테스트와 충돌하지 않는 2번 유저, 20번 좌석, 1번 콘서트 타겟팅
        Long targetUserId = 2L;
        Long targetSeatId = 20L;
        Long targetConcertId = 1L;
        String testGroupId = "integration-group-123";
        Long seatPrice = 150000L;

        // 결제 직전의 상태(좌석 점유 완료, 예약 대기 중)를 강제로 DB에 세팅
        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = ?, hold_expires_at = DATE_ADD(NOW(6), INTERVAL 5 MINUTE) WHERE id = ?", targetUserId, targetSeatId);
        jdbcTemplate.update("INSERT INTO reservation (group_id, user_id, seat_id, price, status, reserved_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', NOW(6), NOW(6), NOW(6))",
                testGroupId, targetUserId, targetSeatId, seatPrice);

        // 2번 유저의 잔액은 더미 데이터에 의해 500,000원이므로 150,000원 결제 요청
        PaymentRequest request = new PaymentRequest(testGroupId, seatPrice, targetConcertId);

        // when: 결제 프로세스 실행
        PaymentResponse response = paymentService.processPayment(targetUserId, request);

        // then: 잔액 차감 검증 (500,000 - 150,000 = 350,000)
        assertThat(response.balance()).isEqualTo(350000L);
        assertThat(userRepository.findById(targetUserId).orElseThrow().getBalance()).isEqualTo(350000L);

        // then: 좌석 상태가 SOLD로 변경되었는지 검증
        assertThat(seatRepository.findById(targetSeatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);

        // then: 예약 상태가 PAID로 변경되었는지 검증
        assertThat(reservationRepository.findAllByGroupIdWithSeat(testGroupId).getFirst().getStatus()).isEqualTo(ReservationStatus.PAID);
    }
}