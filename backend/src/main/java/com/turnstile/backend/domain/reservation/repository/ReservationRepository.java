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
}
