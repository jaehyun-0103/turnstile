package com.turnstile.backend.domain.concert.repository;

import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 콘서트 회차 엔티티의 데이터 접근을 처리하는 리포지토리입니다.
 */
public interface ConcertScheduleRepository extends JpaRepository<ConcertSchedule, Long> {

    List<ConcertSchedule> findAllByConcertId(Long concertId);
}