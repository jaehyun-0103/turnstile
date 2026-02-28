package com.turnstile.backend.domain.queue.dto;

public record QueueStatusResponse(
        String status, // WAITING (대기열), ACTIVE (진입 완료), INVALID (만료 또는 튕김)
        Long rank,     // 내 앞에 남은 대기자 수
        String token   // ACTIVE 상태일 때 발급되는 JWT
) {}