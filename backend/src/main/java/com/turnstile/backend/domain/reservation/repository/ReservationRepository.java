package com.turnstile.backend.domain.reservation.repository;

import com.turnstile.backend.domain.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    // userId 필드로 Reservation 조회
    List<Reservation> findAllByUserId(Long userId);
}
