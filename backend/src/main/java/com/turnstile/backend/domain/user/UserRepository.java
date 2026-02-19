package com.turnstile.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // username 필드로 User 조회
    Optional<User> findByUsername(String username);
}