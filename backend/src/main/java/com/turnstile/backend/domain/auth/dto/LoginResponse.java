package com.turnstile.backend.domain.auth.dto;

public record LoginResponse(
        Long userId,
        String username,
        String name,
        Long balance
) {}