package com.turnstile.backend.global;

import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.queue.event.UserTicketingCompletedEvent;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.global.event.SeatCacheEvictEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랜잭션 처리에 따른 비동기 이벤트(Spring ApplicationEvent)가 정상적으로 발행되는지 검증하는 통합 테스트입니다.
 */
@SpringBootTest
@RecordApplicationEvents
class EventPublishingIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private QueueRedisRepository queueRedisRepository;

    @Autowired private ApplicationEvents events; // 수집된 이벤트를 검증하기 위한 주입
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void tearDown() {
        // 테스트 후 DB 및 Redis 상태 원복
        jdbcTemplate.update("DELETE FROM payment");
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("UPDATE seat SET status = 'AVAILABLE', hold_user_id = NULL, hold_expires_at = NULL");
        jdbcTemplate.update("UPDATE users SET balance = 500000");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("좌석 예약 성공 시 SeatCacheEvictEvent가 정상적으로 발행되어야 한다")
    void should_publish_SeatCacheEvictEvent_on_reservation_success() {
        // given: 1번 유저가 1번 콘서트의 1번 좌석을 예약하는 상황 세팅
        Long targetConcertId = 1L;
        Long targetUserId = 1L;
        Long targetSeatId = 1L;

        // 유저를 대기열 통과 상태(ACTIVE)로 세팅
        queueRedisRepository.enterQueue(String.valueOf(targetConcertId), String.valueOf(targetUserId));
        queueRedisRepository.transitionQueue(String.valueOf(targetConcertId), 1000, 1000, Instant.now().getEpochSecond() + 300);

        ReservationRequest request = new ReservationRequest(targetConcertId, List.of(targetSeatId));

        // when: 예매 로직 정상 수행
        reservationService.reserveSeats(targetUserId, request);

        // then: 캐시 무효화 이벤트가 누락 없이 딱 1번 발행되었는지 검증
        long eventCount = events.stream(SeatCacheEvictEvent.class).count();
        assertThat(eventCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("결제 완료 시 활성 대기열에서 유저를 퇴출하는 UserTicketingCompletedEvent가 발행되어야 한다")
    void should_publish_UserTicketingCompletedEvent_on_payment_success() {
        // given: 2번 유저가 결제를 진행하는 상황 강제 세팅
        Long targetUserId = 2L;
        Long targetSeatId = 20L;
        Long targetConcertId = 1L;
        String testGroupId = "event-test-group";
        Long seatPrice = 150000L;

        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = ?, hold_expires_at = DATE_ADD(NOW(6), INTERVAL 5 MINUTE) WHERE id = ?", targetUserId, targetSeatId);
        jdbcTemplate.update("INSERT INTO reservation (group_id, user_id, seat_id, price, status, reserved_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', NOW(6), NOW(6), NOW(6))",
                testGroupId, targetUserId, targetSeatId, seatPrice);

        PaymentRequest request = new PaymentRequest(testGroupId, seatPrice, targetConcertId);

        // when: 결제 로직 정상 수행
        paymentService.processPayment(targetUserId, request);

        // then: 대기열 퇴출 이벤트가 정상적으로 발행되었는지 검증
        long eventCount = events.stream(UserTicketingCompletedEvent.class).count();
        assertThat(eventCount).isEqualTo(1L);
    }
}