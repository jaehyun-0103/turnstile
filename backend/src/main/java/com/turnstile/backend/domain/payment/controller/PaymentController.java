package com.turnstile.backend.domain.payment.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 좌석 예약을 최종 결제 처리하기 위한 엔드포인트를 제공하는 컨트롤러입니다.
 */
@Slf4j
@Tag(name = "6. Payment", description = "좌석 예약 최종 결제 처리 API")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 임시 점유 중인 예약 건에 대해 결제를 수행합니다.
     */
    @Operation(summary = "결제 수행 (예약 확정)", description = "요구 권한: **LEVEL 3 (PAYMENT) 전용**\n\n발급받은 결제 전용 토큰을 사용하여 잔액을 차감하고 좌석 상태를 판매 완료(SOLD)로 최종 확정합니다.")
    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @LoginUser Long loginUserId,
            @Valid @RequestBody PaymentRequest request) {
        log.info("[Payment] API Request | Process Payment (userId: {}, groupId: {}, requestAmount: {})", loginUserId, request.groupId(), request.amount());

        PaymentResponse response = paymentService.processPayment(loginUserId, request);
        return ResponseEntity.ok(response);
    }
}