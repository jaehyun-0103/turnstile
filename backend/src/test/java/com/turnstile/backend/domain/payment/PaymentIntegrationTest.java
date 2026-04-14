package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    @DisplayName("이중 결제 방어: 동일한 예약 그룹(groupId)에 대해 동시에 10번의 결제 요청이 들어와도 단 1번만 승인되고 결제 이력도 1개만 생성되어야 한다")
    void should_prevent_double_payment_under_concurrent_requests() throws InterruptedException {
        // given: 2번 유저, 20번 좌석 세팅
        Long targetUserId = 2L;
        Long targetSeatId = 20L;
        Long targetConcertId = 1L;
        String testGroupId = "integration-group-concurrent";
        Long seatPrice = 150000L;

        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = ?, hold_expires_at = DATE_ADD(NOW(6), INTERVAL 5 MINUTE) WHERE id = ?", targetUserId, targetSeatId);
        jdbcTemplate.update("INSERT INTO reservation (group_id, user_id, seat_id, price, status, reserved_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', NOW(6), NOW(6), NOW(6))",
                testGroupId, targetUserId, targetSeatId, seatPrice);

        PaymentRequest request = new PaymentRequest(testGroupId, seatPrice, targetConcertId);

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when: 10개의 스레드가 동시에 동일한 결제 요청을 시도
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // 시작 신호 대기
                    paymentService.processPayment(targetUserId, request);
                    successCount.incrementAndGet();
                } catch (TurnstileException e) {
                    failCount.incrementAndGet(); // ALREADY_PAID 등 멱등성 방어 예외
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 모든 스레드 동시 출발
        doneLatch.await();
        executorService.shutdown();

        // then: 정확히 1번만 결제 성공, 9번은 실패해야 함
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        // then: 결제 이력 테이블(Payment)에도 단 1개의 레코드만 존재해야 함
        long paymentRecordCount = paymentRepository.count();
        assertThat(paymentRecordCount).isEqualTo(1L);

        // then: 잔액도 중복 차감 없이 딱 1회분만 깎여야 함 (500,000 - 150,000 = 350,000)
        Long currentBalance = userRepository.findById(targetUserId).orElseThrow().getBalance();
        assertThat(currentBalance).isEqualTo(350000L);
    }
}