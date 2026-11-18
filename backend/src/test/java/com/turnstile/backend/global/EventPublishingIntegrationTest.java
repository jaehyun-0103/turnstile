package com.turnstile.backend.global;

import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.queue.event.UserTicketingCompletedEvent;
import com.turnstile.backend.global.event.SeatCacheEvictEvent;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랜잭션 처리에 따른 커밋 후 이벤트(Spring ApplicationEvent) 발행과 Redis 후처리 성공 경로를 검증하는 통합 테스트입니다.
 */
@RecordApplicationEvents
class EventPublishingIntegrationTest extends DatabaseIntegrationTest {

    @Autowired ApplicationEvents recordedEvents; // 수집된 이벤트를 검증하기 위한 주입

    @Test
    @DisplayName("좌석 예약 성공 시 SeatCacheEvictEvent가 정상적으로 발행되어야 한다")
    void reservationPublishesSeatCacheEvent() {
        // when: 예매 로직 정상 수행
        reserve(userA, List.of(seatIds.getFirst()));

        // then: 캐시 무효화 이벤트가 누락 없이 딱 1번 발행되었는지 검증
        assertThat(recordedEvents.stream(SeatCacheEvictEvent.class).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 완료 시 UserTicketingCompletedEvent 및 SeatCacheEvictEvent가 발행되고 활성 대기열에서 유저가 퇴출되어야 한다")
    void successfulPaymentPublishesCompletionAndRemovesActiveUser() {
        // given: 좌석을 임시 점유한 상태 및 활성 대기열 진입 여부 확인
        var reserved = reserve(userA, List.of(seatIds.getFirst()));
        assertThat(queue.isActiveUser(String.valueOf(concert.getId()), String.valueOf(userA.getId()))).isTrue();

        // when: 결제 로직 정상 수행
        paymentService.processPayment(userA.getId(), new PaymentRequest(reserved.groupId(), 1000L, concert.getId()));

        // then: 대기열 퇴출 이벤트 및 캐시 무효화 이벤트 발행과 활성 대기열 제거 검증
        assertThat(recordedEvents.stream(UserTicketingCompletedEvent.class).count()).isEqualTo(1);
        assertThat(recordedEvents.stream(SeatCacheEvictEvent.class).count()).isEqualTo(2);
        assertThat(queue.isActiveUser(String.valueOf(concert.getId()), String.valueOf(userA.getId()))).isFalse();
    }
}