package com.turnstile.backend.domain.concert.dto;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.turnstile.backend.domain.concert.entity.Concert;

import java.time.LocalDateTime;

/**
 * 콘서트 기본 정보 응답 데이터입니다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public record ConcertResponse(
        Long id,
        String title,
        String description,
        LocalDateTime ticketingStartAt
) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getDescription(),
                concert.getTicketingStartAt()
        );
    }
}