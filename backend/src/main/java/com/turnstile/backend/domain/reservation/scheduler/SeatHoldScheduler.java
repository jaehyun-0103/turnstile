package com.turnstile.backend.domain.reservation.scheduler;

import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.global.common.CacheEvictor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주기적으로 만료된 임시 점유 좌석을 회수하고, 관련 예약을 취소 처리하는 스케줄러입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldScheduler {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final CacheEvictor cacheEvictor;

    /**
     * 점유 시간이 만료된 좌석과 예약 데이터를 정리하고, 변경된 스케줄의 좌석 캐시를 무효화합니다.
     */
    @Transactional
    @Scheduled(fixedDelay = 60000) // 1분 주기 실행
    public void recoverExpiredSeats() {
        LocalDateTime now = LocalDateTime.now();

        // 캐시 무효화를 위해 만료될 좌석이 포함된 스케줄 ID 사전 조회
        List<Long> affectedScheduleIds = seatRepository.findScheduleIdsByExpiredSeats(now);

        if (affectedScheduleIds.isEmpty()) {
            return; // 회수할 자원이 없으면 조기 종료
        }

        // 유령 예약 동기화: PENDING -> EXPIRED
        int expiredReservationCount = reservationRepository.expirePendingReservations(now);

        // 좌석 상태 롤백: HOLD -> AVAILABLE 및 점유자 정보 초기화
        int releasedSeatCount = seatRepository.releaseExpiredSeats(now);

        // 변경된 스케줄 구역의 좌석표 캐시 즉시 무효화
        for (Long scheduleId : affectedScheduleIds) {
            cacheEvictor.evictSeatCache(scheduleId);
        }

        log.info("[Reservation] Scheduler Cleanup | Executed (releasedSeats: {}, expiredReservations: {}, evictedCaches: {})", releasedSeatCount, expiredReservationCount, affectedScheduleIds.size());
    }
}