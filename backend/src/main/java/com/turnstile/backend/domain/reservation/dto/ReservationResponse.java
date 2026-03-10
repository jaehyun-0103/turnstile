package com.turnstile.backend.domain.reservation.dto;

import java.time.LocalDateTime;

public record ReservationResponse(
        String groupId,
        String status,
        LocalDateTime expiredAt,
        String accessToken
) {
}