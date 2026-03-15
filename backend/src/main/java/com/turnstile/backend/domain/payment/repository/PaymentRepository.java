package com.turnstile.backend.domain.payment.repository;

import com.turnstile.backend.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 결제 이력 정보에 대한 데이터 접근 기능을 제공하는 리포지토리입니다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

}
