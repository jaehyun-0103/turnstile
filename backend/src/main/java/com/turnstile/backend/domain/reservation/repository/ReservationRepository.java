package com.turnstile.backend.domain.reservation.repository;

import com.turnstile.backend.domain.reservation.entity.Reservation;
import io.lettuce.core.dynamic.annotation.Param;
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
     * Seat과 Schedule을 한 번에 조회(Fetch Join)합니다.
     */
    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.seat s " +
            "JOIN FETCH s.schedule " +
            "WHERE r.groupId = :groupId")
    List<Reservation> findAllByGroupIdWithSeat(@Param("groupId") String groupId);

    /**
     * 스케줄러 전용: 점유 시간이 지나 만료된 좌석을 참조하고 있는 PENDING 상태의 예약을 EXPIRED로 일괄 처리합니다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = 'EXPIRED' " +
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
    List<Reservation> findAllByUserIdWithDetails(@Param("userId") Long userId);
}