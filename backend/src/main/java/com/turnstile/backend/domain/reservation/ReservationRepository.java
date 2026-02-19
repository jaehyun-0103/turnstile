package com.turnstile.backend.domain.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    // userId 필드로 Reservation 조회
    List<Reservation> findAllByUserId(Long userId);
}
