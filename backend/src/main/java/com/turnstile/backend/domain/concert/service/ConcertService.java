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
import com.turnstile.backend.global.common.SeatCacheKeys;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

    @Value("${seat-cache.ttl-seconds:60}")
    private long seatCacheTtlSeconds;

    // 락 소유자 토큰이 일치할 때만 안전하게 해제하기 위한 원자적 Lua 스크립트
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT =
            RedisScript.of("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """, Long.class);

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
        String cacheKey = SeatCacheKeys.seats(scheduleId);
        String lockKey = SeatCacheKeys.lock(scheduleId);

        // 1단계: Redis 캐시 레이어 선조회 수행
        String cachedData = redisTemplate.opsForValue().get(cacheKey);
        if (cachedData != null) {
            try {
                return objectMapper.readValue(cachedData, SeatListResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("[Concert] Cache Deserialization | Fail (scheduleId: {}, message: {})", scheduleId, e.getMessage());
            }
        }

        // 2단계: 캐시 미스 발생 시 소유자 식별 토큰 기반 분산 락(Try-Lock) 획득 시도를 통해 DB 쏠림 방어선 구축 (SETNX 제어)
        String lockToken = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                Duration.ofSeconds(3)
        );

        if (Boolean.TRUE.equals(acquired)) {
            try {
                // 대표 권한 스레드가 진입하여 단건 검증 및 DB 조회 인입
                concertScheduleRepository.findById(scheduleId)
                        .orElseThrow(
                                () -> new TurnstileException(ErrorCode.SCHEDULE_NOT_FOUND)
                        );
                log.info("[Concert] DB Query (Cache Miss) | Success (action: retrieve_seats, scheduleId: {})", scheduleId);

                List<SeatResponse> seats = seatRepository.findAllBySchedule_Id(scheduleId).stream()
                        .map(SeatResponse::from)
                        .toList();

                SeatListResponse response = new SeatListResponse(scheduleId, seats);

                // 조회 데이터 즉시 직렬화 수행 후 Redis 캐싱 적재
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(response),
                        Duration.ofSeconds(seatCacheTtlSeconds)
                );

                return response;
            } catch (JsonProcessingException e) {
                log.error("[Concert] Cache Serialization | Fail (scheduleId: {}, message: {})", scheduleId, e.getMessage());
                throw new TurnstileException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "좌석 캐시 직렬화에 실패했습니다."
                );
            } finally {
                try {
                    // 비즈니스 로직 종료 직후 본인 소유 락만 원자적으로 회수하는 방어선 실행
                    redisTemplate.execute(
                            RELEASE_LOCK_SCRIPT,
                            List.of(lockKey),
                            lockToken
                    );
                } catch (DataAccessException e) {
                    // 해제 실패 시에도 락 TTL로 회수되며 기존 결과·예외를 덮지 않음
                    log.warn("[Concert] Cache Lock Release | Fail (scheduleId: {})", scheduleId, e);
                }
            }
        }

        // 후속 조회 스레드 정체 시 50ms 대기 후 대표자가 할당한 캐시 인프라 재참조
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String reCachedData = redisTemplate.opsForValue().get(cacheKey);
        if (reCachedData != null) {
            try {
                return objectMapper.readValue(reCachedData, SeatListResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("[Concert] Retried Cache Deserialization | Fail (scheduleId: {}, message: {})", scheduleId, e.getMessage());
            }
        }

        // 경합 임계점 도달 시 안전하게 429 Too Many Requests 비즈니스 예외로 처리
        throw new TurnstileException(
                ErrorCode.TOO_MANY_REQUESTS,
                "조회 요청이 집중되었습니다. 잠시 후 다시 시도해주세요."
        );
    }
}