package com.turnstile.backend.domain.user.service;

import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public Long deductBalance(Long userId, Long amount) {
        int updatedCount = userRepository.deductBalance(userId, amount);
        if (updatedCount == 0) {
            throw new TurnstileException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        log.info("[User] Deduct Balance | Success (userId: {}, amount: {})", userId, amount);

        return userRepository.findById(userId)
                .orElseThrow(() -> new TurnstileException(ErrorCode.USER_NOT_FOUND))
                .getBalance();
    }

    @Transactional(readOnly = true)
    public Long getBalance(Long userId) {
        Long balance = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다."))
                .getBalance();

        log.info("[User] Get Balance | Success (userId: {})", userId);
        return balance;
    }

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