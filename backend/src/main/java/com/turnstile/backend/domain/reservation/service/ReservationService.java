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

        // 2차 인가 검증: Redis 화이트리스트 생존 여부 확인
        if (!queueRedisRepository.isActiveUser(concertIdStr, userIdStr)) {
            throw new IllegalStateException("예매 권한이 없거나 만료되었습니다. 대기열을 다시 거쳐주세요.");
        }

        // 연석 제한 검증
        if (request.seatIds().size() > 4) {
            throw new IllegalArgumentException("최대 4석까지만 예매 가능합니다.");
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
                log.warn("좌석 선점 실패 (이미 점유됨) - userId: {}, seatId: {}", request.userId(), seatId);
                throw new IllegalStateException("이미 선점된 좌석이 포함되어 있습니다. 다시 시도해주세요.");
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

        return new ReservationResponse(
                groupId,
                ReservationStatus.PENDING.name(),
                expiresAt,
                paymentToken
        );
    }

    @Transactional(readOnly = true)
    public List<MyReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findAllByUserIdWithDetails(userId).stream()
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
    }
}