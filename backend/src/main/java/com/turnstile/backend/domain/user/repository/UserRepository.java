package com.turnstile.backend.domain.user.repository;

import com.turnstile.backend.domain.user.entity.User;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * 사용자 엔티티의 데이터 접근 및 잔액 변경을 처리하는 리포지토리입니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    // DB 락(Pessimistic Lock) 대기 시간 없이 쿼리 조건(balance >= amount)만으로 초과 차감을 방어함
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance - :amount " +
            "WHERE u.id = :userId AND u.balance >= :amount")
    int deductBalance(@Param("userId") Long userId, @Param("amount") Long amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance + :amount WHERE u.id = :userId")
    int chargeBalance(@Param("userId") Long userId, @Param("amount") Long amount);
}