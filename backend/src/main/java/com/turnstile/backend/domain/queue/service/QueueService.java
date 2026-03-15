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

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueRedisRepository queueRedisRepository;
    private final ConcertRepository concertRepository;
    private final JwtProvider jwtProvider;

    // 스케줄러가 참조할 활성화된 콘서트 목록 키
    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    // 대기열 진입
    public QueueEntryResponse enterQueue(QueueEntryRequest request) {
        String concertIdStr = String.valueOf(request.concertId());
        String userIdStr = String.valueOf(request.userId());

        // 오픈 시간 검증 및 인프라 보호
        Concert concert = concertRepository.findById(request.concertId())
                .orElseThrow(() -> new TurnstileException(ErrorCode.CONCERT_NOT_FOUND, "요청 콘서트 ID: " + request.concertId()));

        if (LocalDateTime.now().isBefore(concert.getTicketingStartAt())) {
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "아직 예매가 오픈되지 않았습니다. (오픈 일시: " + concert.getTicketingStartAt() + ")");
        }

        Long result = queueRedisRepository.enterQueue(concertIdStr, userIdStr);
        if (result == -1L) { // 이미 진입 완료 상태인지 확인
            log.info("[Queue] Enter Queue | Success - Already Active (userId: {}, concertId: {})", request.userId(), request.concertId());
            return new QueueEntryResponse(request.concertId(), 0L, "ACTIVE");
        }

        // 내 앞의 대기자 수 계산
        Long rank = queueRedisRepository.getWaitingRank(concertIdStr, userIdStr);
        if (rank == null) {
            rank = 0L;
        }

        log.info("[Queue] Enter Queue | Success - Waiting (userId: {}, concertId: {}, rank: {})", request.userId(), request.concertId(), rank);
        return new QueueEntryResponse(request.concertId(), rank, "WAITING");
    }

    // 대기 상태 폴링
    public QueueStatusResponse getQueueStatus(Long concertId, Long userId) {
        String concertIdStr = String.valueOf(concertId);
        String userIdStr = String.valueOf(userId);

        // Redis에서 유저의 만료 시간(Score)을 직접 조회
        Double expireTimestamp = queueRedisRepository.getActiveUserScore(concertIdStr, userIdStr);

        // 진입 완료 상태 확인
        if (expireTimestamp != null) {
            // Redis에 기록된 정확한 만료 시점으로 토큰 발급 (재발급 시에도 만료 시각 고정)
            Date expiryDate = new Date(expireTimestamp.longValue() * 1000);
            String accessToken = jwtProvider.generateToken(userId, expiryDate);

            // 토큰이 발급되는 활성화 시점은 중요하므로 INFO 레벨 유지
            log.info("[Queue] Get Status | Token Issued - Active (userId: {}, concertId: {})", userId, concertId);
            return new QueueStatusResponse(concertId, 0L, "ACTIVE", accessToken);
        }

        // 대기열 상태 확인
        Long rank = queueRedisRepository.getWaitingRank(concertIdStr, userIdStr);
        if (rank != null) {
            // 폴링 응답은 DEBUG 레벨 유지
            log.debug("[Queue] Get Status | Polling - Waiting (userId: {}, concertId: {}, rank: {})", userId, concertId, rank);
            return new QueueStatusResponse(concertId, rank, "WAITING", null);
        }

        // 튕기거나 만료된 비정상 상태는 INFO 레벨로 추적
        log.info("[Queue] Get Status | Fail - Invalid/Expired (userId: {}, concertId: {})", userId, concertId);
        return new QueueStatusResponse(concertId, -1L, "INVALID", null);
    }
}