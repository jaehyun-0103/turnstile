package com.turnstile.backend.domain.reservation.dto;

import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;

/**
 * 마이페이지에서 예약 내역을 확인할 때 사용되는 상세 응답 데이터입니다.
 */
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