package com.turnstile.backend.domain.queue.event;

import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 대기열 도메인과 관련된 이벤트를 수신하여 비동기적/지연 처리를 수행하는 리스너입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueEventListener {

    private final QueueRedisRepository queueRedisRepository;

    /**
     * 결제 트랜잭션이 성공적으로 커밋된 직후에만 동작하여 활성 대기열에서 유저를 퇴출합니다.
     * 롤백 시 유저가 대기열에서 억울하게 튕겨 나가는 현상을 구조적으로 방어합니다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketingCompleted(UserTicketingCompletedEvent event) {
        log.info("[Queue] Evict Active User | Success (concertId: {}, userId: {})", event.concertId(), event.userId());
        queueRedisRepository.removeActiveUser(String.valueOf(event.concertId()), String.valueOf(event.userId()));
    }
}