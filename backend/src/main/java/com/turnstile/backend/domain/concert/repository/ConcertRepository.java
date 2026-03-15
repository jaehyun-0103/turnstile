package com.turnstile.backend.domain.concert.repository;

import com.turnstile.backend.domain.concert.entity.Concert;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 콘서트 엔티티의 데이터 접근을 처리하는 리포지토리입니다.
 */
public interface ConcertRepository extends JpaRepository<Concert, Long> {

    /**
     * 콘서트 단건 조회 시 캐시를 우선적으로 확인합니다.
     */
    @Override
    @Cacheable(
            cacheNames = "concerts:cache",
            key = "#id",
            unless = "#result == null"
    )
    Optional<Concert> findById(Long id);
}