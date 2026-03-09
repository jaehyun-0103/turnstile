package com.turnstile.backend.domain.concert.repository;

import com.turnstile.backend.domain.concert.entity.Concert;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConcertRepository extends JpaRepository<Concert, Long> {
    @Override
    @Cacheable(
            cacheNames = "concerts:cache",
            key = "#id",
            unless = "#result == null"
    )
    Optional<Concert> findById(Long id);
}
