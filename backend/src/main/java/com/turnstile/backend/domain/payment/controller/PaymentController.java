package com.turnstile.backend.domain.payment.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // 결제 API
    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @LoginUser Long loginUserId,
            @RequestBody PaymentRequest request) {

        // 위변조 방지를 위해 토큰에서 추출한 로그인 유저 ID를 강제 주입
        PaymentRequest secureRequest = new PaymentRequest(
                loginUserId,
                request.groupId(),
                request.amount()
        );

        PaymentResponse response = paymentService.processPayment(secureRequest);
        return ResponseEntity.ok(response);
    }
}