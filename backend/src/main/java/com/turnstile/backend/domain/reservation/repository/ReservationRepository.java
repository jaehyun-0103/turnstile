package com.turnstile.backend.domain.reservation.repository;

import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 엔티티의 데이터 접근 및 상태 관리를 담당하는 리포지토리입니다.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * 결제 검증에 필요한 Seat, Schedule, Concert를 함께 조회합니다.
     */
    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.seat s " +
            "JOIN FETCH s.schedule sc " +
            "JOIN FETCH sc.concert " +
            "WHERE r.groupId = :groupId")
    List<Reservation> findAllByGroupIdWithSeat(@Param("groupId") String groupId);

    /**
     * 스케줄러 전용: 점유 시간이 지나 만료된 좌석을 참조하고 있는 PENDING 상태의 예약을 EXPIRED로 일괄 처리하고 수정 시각을 갱신합니다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reservation r " +
            "SET r.status = 'EXPIRED', r.updatedAt = :now " +
            "WHERE r.status = 'PENDING' AND r.seat.id IN " +
            "(SELECT s.id FROM Seat s WHERE s.status = 'HOLD' AND s.holdExpiresAt < :now)")
    int expirePendingReservations(@Param("now") LocalDateTime now);

    /**
     * 이중 결제 방어: 예약이 PENDING 상태일 때만 PAID로 업데이트하여 멱등성을 보장합니다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = 'PAID' WHERE r.groupId = :groupId AND r.status = 'PENDING'")
    int confirmReservations(@Param("groupId") String groupId);

    /**
     * N+1 문제를 방지하기 위해 Fetch Join을 사용하여 유저의 예약 내역과 관련된 좌석, 스케줄, 콘서트 정보를 한 번에 조회합니다.
     */
    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.seat s " +
            "JOIN FETCH s.schedule sc " +
            "JOIN FETCH sc.concert c " +
            "WHERE r.user.id = :userId " +
            "ORDER BY r.reservedAt DESC")
    List<Reservation> findAllByUserIdWithSeatAndSchedule(@Param("userId") Long userId);

    /**
     * 특정 시간 이전에 'EXPIRED' 상태가 된 예약 데이터들을 일괄 삭제합니다.
     */
    @Modifying
    @Query("DELETE FROM Reservation r WHERE r.status = 'EXPIRED' AND r.updatedAt <= :targetTime")
    int deleteExpiredReservationsOlderThan(@Param("targetTime") LocalDateTime targetTime);

    /**
     * 다중 콘서트 환경에서 타 콘서트 예매를 차단하지 않기 위해 격리 검증 시 사용됩니다.
     */
    @Query("SELECT COUNT(r) > 0 FROM Reservation r " +
            "JOIN r.seat s JOIN s.schedule sc " +
            "WHERE r.user.id = :userId AND sc.concert.id = :concertId AND r.status = :status")
    boolean existsByUserIdAndConcertIdAndStatus(@Param("userId") Long userId,
                                                @Param("concertId") Long concertId,
                                                @Param("status") ReservationStatus status);
}