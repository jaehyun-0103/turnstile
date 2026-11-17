package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.service.AuthService;
import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
import com.turnstile.backend.domain.reservation.scheduler.ReservationCleanupScheduler;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.turnstile.backend.support.IsolatedIntegrationTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 사용자 잔액의 데이터베이스 기반 정합성과 동시 차감/충전 시나리오를 검증하는 통합 테스트입니다.
 */
@SpringBootTest
@Timeout(90)
class UserBalanceConsistencyTest extends IsolatedIntegrationTest {

    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired AuthService authService;
    @Autowired StringRedisTemplate redis;
    @Autowired CacheManager cacheManager;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean SeatHoldScheduler seatHoldScheduler;
    @MockitoBean ReservationCleanupScheduler reservationCleanupScheduler;
    @MockitoBean QueueScheduler queueScheduler;

    private User user;
    private String legacyKey;

    @BeforeEach
    void prepare() {
        // 테스트용 사용자 생성 및 기존 캐시 키 초기화
        user = users.save(User.builder()
                .username("user-" + UUID.randomUUID())
                .name("잔액 검증")
                .balance(10000L)
                .build());

        legacyKey = "user:info:" + user.getUsername();
        redis.delete(legacyKey);
    }

    @AfterEach
    void cleanup() {
        if (legacyKey != null) {
            redis.delete(legacyKey);
        }
        if (user != null) {
            users.deleteById(user.getId());
        }
    }

    @Test
    @DisplayName("로그인 및 잔액 변경 시 레거시 캐시를 참조하거나 재생성하지 않고 DB 값을 기준으로 반환해야 한다")
    void should_use_database_without_recreating_cache_on_login_and_balance_changes() {
        // given: 기존 레거시 캐시 키를 Redis에 사전 적재
        redis.opsForValue().set(legacyKey, "legacy-cache-marker");

        // when & then: 로그인 및 충전·차감 시 캐시 미사용 및 DB 기준 최신 잔액 반환 검증
        assertThat(cacheManager.getCache("user:info")).isNull();
        assertThat(loginBalance()).isEqualTo(10000L);

        assertThat(userService.chargeBalance(user.getId(), 2000L)).isEqualTo(12000L);
        assertThat(loginBalance()).isEqualTo(12000L);
        assertThat(userService.getBalance(user.getId())).isEqualTo(12000L);

        assertThat(userService.deductBalance(user.getId(), 3000L)).isEqualTo(9000L);
        assertThat(loginBalance()).isEqualTo(9000L);
        assertThat(userService.getBalance(user.getId())).isEqualTo(9000L);

        assertThat(redis.opsForValue().get(legacyKey)).isEqualTo("legacy-cache-marker");

        // 잔여 키 정리 후에도 로그인 및 잔액 조회 시 캐시가 재생성되지 않는지 검증
        redis.delete(legacyKey);
        assertThat(loginBalance()).isEqualTo(9000L);
        assertThat(userService.getBalance(user.getId())).isEqualTo(9000L);
        assertThat(redis.hasKey(legacyKey)).isFalse();
    }

    @Test
    @DisplayName("동시 잔액 차감 요청 시 각 트랜잭션의 순차 반영 결과와 최종 DB 잔액이 일치해야 한다")
    void should_return_consistent_balances_on_concurrent_deductions() throws Exception {
        List<Long> results = race(
                () -> userService.deductBalance(user.getId(), 3000L),
                () -> userService.deductBalance(user.getId(), 5000L)
        );

        // then: 실행 순서에 따른 각 트랜잭션 반환 잔액 및 최종 DB 잔액(2000원) 일치 검증
        assertThat(
                results.equals(List.of(7000L, 2000L)) || results.equals(List.of(2000L, 5000L))
        ).isTrue();

        assertThat(userService.getBalance(user.getId())).isEqualTo(2000L);
        assertThat(loginBalance()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("동시 잔액 충전 및 차감 요청 시 각 트랜잭션의 반영 결과와 최종 DB 잔액이 일치해야 한다")
    void should_return_consistent_balances_on_concurrent_charge_and_deduction() throws Exception {
        // when: 2000원 충전 및 3000원 차감 트랜잭션 동시 실행
        List<Long> results = race(
                () -> userService.chargeBalance(user.getId(), 2000L),
                () -> userService.deductBalance(user.getId(), 3000L)
        );

        // then: 실행 순서에 따른 각 트랜잭션 반환 잔액 및 최종 DB 잔액(9000원) 일치 검증
        assertThat(
                results.equals(List.of(12000L, 9000L)) || results.equals(List.of(9000L, 7000L))
        ).isTrue();

        assertThat(userService.getBalance(user.getId())).isEqualTo(9000L);
        assertThat(loginBalance()).isEqualTo(9000L);
    }

    @Test
    @DisplayName("잔액이 부족한 상태에서 차감 요청 시 INSUFFICIENT_BALANCE 예외가 발생하고 기존 잔액이 유지되어야 한다")
    void insufficientBalanceLeavesDatabaseUnchanged() {
        // when & then: 보유 잔액을 초과하는 차감 요청 시 예외 발생 및 잔액 미변경 검증
        TurnstileException error = assertThrows(
                TurnstileException.class,
                () -> userService.deductBalance(user.getId(), 10001L)
        );

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        assertThat(userService.getBalance(user.getId())).isEqualTo(10000L);
        assertThat(loginBalance()).isEqualTo(10000L);
    }

    private Long loginBalance() {
        return authService.login(new LoginRequest(user.getUsername())).balance();
    }

    private List<Long> race(LongSupplier first, LongSupplier second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Long> firstResult = executor.submit(
                    () -> runAfterInitialRead(first, ready, start)
            );
            Future<Long> secondResult = executor.submit(
                    () -> runAfterInitialRead(second, ready, start)
            );

            if (!ready.await(5, TimeUnit.SECONDS)) {
                start.countDown();
                firstResult.get(15, TimeUnit.SECONDS);
                secondResult.get(15, TimeUnit.SECONDS);
                throw new AssertionError("두 트랜잭션의 초기 조회 대기 실패");
            }

            start.countDown();

            return List.of(
                    firstResult.get(15, TimeUnit.SECONDS),
                    secondResult.get(15, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Long runAfterInitialRead(
            LongSupplier operation,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        return transaction.execute(status -> {
            // 갱신 전 초기 잔액 조회로 1차 영속성 컨텍스트 상태를 선점함
            assertThat(users.findById(user.getId()).orElseThrow().getBalance()).isEqualTo(10000L);

            ready.countDown();

            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 갱신 시작 대기 실패");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("동시 갱신 대기 중 인터럽트", e);
            }

            return operation.getAsLong();
        });
    }
}