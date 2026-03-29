package com.turnstile.backend.domain.concert.dto;

import com.turnstile.backend.domain.concert.entity.Seat;

/**
 * 개별 좌석의 상태 및 가격 정보 응답 데이터입니다.
 */
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