package com.turnstile.backend.global.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictor {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 특정 스케줄의 좌석표 캐시를 즉시 삭제합니다.
     * Reservation(HOLD), Payment(SOLD), Scheduler(RELEASE) 시점에 호출됩니다.
     */
    public void evictSeatCache(Long scheduleId) {
        String key = SeatCacheKeys.seats(scheduleId);
        Boolean deleted = redisTemplate.delete(key);

        if (Boolean.TRUE.equals(deleted)) {
            log.info("[Global] Cache Eviction | Success (type: Seat, scheduleId: {})", scheduleId);
        } else if (Boolean.FALSE.equals(deleted)) {
            log.debug("[Global] Cache Eviction | Skip - Already absent (type: Seat, scheduleId: {})", scheduleId);
        } else {
            log.warn("[Global] Cache Eviction | Fail - No result from Redis (type: Seat, scheduleId: {})", scheduleId);
        }
    }
}