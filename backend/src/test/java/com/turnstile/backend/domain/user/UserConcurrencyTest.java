package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import com.turnstile.backend.support.ConcurrentTestSupport;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다중 스레드 환경에서 유저 잔액의 원자적 충전 및 초과 차감 방어 로직을 검증하는 통합 테스트입니다.
 */
class UserConcurrencyTest extends DatabaseIntegrationTest {
    @Autowired UserService userService;

    @Test
    @DisplayName("동시에 100번의 충전 요청이 들어와도 Race Condition 없이 잔액이 정확히 누적되어야 한다")
    void oneHundredConcurrentChargesAreAllApplied() throws Exception {
        // given: 테스트 대상 유저의 초기 잔액을 0원으로 세팅
        jdbc.update("UPDATE users SET balance = 0 WHERE id = ?", userA.getId());

        // when: 100개의 스레드가 동시에 1,000원씩 충전 요청
        var results = ConcurrentTestSupport.run(100, index -> userService.chargeBalance(userA.getId(), 1000L));

        // then: 기본 잔액 0원 + (100 * 1000원) = 100,000원 검증
        assertThat(results).hasSize(100).allMatch(balance -> balance > 0);
        assertThat(userService.getBalance(userA.getId())).isEqualTo(100000L);
    }

    @Test
    @DisplayName("보유 잔액(5,000원)에 대해 동시에 10번의 1,000원 차감 요청 시, 5번만 성공하고 초과 차감을 방어해야 한다")
    void concurrentDeductionsNeverOverdrawBalance() throws Exception {
        // given: 차감 테스트를 위해 대상 유저의 잔액을 5,000원으로 세팅
        jdbc.update("UPDATE users SET balance = 5000 WHERE id = ?", userA.getId());

        // when: 10개의 스레드가 동시에 1,000원씩 차감 시도
        var results = ConcurrentTestSupport.run(10, index -> {
            try {
                userService.deductBalance(userA.getId(), 1000L);
                return true;
            } catch (TurnstileException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE); // INSUFFICIENT_BALANCE 예외 발생
                return false;
            }
        });

        // then: 정확히 5번 성공, 5번 실패(예외) 및 최종 잔액 0원 검증
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        assertThat(results.stream().filter(success -> !success).count()).isEqualTo(5);
        assertThat(userService.getBalance(userA.getId())).isZero();
    }
}