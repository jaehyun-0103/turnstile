package com.turnstile.backend.domain.concert.controller;

import com.turnstile.backend.domain.concert.dto.ConcertResponse;
import com.turnstile.backend.domain.concert.dto.ConcertScheduleResponse;
import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.service.ConcertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 콘서트, 회차, 좌석 정보 조회를 담당하는 컨트롤러입니다.
 */
@Slf4j
@Tag(name = "4. Concert", description = "콘서트, 회차, 좌석 정보 조회 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertService concertService;

    /**
     * 예매 가능한 전체 콘서트 목록을 조회합니다.
     */
    @Operation(summary = "전체 콘서트 목록 조회", description = "요구 권한: **없음 (Public)**\n\n예매 가능한 전체 콘서트 목록을 조회합니다.")
    @GetMapping("/concerts")
    public ResponseEntity<List<ConcertResponse>> getConcerts() {
        log.info("[Concert] API Request | Get Concerts");
        return ResponseEntity.ok(concertService.getConcerts());
    }

    /**
     * 특정 콘서트의 상세 일정(회차) 목록을 조회합니다.
     */
    @Operation(summary = "콘서트 상세 일정(회차) 조회", description = "요구 권한: **없음 (Public)**\n\n특정 콘서트의 상세 일정 목록을 조회합니다.")
    @GetMapping("/concerts/{concertId}/schedules")
    public ResponseEntity<List<ConcertScheduleResponse>> getSchedules(
            @PathVariable("concertId") Long concertId) {
        log.info("[Concert] API Request | Get Schedules (concertId: {})", concertId);
        return ResponseEntity.ok(concertService.getSchedules(concertId));
    }

    /**
     * 특정 회차의 전체 좌석 상태 목록을 조회합니다.
     */
    @Operation(summary = "회차별 전체 좌석 상태 조회", description = "요구 권한: **LEVEL 2 (QUEUE)** 이상\n\n대기열을 통과한 유저만 접근 가능하며, 특정 회차의 실시간 좌석 상태를 조회합니다.")
    @GetMapping("/schedules/{scheduleId}/seats")
    public ResponseEntity<SeatListResponse> getSeats(
            @PathVariable("scheduleId") Long scheduleId) {
        log.info("[Concert] API Request | Get Seats (scheduleId: {})", scheduleId);
        return ResponseEntity.ok(concertService.getSeats(scheduleId));
    }
}