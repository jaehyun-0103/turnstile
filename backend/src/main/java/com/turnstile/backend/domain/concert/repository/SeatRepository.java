package com.turnstile.backend.domain.concert.repository;

import com.turnstile.backend.domain.concert.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 좌석 엔티티의 데이터 접근 및 상태 변경을 처리하는 리포지토리입니다.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    // scheduleId 필드로 Seat 조회
    List<Seat> findAllByScheduleId(Long scheduleId);

    // 한 번의 쿼리로 여러 좌석을 점유(HOLD)함
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s " +
            "SET s.status = 'HOLD', s.holdUserId = :userId, s.holdExpiresAt = :expiresAt " +
            "WHERE s.id IN :seatId AND s.status = 'AVAILABLE'")
    int holdSeatsConditionally(@Param("seatId") List<Long> seatId,
                              @Param("userId") Long userId,
                              @Param("expiresAt") LocalDateTime expiresAt);

    // TTL이 만료된 임시 점유 좌석들을 스케줄러를 통해 일괄적으로 예매 가능 상태로 회수함
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s " +
            "SET s.status = 'AVAILABLE', s.holdUserId = null, s.holdExpiresAt = null " +
            "WHERE s.status = 'HOLD' AND s.holdExpiresAt < :now")
    int releaseExpiredSeats(@Param("now") LocalDateTime now);

    // 좌석 상태 변경에 따른 캐시 무효화를 위해 만료된 좌석이 포함된 회차 식별자를 조회함
    @Query("SELECT DISTINCT s.schedule.id FROM Seat s WHERE s.status = 'HOLD' AND s.holdExpiresAt < :now")
    List<Long> findScheduleIdsByExpiredSeats(@Param("now") LocalDateTime now);

    // 결제 완료 이벤트 발생 시 예약 그룹에 속한 좌석들을 최종 판매 완료(SOLD) 상태로 확정함
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s SET s.status = 'SOLD' " +
            "WHERE s.id IN (SELECT r.seat.id FROM Reservation r WHERE r.groupId = :groupId)")
    void confirmSeats(@Param("groupId") String groupId);

    // 예약 검증 로직에서 연관된 스케줄 및 콘서트 정보를 단일 쿼리(Fetch Join)로 적재함
    @Query("SELECT s FROM Seat s " +
            "JOIN FETCH s.schedule sc JOIN FETCH sc.concert " +
            "WHERE s.id IN :seatIds")
    List<Seat> findAllByIdWithScheduleAndConcert(@Param("seatIds") List<Long> seatIds);
}