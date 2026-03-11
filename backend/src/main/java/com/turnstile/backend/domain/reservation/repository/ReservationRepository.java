package com.turnstile.backend.domain.reservation.repository;

import com.turnstile.backend.domain.reservation.entity.Reservation;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    // userId 필드로 Reservation 조회
    List<Reservation> findAllByUserId(Long userId);

    // groupId 필드로 연석 일괄 조회
    List<Reservation> findAllByGroupId(String groupId);

    // 스케줄러용: 만료된 좌석을 물고 있는 PENDING 예약을 EXPIRED로 일괄 변경
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = 'EXPIRED' " +
            "WHERE r.status = 'PENDING' AND r.seat.id IN " +
            "(SELECT s.id FROM Seat s WHERE s.status = 'HOLD' AND s.holdExpiresAt < :now)")
    int expirePendingReservations(@Param("now") LocalDateTime now);

    // 이중 결제 방어를 위한 멱등성 보장 (PENDING 상태일 때만 PAID로 업데이트)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = 'PAID' WHERE r.groupId = :groupId AND r.status = 'PENDING'")
    int confirmReservations(@Param("groupId") String groupId);

    // 유저 ID로 예약 내역 조회 (Fetch Join으로 성능 최적화)
    @Query("SELECT r FROM Reservation r " +
            "JOIN FETCH r.seat s " +
            "JOIN FETCH s.schedule sc " +
            "JOIN FETCH sc.concert c " +
            "WHERE r.user.id = :userId " +
            "ORDER BY r.reservedAt DESC")
    List<Reservation> findAllByUserIdWithDetails(@Param("userId") Long userId);
}
