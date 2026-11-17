package com.turnstile.backend.domain.user.service;

import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정보 조회 및 잔액 차감/충전 비즈니스 로직을 담당하는 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * 사용자의 잔액을 차감합니다.
     * 잔액이 부족할 경우 예외를 발생시킵니다.
     * @param userId 사용자 식별자
     * @param amount 차감할 금액
     * @return 차감 후 최신 잔액
     */
    @Transactional
    public Long deductBalance(Long userId, Long amount) {
        userRepository.findById(userId).orElseThrow(() -> new TurnstileException(ErrorCode.USER_NOT_FOUND));

        int updatedCount = userRepository.deductBalance(userId, amount);
        if (updatedCount == 0) {
            throw new TurnstileException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // clearAutomatically = true 옵션으로 영속성 컨텍스트가 비워졌으므로 DB에서 최신 상태 조회
        Long remainingBalance = userRepository.findById(userId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.USER_NOT_FOUND))
                .getBalance();
        log.info("[User] Deduct Balance | Success (userId: {}, amount: {})", userId, amount);

        return remainingBalance;
    }

    /**
     * 사용자의 현재 잔액을 조회합니다.
     * @param userId 사용자 식별자
     * @return 현재 잔액
     */
    @Transactional(readOnly = true)
    public Long getBalance(Long userId) {
        Long balance = userRepository.findById(userId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.USER_NOT_FOUND, "요청 유저 ID: " + userId))
                .getBalance();

        log.info("[User] Get Balance | Success (userId: {})", userId);
        return balance;
    }

    /**
     * 사용자의 계좌에 금액을 충전합니다.
     * @param userId 사용자 식별자
     * @param amount 충전할 금액
     * @return 충전 후 최신 잔액
     */
    @Transactional
    public Long chargeBalance(Long userId, Long amount) {
        if (amount <= 0) {
            throw new TurnstileException(ErrorCode.INVALID_INPUT_VALUE, "충전 금액은 0보다 커야 합니다.");
        }

        int updatedCount = userRepository.chargeBalance(userId, amount);
        if (updatedCount == 0) {
            throw new TurnstileException(ErrorCode.USER_NOT_FOUND);
        }

        log.info("[User] Charge Balance | Success (userId: {}, amount: {})", userId, amount);

        // clearAutomatically = true 옵션으로 영속성 컨텍스트가 비워졌으므로 DB에서 최신 상태 조회
        return userRepository.findById(userId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.USER_NOT_FOUND))
                .getBalance();
    }
}