package com.turnstile.backend.domain.user.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.user.dto.UserBalanceResponse;
import com.turnstile.backend.domain.user.dto.UserChargeRequest;
import com.turnstile.backend.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "2. User", description = "사용자 정보 및 잔액 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

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
     * 특정 사용자의 계좌에 지정된 금액을 충전합니다.
     */
    @Operation(summary = "잔액 충전 (시연용)", description = "요구 권한: **LEVEL 1 (IDENTITY)** 이상\n\n특정 사용자의 계좌에 금액을 충전합니다.")
    @PatchMapping("/{userId}/balance")
    public ResponseEntity<UserBalanceResponse> chargeBalance(
            @PathVariable("userId") Long userId,
            @Valid @RequestBody UserChargeRequest request) {
        log.info("[User] API Request | Charge Balance (userId: {}, amount: {})", userId, request.amount());
        Long updatedBalance = userService.chargeBalance(userId, request.amount());
        return ResponseEntity.ok(new UserBalanceResponse(userId, updatedBalance));
    }
}