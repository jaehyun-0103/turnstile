package com.turnstile.backend.domain.concert.controller;

import com.turnstile.backend.domain.concert.dto.ConcertResponse;
import com.turnstile.backend.domain.concert.dto.ConcertScheduleResponse;
import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.service.ConcertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertService concertService;

    // 전체 콘서트 목록 조회 API
    @GetMapping("/concerts")
    public ResponseEntity<List<ConcertResponse>> getConcerts() {
        log.info("[Concert] API Request | Get Concerts");
        return ResponseEntity.ok(concertService.getConcerts());
    }

    // 특정 콘서트의 회차 목록 조회 API
    @GetMapping("/concerts/{concertId}/schedules")
    public ResponseEntity<List<ConcertScheduleResponse>> getSchedules(
            @PathVariable("concertId") Long concertId) {
        log.info("[Concert] API Request | Get Schedules (concertId: {})", concertId);
        return ResponseEntity.ok(concertService.getSchedules(concertId));
    }

    // 특정 회차의 전체 좌석 조회 API (AuthInterceptor 통과 대상)
    @GetMapping("/schedules/{scheduleId}/seats")
    public ResponseEntity<SeatListResponse> getSeats(
            @PathVariable("scheduleId") Long scheduleId) {
        log.info("[Concert] API Request | Get Seats (scheduleId: {})", scheduleId);
        return ResponseEntity.ok(concertService.getSeats(scheduleId));
    }
}