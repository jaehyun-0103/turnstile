package com.turnstile.backend.domain.concert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 예매 가능한 전체 콘서트 목록을 조회합니다.
     */
    @Cacheable(cacheNames = "concert:info", key = "'all'")
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
    @Cacheable(cacheNames = "concert:schedules", key = "#concertId")
    public List<ConcertScheduleResponse> getSchedules(Long concertId) {
        concertRepository.findById(concertId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.CONCERT_NOT_FOUND));

        log.info("[Concert] DB Query (Cache Miss) | Success (action: retrieve_schedules, concertId: {})", concertId);

        return concertScheduleRepository.findAllByConcert_Id(concertId).stream()
                .map(ConcertScheduleResponse::from)
                .toList();
    }

    /**
     * 특정 회차의 전체 좌석 상태 목록을 조회합니다.
     * @param scheduleId 회차 식별자
     * @return 해당 회차의 좌석 상태 목록
     */
    public SeatListResponse getSeats(Long scheduleId) {
        String cacheKey = "concert:schedule:" + scheduleId + ":seats";
        String lockKey = "lock:concert:schedule:" + scheduleId;

        // 1단계: Redis 캐시 레이어 선조회 수행
        String cachedData = redisTemplate.opsForValue().get(cacheKey);
        if (cachedData != null) {
            try {
                return objectMapper.readValue(cachedData, SeatListResponse.class);
            } catch (JsonProcessingException e) {
                log.error("[Concert] Cache Deserialization Fail | (message: {})", e.getMessage());
            }
        }

        // 2단계: 캐시 미스 발생 시 분산 락(Try-Lock) 획득 시도를 통해 DB 쏠림 방어선 구축 (SETNX 제어)
        Boolean isLocked = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(3));

        if (Boolean.TRUE.equals(isLocked)) {
            try {
                // 대표 권한 스레드가 진입하여 단건 검증 및 DB 조회 인입
                concertScheduleRepository.findById(scheduleId)
                        .orElseThrow(() -> new TurnstileException(ErrorCode.SCHEDULE_NOT_FOUND));

                log.info("[Concert] DB Query (Cache Miss) | Success (action: retrieve_seats, scheduleId: {})", scheduleId);

                List<SeatResponse> seats = seatRepository.findAllBySchedule_Id(scheduleId).stream()
                        .map(SeatResponse::from)
                        .toList();

                SeatListResponse response = new SeatListResponse(scheduleId, seats);

                // 조회 데이터 즉시 직렬화 수행 후 Redis 캐싱 적재 (만료 정책: 1분 설정)
                String jsonValue = objectMapper.writeValueAsString(response);
                redisTemplate.opsForValue().set(cacheKey, jsonValue, Duration.ofMinutes(1));

                return response;
            } catch (JsonProcessingException e) {
                log.error("[Concert] Cache Serialization Fail | (message: {})", e.getMessage());
                throw new TurnstileException(ErrorCode.INTERNAL_SERVER_ERROR, "캐시 동기화 연산에 실패했습니다.");
            } finally {
                // 비즈니스 트랜잭션 종료 직후 원자적 락 회수 방어선 실행
                redisTemplate.delete(lockKey);
            }
        } else {
            // 후속 조회 스레드 정체 시 50ms 대기 후 대표자가 할당한 캐시 인프라 재참조
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            String reCachedData = redisTemplate.opsForValue().get(cacheKey);
            if (reCachedData != null) {
                try {
                    return objectMapper.readValue(reCachedData, SeatListResponse.class);
                } catch (JsonProcessingException ignored) {}
            }
            // 경합 임계점 도달 시 안전하게 429 Too Many Requests 비즈니스 예외로 처리
            throw new TurnstileException(ErrorCode.TOO_MANY_REQUESTS, "조회 요청이 집중되었습니다. 잠시 후 다시 시도해주세요.");
        }
    }
}