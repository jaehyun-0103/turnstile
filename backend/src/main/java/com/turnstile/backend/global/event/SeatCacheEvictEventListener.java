package com.turnstile.backend.global.event;

import com.turnstile.backend.global.common.CacheEvictor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 트랜잭션 생명주기에 맞춰 캐시 무효화 이벤트를 수신하고 처리하는 리스너입니다.
 * DB 상태와 Redis 캐시 간의 정합성을 보장하는 역할을 수행합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatCacheEvictEventListener {

    private final CacheEvictor cacheEvictor;

    /**
     * 트랜잭션이 성공적으로 커밋된 직후에만 캐시 무효화를 실행합니다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSeatCacheEvict(SeatCacheEvictEvent event) {
        // 트랜잭션 롤백 시 캐시가 조기 삭제되어 발생하는 Race Condition을 방지하기 위해 지연 실행
        log.info("[Global] Evict Seat Cache | Success (scheduleId: {})", event.scheduleId());
        cacheEvictor.evictSeatCache(event.scheduleId());
    }
}