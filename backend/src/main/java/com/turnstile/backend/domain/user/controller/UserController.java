package com.turnstile.backend.domain.user.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.user.dto.UserBalanceResponse;
import com.turnstile.backend.domain.user.dto.UserChargeRequest;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Tag(name = "2. User", description = "사용자 정보 및 잔액 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Value("${demo.balance-charge-enabled:false}")
    private boolean balanceChargeEnabled; // 시연용 잔액 충전 활성화 여부

    /**
     * 현재 로그인한 사용자의 잔액을 조회합니다.
     */
    @Operation(summary = "내 잔액 조회", description = "요구 권한: **LEVEL 1 (IDENTITY)** 이상\n\n현재 로그인한 사용자의 잔액을 조회합니다.")
    @GetMapping("/me/balance")
    public ResponseEntity<UserBalanceResponse> getMyBalance(@LoginUser Long userId) {
        log.info("[User] API Request | Get Balance (userId: {})", userId);
        Long balance = userService.getBalance(userId);
        return ResponseEntity.ok(new UserBalanceResponse(userId, balance));
    }

    /**
     * 현재 로그인한 사용자의 계좌에 지정된 금액을 충전합니다. (시연용 설정이 활성화된 환경에서만 허용)
     */
    @Operation(
            summary = "내 잔액 충전 (시연용)",
            description = "요구 권한: **LEVEL 1 (IDENTITY)** 이상\n\n"
                    + "시연용 충전이 활성화된 환경에서 로그인한 본인의 잔액만 충전합니다."
    )
    @PatchMapping("/me/balance")
    public ResponseEntity<UserBalanceResponse> chargeBalance(
            @LoginUser Long loginUserId,
            @Valid @RequestBody UserChargeRequest request) {
        if (!balanceChargeEnabled) {
            log.warn("[User] API Request | Charge Balance Denied - Feature is disabled (userId: {}, code: {})", loginUserId, ErrorCode.ACCESS_DENIED.getCode());
            throw new TurnstileException(
                    ErrorCode.ACCESS_DENIED,
                    "시연용 잔액 충전이 비활성화되어 있습니다."
            );
        }

        log.info("[User] API Request | Charge Balance (userId: {}, amount: {})", loginUserId, request.amount());

        Long updatedBalance =
                userService.chargeBalance(loginUserId, request.amount());

        return ResponseEntity.ok(
                new UserBalanceResponse(loginUserId, updatedBalance)
        );
    }
}