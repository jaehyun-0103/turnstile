package com.turnstile.backend.domain.user.repository;

import com.turnstile.backend.domain.user.entity.User;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // username 필드로 User 조회
    Optional<User> findByUsername(String username);

    // DB 레벨 원자적 잔액 차감 (초과 차감 원천 방지)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance - :amount " +
            "WHERE u.id = :userId AND u.balance >= :amount")
    int deductBalance(@Param("userId") Long userId, @Param("amount") Long amount);
}