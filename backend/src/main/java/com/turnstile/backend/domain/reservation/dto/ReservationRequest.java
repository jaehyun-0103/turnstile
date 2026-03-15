package com.turnstile.backend.domain.reservation.dto;

import java.util.List;

/**
 * 좌석 예약을 요청하기 위한 클라이언트 입력 데이터입니다.
 */
public record ReservationRequest(
        Long userId,
        Long concertId,
        List<Long> seatIds
) {}