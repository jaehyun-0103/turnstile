package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다중 스레드 환경에서 유저 잔액의 원자적 충전 및 초과 차감 방어 로직을 검증하는 통합 테스트입니다.
 */
@SpringBootTest
class UserConcurrencyTest {

    private User savedUser;

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        savedUser = userRepository.save(User.builder()
                .username("test_concurrency" + System.nanoTime())
                .name("test")
                .balance(5000L) // 기본 잔액 5,000원 세팅
                .build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(savedUser.getId());
    }

    @Test
    @DisplayName("동시에 100번의 충전 요청이 들어와도 Race Condition 없이 잔액이 정확히 누적되어야 한다")
    void should_accumulate_balance_accurately_under_concurrent_charges() throws InterruptedException {
        // given: 100번의 1,000원 충전 요청 세팅
        int threadCount = 100;
        long chargeAmount = 1000L;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when: 스레드 풀을 활용하여 동시 충전
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    userService.chargeBalance(savedUser.getId(), chargeAmount);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // then: 기본 잔액 5000원 + (100 * 1000원) = 105,000원 검증
        User updatedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertThat(updatedUser.getBalance()).isEqualTo(5000L + (threadCount * chargeAmount));
    }

    @Test
    @DisplayName("보유 잔액(5,000원)에 대해 동시에 10번의 1,000원 차감 요청 시, 5번만 성공하고 초과 차감을 방어해야 한다")
    void should_prevent_negative_balance_under_concurrent_deductions() throws InterruptedException {
        // given: 10번의 1,000원 차감 요청 세팅
        int threadCount = 10;
        long deductAmount = 1000L;
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when: 10개의 스레드가 동시에 차감 시도
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    userService.deductBalance(savedUser.getId(), deductAmount);
                    successCount.incrementAndGet();
                } catch (TurnstileException e) {
                    failCount.incrementAndGet(); // INSUFFICIENT_BALANCE 예외 발생
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // then: 정확히 5번 성공, 5번 실패(예외) 및 최종 잔액 0원 검증
        User updatedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failCount.get()).isEqualTo(5);
        assertThat(updatedUser.getBalance()).isZero();
    }
}