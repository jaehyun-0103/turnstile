package com.turnstile.backend.domain.queue.dto;

public record QueueEntryResponse(
        Long concertId,
        Long rank,
        String status
) {}