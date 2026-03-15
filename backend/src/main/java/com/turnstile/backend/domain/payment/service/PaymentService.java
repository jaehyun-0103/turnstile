package com.turnstile.backend.domain.payment.service;

import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.entity.Payment;
import com.turnstile.backend.domain.payment.entity.PaymentStatus;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final UserService userService;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final QueueRedisRepository queueRedisRepository;
    private final CacheEvictor cacheEvictor;

    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        // 예약 정보 조회
        List<Reservation> reservations = reservationRepository.findAllByGroupId(request.groupId());
        if (reservations.isEmpty()) {
            throw new TurnstileException(ErrorCode.RESERVATION_NOT_FOUND, "요청 그룹 ID: " + request.groupId());
        }

        // Lazy Loading 에러를 막기 위해 필요한 ID 값 미리 추출
        Long concertId = reservations.get(0).getSeat().getSchedule().getConcert().getId();
        Long scheduleId = reservations.get(0).getSeat().getSchedule().getId();

        // Redis 2차 인가 검증
        if (!queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(request.userId()))) {
            throw new TurnstileException(ErrorCode.QUEUE_NOT_ACTIVE, "요청 유저 ID: " + request.userId());
        }

        // 결제 금액 위변조 검증
        long totalAmount = reservations.stream().mapToLong(Reservation::getPrice).sum();
        if (totalAmount != request.amount()) {
            throw new TurnstileException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "실제 금액: " + totalAmount + ", 요청 금액: " + request.amount());
        }

        // PENDING 조건절(CAS)을 활용한 멱등성 확보 및 이중 결제 방어
        int updatedReservationCount = reservationRepository.confirmReservations(request.groupId());
        if (updatedReservationCount == 0) {
            log.warn("중복 결제 방어 작동 - groupId: {}", request.groupId());
            throw new TurnstileException(ErrorCode.ALREADY_PAID, "요청 그룹 ID: " + request.groupId());
        }

        // 유저 잔액 원자적 차감 (이때 Persistence Context가 1차로 비워짐)
        Long remainingBalance = userService.deductBalance(request.userId(), totalAmount);

        // 데이터 상태 확정 (이때 Persistence Context가 2, 3차로 비워짐)
        seatRepository.confirmSeats(request.groupId());

        // 결제 이력 생성
        Payment payment = paymentRepository.save(Payment.builder()
                .groupId(request.groupId())
                .amount(totalAmount)
                .status(PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .build());

        // 캐시 무효화
        cacheEvictor.evictSeatCache(scheduleId);

        return new PaymentResponse(
                payment.getId(),
                payment.getStatus().name(),
                payment.getPaidAt(),
                remainingBalance
        );
    }
}