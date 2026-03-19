package com.turnstile.backend.domain.queue.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 대기열 진입을 위한 요청 데이터입니다.
 */
public record QueueEntryRequest(
        @NotNull(message = "concertId는 필수 값입니다.")
        @Positive(message = "concertId는 양수여야 합니다.")
        Long concertId
) {}