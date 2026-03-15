package com.turnstile.backend.domain.concert.dto;

import com.turnstile.backend.domain.concert.entity.ConcertSchedule;

import java.time.LocalDateTime;

/**
 * 콘서트 회차 정보 응답 데이터입니다.
 */
public record ConcertScheduleResponse(
        Long id,
        LocalDateTime date,
        Integer totalSeats
) {
    public static ConcertScheduleResponse from(ConcertSchedule schedule) {
        return new ConcertScheduleResponse(
                schedule.getId(),
                schedule.getConcertDate(),
                schedule.getTotalSeats()
        );
    }
}