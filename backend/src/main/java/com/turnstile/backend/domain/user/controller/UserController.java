package com.turnstile.backend.domain.user.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.user.dto.UserBalanceResponse;
import com.turnstile.backend.domain.user.dto.UserChargeRequest;
import com.turnstile.backend.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // 내 잔액 조회
    @GetMapping("/me/balance")
    public ResponseEntity<UserBalanceResponse> getMyBalance(@LoginUser Long userId) {
        Long balance = userService.getBalance(userId);
        return ResponseEntity.ok(new UserBalanceResponse(userId, balance));
    }

    // 잔액 충전
    @PatchMapping("/{userId}/balance")
    public ResponseEntity<UserBalanceResponse> chargeBalance(
            @PathVariable("userId") Long userId,
            @RequestBody UserChargeRequest request) {

        Long updatedBalance = userService.chargeBalance(userId, request.amount());
        return ResponseEntity.ok(new UserBalanceResponse(userId, updatedBalance));
    }
}