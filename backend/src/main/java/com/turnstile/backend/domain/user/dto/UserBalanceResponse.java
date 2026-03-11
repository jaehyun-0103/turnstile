package com.turnstile.backend.domain.user.dto;

public record UserBalanceResponse(
        Long userId,
        Long balance
) {}