package com.turnstile.backend.domain.concert.service;

import com.turnstile.backend.domain.concert.dto.ConcertResponse;
import com.turnstile.backend.domain.concert.dto.ConcertScheduleResponse;
import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.dto.SeatResponse;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 콘서트, 회차, 좌석 정보 조회를 담당하는 서비스입니다.
 * 조회 성능 최적화를 위해 적극적인 캐싱 전략을 사용합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    /**
     * 예매 가능한 전체 콘서트 목록을 조회합니다.
     */
    @Cacheable(cacheNames = "concerts:cache", key = "'all'")
    public List<ConcertResponse> getConcerts() {
        log.info("[Concert] DB Query (Cache Miss) | Success (action: retrieve_all_concerts)");

        return concertRepository.findAll().stream()
                .map(ConcertResponse::from)
                .toList();
    }

    /**
     * 특정 콘서트에 속한 상세 일정(회차) 목록을 조회합니다.
     * @param concertId 콘서트 식별자
     * @return 해당 콘서트의 회차 목록
     */
    @Cacheable(cacheNames = "schedules:cache", key = "#concertId")
    public List<ConcertScheduleResponse> getSchedules(Long concertId) {
        concertRepository.findById(concertId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.CONCERT_NOT_FOUND));

        log.info("[Concert] DB Query (Cache Miss) | Success (action: retrieve_schedules, concertId: {})", concertId);

        return concertScheduleRepository.findAllByConcertId(concertId).stream()
                .map(ConcertScheduleResponse::from)
                .toList();
    }

    /**
     * 특정 회차의 전체 좌석 상태 목록을 조회합니다.
     * @param scheduleId 회차 식별자
     * @return 해당 회차의 좌석 상태 목록
     */
    // sync=true 옵션으로 다수의 사용자가 동시에 빈 캐시에 접근할 때 발생하는 캐시 스탬피드 현상을 방어함
    @Cacheable(cacheNames = "seats:cache", key = "#scheduleId", sync = true)
    public SeatListResponse getSeats(Long scheduleId) {
        concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.SCHEDULE_NOT_FOUND));

        log.info("[Concert] DB Query (Cache Miss) | Success (action: retrieve_seats, scheduleId: {})", scheduleId);

        List<SeatResponse> seats = seatRepository.findAllByScheduleId(scheduleId).stream()
                .map(SeatResponse::from)
                .toList();

        return new SeatListResponse(scheduleId, seats);
    }
}