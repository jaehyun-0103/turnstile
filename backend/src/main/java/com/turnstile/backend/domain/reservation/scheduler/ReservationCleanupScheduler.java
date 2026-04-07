package com.turnstile.backend.domain.reservation.scheduler;

import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 만료된 지 24시간이 지난(CS 유예 기간 경과) 예약 내역을 DB에서 영구 삭제하여
 * 스토리지 용량을 확보하고 조회 성능을 유지하는 스케줄러입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationCleanupScheduler {

    private final ReservationRepository reservationRepository;

    // 매일 새벽 3시 0분 0초에 실행
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldExpiredReservations() {
        // 기준 시간: 현재 시간으로부터 24시간 전
        LocalDateTime targetTime = LocalDateTime.now().minusHours(24);
        log.info("[Reservation] Cleanup Scheduler | Started (Target Time: before {})", targetTime);

        int deletedCount = reservationRepository.deleteExpiredReservationsOlderThan(targetTime);
        log.info("[Reservation] Cleanup Scheduler | Completed (Deleted: {} rows)", deletedCount);
    }
}