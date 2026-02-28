package com.turnstile.backend.domain.queue.dto;

public record QueueEntryRequest(
        Long userId,
        Long concertId
) {}