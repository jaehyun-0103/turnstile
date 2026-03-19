package com.turnstile.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 좌석 예약을 요청하기 위한 클라이언트 입력 데이터입니다.
 */
public record ReservationRequest(
        @NotNull(message = "concertId는 필수 값입니다.")
        @Positive(message = "concertId는 양수여야 합니다.")
        Long concertId,

        @NotEmpty(message = "최소 1개 이상의 좌석을 선택해야 합니다.")
        List<Long> seatIds
) {}