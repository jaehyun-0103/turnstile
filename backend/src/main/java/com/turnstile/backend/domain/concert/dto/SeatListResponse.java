package com.turnstile.backend.domain.concert.dto;

import java.util.List;

/**
 * 특정 회차의 전체 좌석 목록 응답 데이터입니다.
 */
public record SeatListResponse(
        Long scheduleId,
        List<SeatResponse> seats
) {}