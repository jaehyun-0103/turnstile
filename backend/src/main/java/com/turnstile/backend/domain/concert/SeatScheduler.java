package com.turnstile.backend.domain.concert;

import com.turnstile.backend.global.common.CacheEvictor;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SeatScheduler {
    private final SeatRepository seatRepository;
    private final CacheEvictor cacheEvictor;

    /**
     * 결제 시한(5분)이 만료된 임시 점유 좌석을 회수하고 캐시를 무효화합니다.
     */
    @Scheduled(fixedRate = 60000) // 1분 주기
    @Transactional
    public void releaseExpiredSeats() {
        LocalDateTime now = LocalDateTime.now();

        // 캐시 무효화가 필요한 스케줄 ID 식별
        List<Long> scheduleIds = seatRepository.findScheduleIdsByExpiredSeats(now);

        if (!scheduleIds.isEmpty()) {
            // DB 상태 회수 (HOLD -> AVAILABLE)
            seatRepository.releaseExpiredSeats(now);

            // 관련 스케줄의 좌석표 캐시 즉시 파괴 (팬텀 클릭 방지)
            scheduleIds.forEach(cacheEvictor::evictSeatCache);
        }
    }
}