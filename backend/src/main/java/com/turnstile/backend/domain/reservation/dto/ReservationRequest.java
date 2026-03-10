package com.turnstile.backend.domain.reservation.dto;

import java.util.List;

public record ReservationRequest(
        Long userId,
        Long concertId,
        List<Long> seatIds
) {
}