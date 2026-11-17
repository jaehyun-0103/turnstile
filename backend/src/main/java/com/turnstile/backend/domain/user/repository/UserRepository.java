package com.turnstile.backend.domain.user.repository;

import com.turnstile.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * 사용자 엔티티의 데이터 접근 및 잔액 변경을 처리하는 리포지토리입니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    // 잔액 조건을 포함한 원자적 UPDATE로 초과 차감을 방어함
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance - :amount " +
            "WHERE u.id = :userId AND u.balance >= :amount")
    int deductBalance(@Param("userId") Long userId, @Param("amount") Long amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.balance = u.balance + :amount WHERE u.id = :userId")
    int chargeBalance(@Param("userId") Long userId, @Param("amount") Long amount);
}