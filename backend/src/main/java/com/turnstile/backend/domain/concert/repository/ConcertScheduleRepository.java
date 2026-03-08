package com.turnstile.backend.domain.concert.repository;

import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConcertScheduleRepository extends JpaRepository<ConcertSchedule, Long> {
    // concertId 필드로 ConcertSchedule 조회
    List<ConcertSchedule> findAllByConcertId(Long concertId);
}
