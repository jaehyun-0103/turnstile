package com.turnstile.backend.domain.concert.service;

import com.turnstile.backend.domain.concert.dto.ConcertResponse;
import com.turnstile.backend.domain.concert.dto.ConcertScheduleResponse;
import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.dto.SeatResponse;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    // 파라미터가 없으므로 고정 키('all')를 사용하여 전체 콘서트 목록 캐싱
    @Cacheable(cacheNames = "concerts:cache", key = "'all'")
    public List<ConcertResponse> getConcerts() {
        return concertRepository.findAll().stream()
                .map(ConcertResponse::from)
                .toList();
    }

    // 콘서트 ID를 키로 사용하여 회차 목록 캐싱
    @Cacheable(cacheNames = "schedules:cache", key = "#concertId")
    public List<ConcertScheduleResponse> getSchedules(Long concertId) {
        return concertScheduleRepository.findAllByConcertId(concertId).stream()
                .map(ConcertScheduleResponse::from)
                .toList();
    }

    // sync=true 옵션으로 캐시 스탬피드 방어 (동시 요청 시 단일 스레드만 DB 접근 허용)
    @Cacheable(cacheNames = "seats:cache", key = "#scheduleId", sync = true)
    public SeatListResponse getSeats(Long scheduleId) {
        List<SeatResponse> seats = seatRepository.findAllByScheduleId(scheduleId).stream()
                .map(SeatResponse::from)
                .toList();

        return new SeatListResponse(scheduleId, seats);
    }
}