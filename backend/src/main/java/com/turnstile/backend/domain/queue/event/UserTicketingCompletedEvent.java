package com.turnstile.backend.domain.queue.event;

/**
 * 유저의 예매 및 결제 트랜잭션이 성공적으로 완료되었을 때 발행되는 이벤트 객체입니다.
 * 대기열 도메인으로 비즈니스 완료 상태를 전파하여 슬롯을 반납시키기 위해 사용됩니다.
 */
public record UserTicketingCompletedEvent(
        Long concertId,
        Long userId
) {}