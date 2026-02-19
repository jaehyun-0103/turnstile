package com.turnstile.backend.domain.concert;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    // scheduleId 필드로 Seat 조회
    List<Seat> findAllByScheduleId(Long scheduleId);
}
