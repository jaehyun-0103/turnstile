package com.turnstile.backend.domain.concert.dto;

import java.util.List;

public record SeatListResponse(
        Long scheduleId,
        List<SeatResponse> seats
) {
}