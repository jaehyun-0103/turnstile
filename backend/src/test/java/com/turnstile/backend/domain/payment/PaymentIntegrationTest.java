package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User testUser;

    @BeforeEach
    void setUp() {
        // 테스트 간 데이터 오염 방지 및 독립성 보장을 위해 DB 초기화 진행
        transactionTemplate.execute(status -> {
            userRepository.deleteAllInBatch();

            // given: 초기 잔액이 100,000원인 테스트 유저 세팅
            testUser = userRepository.save(User.builder()
                    .username("payment_tester")
                    .name("test")
                    .balance(100000L)
                    .build());
            return null;
        });
    }

    @Test
    @DisplayName("원자적 잔액 차감: 잔액이 충분하거나 딱 맞을 경우 정상 차감")
    void deductBalanceSuccessTest() {
        // given: 100,000원의 잔액을 가진 테스트 유저 환경 세팅
        Long deductAmount = 100000L;

        // when: 유저의 보유 잔액과 딱 맞는 값 차감 시도
        userService.deductBalance(testUser.getId(), deductAmount);

        // then: 잔액이 0원인지 검증
        User resultUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(resultUser.getBalance()).isEqualTo(0L);
    }

    @Test
    @DisplayName("원자적 잔액 차감: 잔액이 부족할 경우 예외가 발생하고 잔액은 유지")
    void deductBalanceFailTest() {
        // given: 100,000원의 잔액을 가진 테스트 유저 환경 세팅
        Long deductAmount = 100001L;

        // when & then: 검증
        // 보유 잔액보다 큰 금액(100,001원) 차감 시도
        assertThatThrownBy(() -> userService.deductBalance(testUser.getId(), deductAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잔액이 부족합니다.");

        // 예외 발생 후 트랜잭션 롤백이 정상적으로 동작하여 기존 잔액(100,000원)이 깎이지 않고 유지되었는지 확인
        User resultUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(resultUser.getBalance()).isEqualTo(100000L);
    }
}