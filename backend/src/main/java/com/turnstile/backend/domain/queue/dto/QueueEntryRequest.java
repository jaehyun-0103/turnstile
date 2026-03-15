package com.turnstile.backend.domain.queue.dto;

/**
 * 대기열 진입을 위한 요청 데이터입니다.
 */
public record QueueEntryRequest(
        Long userId,
        Long concertId
) {}