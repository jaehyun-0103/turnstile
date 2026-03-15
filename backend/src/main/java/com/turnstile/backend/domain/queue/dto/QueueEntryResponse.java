package com.turnstile.backend.domain.queue.dto;

/**
 * 대기열 진입 처리 결과 및 현재 상태를 반환하는 응답 데이터입니다.
 */
public record QueueEntryResponse(
        Long concertId,
        Long rank,
        String status
) {}