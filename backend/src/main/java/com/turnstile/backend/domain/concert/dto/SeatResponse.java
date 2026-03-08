package com.turnstile.backend.domain.concert.dto;

import com.turnstile.backend.domain.concert.entity.Seat;

public record SeatResponse(
        Long seatId,
        Integer seatNo,
        String status,
        Long price
) {
    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSeatNo(),
                seat.getStatus().name(),
                seat.getPrice()
        );
    }
}