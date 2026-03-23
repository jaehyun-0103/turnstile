package com.turnstile.backend.domain.payment.service;

import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.entity.Payment;
import com.turnstile.backend.domain.payment.entity.PaymentStatus;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.queue.event.UserTicketingCompletedEvent;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.event.SeatCacheEvictEvent;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약된 좌석에 대한 최종 결제 처리를 담당하는 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final UserService userService;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 임시 예약된 좌석들의 결제를 진행하고, 유저 잔액 차감 및 좌석 상태를 확정합니다.
     * @param userId 결제 요청 사용자 식별자
     * @param request 결제 요청 정보
     * @return 결제 완료 응답 정보
     */
    @Transactional
    public PaymentResponse processPayment(Long userId, PaymentRequest request) {
        // 예약 정보 존재 여부 확인
        List<Reservation> reservations = reservationRepository.findAllByGroupIdWithSeat(request.groupId());
        if (reservations.isEmpty()) {
            throw new TurnstileException(ErrorCode.RESERVATION_NOT_FOUND, "요청 그룹 ID: " + request.groupId());
        }

        // 결제 요청자와 예약 소유자가 일치하는지 검증하여 타인의 예약 탈취 결제를 차단
        Long reservationOwnerId = reservations.get(0).getUser().getId();
        if (!reservationOwnerId.equals(userId)) {
            log.warn("[Payment] Ownership Validation | Fail - Unauthorized access (requestUserId: {}, ownerUserId: {})", userId, reservationOwnerId);
            throw new TurnstileException(ErrorCode.FORBIDDEN_USER, "요청 유저 ID: " + userId);
        }

        // 타 콘서트 대기열 우회 결제 및 엉뚱한 활성 유저 퇴출 테러를 방지하기 위한 콘서트 ID 교차 검증
        Long actualConcertId = reservations.get(0).getSeat().getSchedule().getConcert().getId();
        if (!actualConcertId.equals(request.concertId())) {
            log.warn("[Payment] Validate Concert | Fail (reason: concert mismatch, requestedConcertId: {}, actualConcertId: {})",
                    request.concertId(), actualConcertId);
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "결제 대상 콘서트 정보가 일치하지 않습니다.");
        }

        // 결제 금액 위변조 여부 검증
        long totalAmount = reservations.stream().mapToLong(Reservation::getPrice).sum();
        if (totalAmount != request.amount()) {
            throw new TurnstileException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "실제 금액: " + totalAmount + ", 요청 금액: " + request.amount());
        }
        log.info("[Payment] Validation | Success - Amount Match (groupId: {}, totalAmount: {})", request.groupId(), totalAmount);

        // PENDING 상태 확인 및 PAID 전이 (멱등성 보장 및 중복 결제 차단)
        int updatedReservationCount = reservationRepository.confirmReservations(request.groupId());
        if (updatedReservationCount == 0) {
            log.warn("[Payment] Validation | Fail - Already Paid or Expired (groupId: {})", request.groupId());
            throw new TurnstileException(ErrorCode.ALREADY_PAID, "요청 그룹 ID: " + request.groupId());
        }

        // 유저 잔액 차감 및 좌석 상태 확정 (HOLD -> SOLD)
        Long remainingBalance = userService.deductBalance(userId, totalAmount);
        seatRepository.confirmSeats(request.groupId());

        // 결제 이력 생성 및 영속화
        Payment payment = paymentRepository.save(Payment.builder()
                .groupId(request.groupId())
                .amount(totalAmount)
                .status(PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .build());

        Long scheduleId = reservations.get(0).getSeat().getSchedule().getId();
        // 트랜잭션 롤백 시 DB와 Redis 간의 생명주기 불일치(Race Condition)를 차단하기 위해 이벤트 발행으로 결합도 완화
        eventPublisher.publishEvent(new SeatCacheEvictEvent(scheduleId));
        // 결제 확정 즉시 활성열에서 유저를 퇴출하기 위한 이벤트 발행 (좀비 슬롯 방어)
        eventPublisher.publishEvent(new UserTicketingCompletedEvent(request.concertId(), userId));

        log.info("[Payment] Process Payment | Success (userId: {}, paymentId: {}, amount: {})", userId, payment.getId(), totalAmount);

        return new PaymentResponse(
                payment.getId(),
                payment.getStatus().name(),
                payment.getPaidAt(),
                remainingBalance
        );
    }
}