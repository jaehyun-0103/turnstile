package com.turnstile.backend.domain.concert.dto;

import com.turnstile.backend.domain.concert.entity.ConcertSchedule;

import java.time.LocalDateTime;

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