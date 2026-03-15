package com.turnstile.backend.domain.reservation.dto;

import java.time.LocalDateTime;

/**
 * 좌석 임시 점유 성공 시 반환되는 예약 그룹 정보 및 결제용 토큰 데이터입니다.
 */
public record ReservationResponse(
        String groupId,
        String status,
        LocalDateTime expiredAt,
        String accessToken
) {}