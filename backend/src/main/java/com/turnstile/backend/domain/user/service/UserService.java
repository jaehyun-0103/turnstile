package com.turnstile.backend.domain.user.service;

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
}