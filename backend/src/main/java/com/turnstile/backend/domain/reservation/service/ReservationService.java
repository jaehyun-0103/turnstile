package com.turnstile.backend.domain.reservation.service;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.MyReservationResponse;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 콘서트 좌석 임시 점유(예약) 및 내 예약 내역 조회 비즈니스 로직을 담당하는 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final QueueRedisRepository queueRedisRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final CacheEvictor cacheEvictor;
    private final JwtProvider jwtProvider;

    @Value("${reservation.policy.max-seats-per-user:4}")
    private int maxSeatsPerUser;

    @Value("${reservation.policy.hold-time-seconds:330}")
    private long holdTimeSeconds;

    @Value("${reservation.policy.payment-ttl-seconds:330}")
    private long paymentExpirySeconds;

    /**
     * 사용자가 선택한 좌석들을 임시 점유하고 결제용 접근 토큰을 발급합니다.
     * @param userId 예약 요청 사용자 식별자
     * @param request 예약 요청 정보 (콘서트 ID, 좌석 ID 목록)
     * @return 생성된 예약 그룹 ID와 점유 만료 시간, 결제 토큰
     */
    @Transactional
    public ReservationResponse reserveSeats(Long userId, ReservationRequest request) {
        String concertIdStr = String.valueOf(request.concertId());
        String userIdStr = String.valueOf(userId);

        if (!queueRedisRepository.isActiveUser(concertIdStr, userIdStr)) {
            throw new TurnstileException(ErrorCode.QUEUE_NOT_ACTIVE, "예매 권한이 없거나 만료되었습니다. (userId: " + userId + ")");
        }

        if (request.seatIds() == null || request.seatIds().isEmpty()) {
            log.warn("[Reservation] Reserve Seats | Fail - Empty seat list (userId: {}, concertId: {})", userId, request.concertId());
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "최소 1개 이상의 좌석을 선택해야 합니다.");
        }

        if (request.seatIds().size() > maxSeatsPerUser) {
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE,
                    "최대 " + maxSeatsPerUser + "석까지만 예매 가능합니다. (요청 좌석 수: " + request.seatIds().size() + ")");
        }

        // DB 데드락 방지 및 중복 요청으로 인한 자원 낭비를 막기 위해 좌석 ID 중복 제거 후 오름차순 정렬
        List<Long> distinctSeatIds = request.seatIds().stream().distinct().toList();
        List<Long> sortedSeatIds = new ArrayList<>(distinctSeatIds);
        Collections.sort(sortedSeatIds);

        List<Seat> seats = seatRepository.findAllById(sortedSeatIds);

        // 비정상적인 좌석 ID 요청 시 DB 락 획득을 시도하기 전 최전방에서 차단(Fail-Fast)하여 부하 방지
        if (seats.size() != distinctSeatIds.size()) {
            log.warn("[Reservation] Validate Seats | Fail (reason: seat not found, requestedCount: {}, foundCount: {})",
                    distinctSeatIds.size(), seats.size());
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "존재하지 않거나 유효하지 않은 좌석이 포함되어 있습니다.");
        }

        // 타 콘서트 대기열 통과 후 좌석 ID를 조작하는 우회 공격을 차단하기 위해 실제 콘서트 ID 교차 검증
        for (Seat seat : seats) {
            Long actualConcertId = seat.getSchedule().getConcert().getId();
            if (!actualConcertId.equals(request.concertId())) {
                log.warn("[Reservation] Validate Seats | Fail (reason: concert mismatch, requestedConcertId: {}, actualConcertId: {})",
                        request.concertId(), actualConcertId);
                throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "요청한 콘서트의 좌석이 아닙니다.");
            }
        }

        // 데이터 무결성 훼손 및 결제 꼬임을 방지하기 위해 하나의 예약 그룹에는 단일 회차의 좌석만 포함되도록 제한
        long uniqueScheduleCount = seats.stream()
                .map(seat -> seat.getSchedule().getId())
                .distinct()
                .count();

        if (uniqueScheduleCount > 1) {
            log.warn("[Reservation] Validate Seats | Fail (reason: cross schedule booking, uniqueScheduleCount: {})",
                    uniqueScheduleCount);
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "서로 다른 회차의 좌석을 섞어서 예매할 수 없습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(holdTimeSeconds);

        for (Long seatId : sortedSeatIds) {
            int updatedRowCount = seatRepository.holdSeatConditionally(seatId, userId, expiresAt);

            if (updatedRowCount == 0) {
                log.warn("[Reservation] Reserve Seats | Fail - Seat Already Held (userId: {}, seatId: {})", userId, seatId);
                throw new TurnstileException(ErrorCode.SEAT_ALREADY_HELD, "요청 좌석 ID: " + seatId);
            }
        }

        User userRef = userRepository.getReferenceById(userId);
        String groupId = UUID.randomUUID().toString();



        List<Reservation> reservations = seats.stream()
                .map(seat -> Reservation.builder()
                        .groupId(groupId)
                        .user(userRef)
                        .seat(seat)
                        .status(ReservationStatus.PENDING)
                        .price(seat.getPrice())
                        .reservedAt(now)
                        .build())
                .toList();

        reservationRepository.saveAll(reservations);

        // 캐시 즉시 무효화 (팬텀 클릭 차단)
        Long scheduleId = seats.get(0).getSchedule().getId();
        cacheEvictor.evictSeatCache(scheduleId);

        long paymentExpiryMillis = paymentExpirySeconds * 1000L;
        Date paymentExpiryDate = new Date(System.currentTimeMillis() + paymentExpiryMillis);
        String paymentToken = jwtProvider.generateToken(userId, TokenType.PAYMENT, paymentExpiryDate);

        log.info("[Reservation] Reserve Seats | Success (userId: {}, groupId: {}, seatCount: {})", userId, groupId, sortedSeatIds.size());

        return new ReservationResponse(
                groupId,
                ReservationStatus.PENDING.name(),
                expiresAt,
                paymentToken
        );
    }

    /**
     * 특정 사용자의 모든 예약 내역을 최신순으로 조회합니다.
     */
    @Transactional(readOnly = true)
    public List<MyReservationResponse> getMyReservations(Long userId) {
        List<MyReservationResponse> responses = reservationRepository.findAllByUserIdWithDetails(userId).stream()
                .map(r -> new MyReservationResponse(
                        r.getId(),
                        r.getGroupId(),
                        r.getSeat().getSchedule().getConcert().getTitle(),
                        r.getSeat().getSchedule().getConcertDate(),
                        r.getSeat().getSeatNo(),
                        r.getStatus(),
                        r.getPrice(),
                        r.getReservedAt()
                ))
                .toList();

        log.info("[Reservation] Get My Reservations | Success (userId: {}, count: {})", userId, responses.size());
        return responses;
    }
}