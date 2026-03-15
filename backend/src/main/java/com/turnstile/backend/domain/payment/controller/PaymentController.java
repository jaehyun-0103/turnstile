package com.turnstile.backend.domain.payment.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.service.PaymentService;
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
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 임시 점유 중인 예약 건에 대해 결제를 수행합니다.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @LoginUser Long loginUserId,
            @RequestBody PaymentRequest request) {
        log.info("[Payment] API Request | Process Payment (userId: {}, groupId: {}, requestAmount: {})", loginUserId, request.groupId(), request.amount());

        // 위변조 방지를 위해 토큰 기반의 로그인 유저 ID를 강제 주입하여 요청 객체 재생성
        PaymentRequest secureRequest = new PaymentRequest(
                loginUserId,
                request.groupId(),
                request.amount()
        );

        PaymentResponse response = paymentService.processPayment(secureRequest);
        return ResponseEntity.ok(response);
    }
}