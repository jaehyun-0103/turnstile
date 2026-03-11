package com.turnstile.backend.domain.reservation.dto;

import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;

public record MyReservationResponse(
        Long reservationId,
        String groupId,
        String concertTitle,
        LocalDateTime concertDate,
        int seatNo,
        ReservationStatus status,
        long price,
        LocalDateTime reservedAt
) {}