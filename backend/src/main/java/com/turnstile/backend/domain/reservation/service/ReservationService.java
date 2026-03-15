package com.turnstile.backend.domain.reservation.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

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

    @Transactional
    public ReservationResponse reserveSeats(ReservationRequest request) {
        String concertIdStr = String.valueOf(request.concertId());
        String userIdStr = String.valueOf(request.userId());

        if (!queueRedisRepository.isActiveUser(concertIdStr, userIdStr)) {
            throw new TurnstileException(ErrorCode.QUEUE_NOT_ACTIVE, "예매 권한이 없거나 만료되었습니다. (userId: " + request.userId() + ")");
        }

        if (request.seatIds().size() > 4) {
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "최대 4석까지만 예매 가능합니다. (요청 좌석 수: " + request.seatIds().size() + ")");
        }

        // seatIds 오름차순 정렬 (데드락 예방)
        List<Long> sortedSeatIds = new ArrayList<>(request.seatIds());
        Collections.sort(sortedSeatIds);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(5); // 5분 임시 점유

        // CAS 업데이트 및 All-or-Nothing 롤백 검증
        for (Long seatId : sortedSeatIds) {
            int updatedRowCount = seatRepository.holdSeatConditionally(seatId, request.userId(), expiresAt);

            if (updatedRowCount == 0) {
                log.warn("[Reservation] Reserve Seats | Fail - Seat Already Held (userId: {}, seatId: {})", request.userId(), seatId);
                throw new TurnstileException(ErrorCode.SEAT_ALREADY_HELD, "요청 좌석 ID: " + seatId);
            }
        }

        // 예약 레코드 생성 (PENDING)
        User userRef = userRepository.getReferenceById(request.userId());
        String groupId = UUID.randomUUID().toString();

        List<Seat> seats = seatRepository.findAllById(sortedSeatIds);
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
        Long scheduleId = seats.get(0).getSchedule().getId(); // Seat 엔티티 매핑 반영
        cacheEvictor.evictSeatCache(scheduleId);


        long paymentExpiryMillis = 330 * 1000L;
        Date paymentExpiryDate = new Date(System.currentTimeMillis() + paymentExpiryMillis);

        // 결제용 JWT 발급 (5분 30초 TTL)
        String paymentToken = jwtProvider.generateToken(request.userId(), paymentExpiryDate);

        log.info("[Reservation] Reserve Seats | Success (userId: {}, groupId: {}, seatCount: {})", request.userId(), groupId, sortedSeatIds.size());

        return new ReservationResponse(
                groupId,
                ReservationStatus.PENDING.name(),
                expiresAt,
                paymentToken
        );
    }

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