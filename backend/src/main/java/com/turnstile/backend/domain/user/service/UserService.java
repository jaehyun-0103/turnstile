package com.turnstile.backend.domain.user.service;

import com.turnstile.backend.domain.user.dto.UserBalanceResponse;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public Long deductBalance(Long userId, Long amount) {
        int updatedCount = userRepository.deductBalance(userId, amount);
        if (updatedCount == 0) {
            throw new IllegalArgumentException("잔액이 부족합니다.");
        }

        return userRepository.findById(userId).orElseThrow().getBalance();
    }

    @Transactional(readOnly = true)
    public Long getBalance(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다."))
                .getBalance();
    }

    @Transactional
    public Long chargeBalance(Long userId, Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다.");
        }

        int updatedCount = userRepository.chargeBalance(userId, amount);
        if (updatedCount == 0) {
            throw new IllegalArgumentException("존재하지 않는 유저입니다.");
        }

        // clearAutomatically = true 옵션으로 영속성 컨텍스트가 비워졌으므로 DB에서 최신 상태 조회
        return userRepository.findById(userId).orElseThrow().getBalance();
    }
}