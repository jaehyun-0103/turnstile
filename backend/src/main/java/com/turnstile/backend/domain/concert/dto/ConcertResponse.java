package com.turnstile.backend.domain.concert.dto;

import com.turnstile.backend.domain.concert.entity.Concert;

/**
 * 콘서트 기본 정보 응답 데이터입니다.
 */
public record ConcertResponse(
        Long id,
        String title,
        String description
) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getDescription()
        );
    }
}