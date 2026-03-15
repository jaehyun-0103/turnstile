package com.turnstile.backend.domain.queue.dto;

/**
 * 클라이언트의 폴링 요청에 대한 대기열 순위 또는 입장 토큰 반환 데이터입니다.
 */
public record QueueStatusResponse(
        Long concertId,
        Long rank,
        String status,
        String accessToken
) {}