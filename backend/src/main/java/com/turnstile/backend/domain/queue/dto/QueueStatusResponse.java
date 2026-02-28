package com.turnstile.backend.domain.queue.dto;

public record QueueStatusResponse(
        Long concertId,
        Long rank,
        String status,
        String accessToken
) {}