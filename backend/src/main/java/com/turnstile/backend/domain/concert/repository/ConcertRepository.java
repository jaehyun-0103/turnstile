package com.turnstile.backend.domain.concert.repository;

import com.turnstile.backend.domain.concert.entity.Concert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertRepository extends JpaRepository<Concert, Long> {

}
