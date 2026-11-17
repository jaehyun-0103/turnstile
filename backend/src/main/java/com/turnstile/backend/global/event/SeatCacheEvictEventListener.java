package com.turnstile.backend.global.event;

import com.turnstile.backend.global.common.CacheEvictor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
    private final MeterRegistry meterRegistry;

    /**
     * 트랜잭션이 성공적으로 커밋된 직후에만 캐시 무효화를 실행합니다.
     * 후처리 실패가 이미 확정된 DB 트랜잭션에 전파되지 않도록 예외를 격리하고 메트릭을 기록합니다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSeatCacheEvict(SeatCacheEvictEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 트랜잭션 롤백 시 캐시가 조기 삭제되어 발생하는 Race Condition을 방지하기 위해 지연 실행
            cacheEvictor.evictSeatCache(event.scheduleId());

            log.info("[Global] Evict Seat Cache | Success (scheduleId: {})", event.scheduleId());
        } catch (DataAccessException e) {
            meterRegistry.counter(
                    "turnstile.post_commit.failures",
                    "operation", "seat_cache_evict"
            ).increment();

            log.error("[Global] Evict Seat Cache | Fail - DB commit is unchanged (scheduleId: {})", event.scheduleId(), e);
        } finally {
            sample.stop(meterRegistry.timer(
                    "turnstile.post_commit.duration",
                    "operation", "seat_cache_evict"
            ));
        }
    }
}