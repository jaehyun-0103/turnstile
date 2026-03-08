package com.turnstile.backend.domain.concert.dto;

import com.turnstile.backend.domain.concert.entity.Concert;

public record ConcertResponse(
        Long id,
        String title,
        String description
) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                "BTS 콘서트"
        );
    }
}