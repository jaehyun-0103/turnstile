package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.payment.entity.Payment;
import com.turnstile.backend.domain.payment.entity.PaymentStatus;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.support.RepositoryIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 이력 엔티티의 JPA 영속성 저장을 검증하는 데이터 계층 테스트입니다.
 */
class PaymentRepositoryTest extends RepositoryIntegrationTest {

    @Autowired private PaymentRepository paymentRepository;

    @Test
    @DisplayName("결제 이력 저장 시 식별자가 생성되고 기록 시간이 자동 저장되어야 한다")
    void should_persist_payment_history_correctly() {
        // given: 결제 엔티티 생성
        Payment payment = Payment.builder()
                .groupId("group-1")
                .amount(150000L)
                .status(PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .build();

        // when: DB 저장
        Payment saved = paymentRepository.save(payment);

        // then: 식별자 및 생성일자 검증
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}