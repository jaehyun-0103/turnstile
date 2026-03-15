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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Date;

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
            return new QueueEntryResponse(request.concertId(), 0L, "ACTIVE");
        }

        // 내 앞의 대기자 수 계산
        Long rank = queueRedisRepository.getWaitingRank(concertIdStr, userIdStr);
        if (rank == null) {
            rank = 0L;
        }

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
            return new QueueStatusResponse(concertId, 0L, "ACTIVE", accessToken);
        }

        // 대기열 상태 확인
        Long rank = queueRedisRepository.getWaitingRank(concertIdStr, userIdStr);
        if (rank != null) {
            return new QueueStatusResponse(concertId, rank, "WAITING", null);
        }

        // 5분이 지나 만료 또는 튕긴 상태
        return new QueueStatusResponse(concertId, -1L, "INVALID", null);
    }
}