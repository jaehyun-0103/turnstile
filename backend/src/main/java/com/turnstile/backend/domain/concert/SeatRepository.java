package com.turnstile.backend.domain.concert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // scheduleId 필드로 Seat 조회
    List<Seat> findAllByScheduleId(Long scheduleId);

    // 1. CAS 기반 상태 업데이트 (좌석 점유)
    // 리턴값이 1이면 점유 성공, 0이면 실패
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s " +
            "SET s.status = 'HOLD', s.holdUserId = :userId, s.holdExpiresAt = :expiresAt " +
            "WHERE s.id = :seatId AND s.status = 'AVAILABLE'")
    int holdSeatConditionally(@Param("seatId") Long seatId,
                              @Param("userId") Long userId,
                              @Param("expiresAt") LocalDateTime expiresAt);

    // 2. TTL 기반 만료 좌석 일괄 회수 (스케줄러)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s " +
            "SET s.status = 'AVAILABLE', s.holdUserId = null, s.holdExpiresAt = null " +
            "WHERE s.status = 'HOLD' AND s.holdExpiresAt < :now")
    int releaseExpiredSeats(@Param("now") LocalDateTime now);
}