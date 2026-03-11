package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserServiceConcurrencyTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    private User savedUser;

    @BeforeEach
    void setUp() {
        // 테스트 독립성 보장 및 초기 환경 세팅
        User user = User.builder()
                .username("test_user_concurrency")
                .name("test")
                .balance(0L)
                .build();
        savedUser = userRepository.save(user);
    }

    @AfterEach
    void tearDown() {
        // 다음 테스트에 영향을 주지 않도록 테스트 종료 시 데이터베이스 초기화
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동시성 제어: 동시에 100번의 충전 요청이 들어와도 Race Condition을 방어하고 잔액이 정확히 누적")
    void chargeBalance_Concurrency() throws InterruptedException {
        // given: 총 100번의 충전 요청(1회당 1,000원)과 32개의 워커를 가진 스레드 풀 세팅
        int threadCount = 100;
        long chargeAmount = 1000L;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when: 스레드 풀을 활용하여 동시에 100번의 잔액 충전
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    userService.chargeBalance(savedUser.getId(), chargeAmount);
                } finally {
                    latch.countDown(); // 각 작업이 끝날 때마다 Latch 감소
                }
            });
        }

        // 모든 스레드의 충전 작업이 완전히 끝날 때까지 메인 스레드 대기
        latch.await();

        // then: 검증
        User updatedUser = userRepository.findById(savedUser.getId()).orElseThrow();

        // Race Condition(경쟁 상태)으로 인한 데이터 유실 없이 최종 잔액이 정확히 100,000원(100 * 1,000)으로 누적
        assertThat(updatedUser.getBalance()).isEqualTo(threadCount * chargeAmount);
    }
}