package com.turnstile.backend.domain.queue.service;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 콘서트 대기열 진입 및 상태 폴링 비즈니스 로직을 처리하는 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueRedisRepository queueRedisRepository;
    private final ConcertRepository concertRepository;
    private final JwtProvider jwtProvider;

    /**
     * 사용자를 대기열에 진입시킵니다. 예매 오픈 전이거나 콘서트가 없을 경우 예외를 발생시킵니다.
     * @param userId 대기열에 진입할 사용자 식별자
     * @param request 대기열 진입 요청 정보
     * @return 대기열 상태 및 현재 순위
     */
    public QueueEntryResponse enterQueue(Long userId, QueueEntryRequest request) {
        String concertIdStr = String.valueOf(request.concertId());
        String userIdStr = String.valueOf(userId);

        Concert concert = concertRepository.findById(request.concertId())
                .orElseThrow(() -> new TurnstileException(ErrorCode.CONCERT_NOT_FOUND, "요청 콘서트 ID: " + request.concertId()));

        if (LocalDateTime.now().isBefore(concert.getTicketingStartAt())) {
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "아직 예매가 오픈되지 않았습니다. (오픈 일시: " + concert.getTicketingStartAt() + ")");
        }

        Long result = queueRedisRepository.enterQueue(concertIdStr, userIdStr);
        if (result == -1L) {
            log.info("[Queue] Enter Queue | Success - Already Active (userId: {}, concertId: {})", userId, request.concertId());
            return new QueueEntryResponse(request.concertId(), 0L, "ACTIVE");
        }

        // 내 앞의 대기자 수 계산
        Long rank = queueRedisRepository.getWaitingRank(concertIdStr, userIdStr);
        if (rank == null) {
            rank = 0L;
        }

        log.info("[Queue] Enter Queue | Success - Waiting (userId: {}, concertId: {}, rank: {})", userId, request.concertId(), rank);
        return new QueueEntryResponse(request.concertId(), rank, "WAITING");
    }

    /**
     * 사용자의 현재 대기 상태를 조회하거나, 입장 가능 시 접근 토큰을 발급합니다.
     * @param concertId 콘서트 식별자
     * @param userId 사용자 식별자
     * @return 현재 순위 또는 발급된 토큰 정보
     */
    public QueueStatusResponse getQueueStatus(Long concertId, Long userId) {
        String concertIdStr = String.valueOf(concertId);
        String userIdStr = String.valueOf(userId);

        // Redis에서 유저의 만료 시간(Score)을 직접 조회
        Double expireTimestamp = queueRedisRepository.getActiveUserScore(concertIdStr, userIdStr);

        // 진입 완료 상태 확인
        if (expireTimestamp != null) {
            Date expiryDate = new Date(expireTimestamp.longValue() * 1000);
            String accessToken = jwtProvider.generateToken(userId, expiryDate);

            log.info("[Queue] Get Status | Token Issued - Active (userId: {}, concertId: {})", userId, concertId);
            return new QueueStatusResponse(concertId, 0L, "ACTIVE", accessToken);
        }

        // 대기열 상태 확인
        Long rank = queueRedisRepository.getWaitingRank(concertIdStr, userIdStr);
        if (rank != null) {
            log.debug("[Queue] Get Status | Polling - Waiting (userId: {}, concertId: {}, rank: {})", userId, concertId, rank);
            return new QueueStatusResponse(concertId, rank, "WAITING", null);
        }

        log.info("[Queue] Get Status | Fail - Invalid/Expired (userId: {}, concertId: {})", userId, concertId);
        return new QueueStatusResponse(concertId, -1L, "INVALID", null);
    }
}