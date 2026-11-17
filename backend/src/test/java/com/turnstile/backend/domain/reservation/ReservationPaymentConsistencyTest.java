package com.turnstile.backend.domain.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.scheduler.ReservationCleanupScheduler;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 좌석 예약 및 결제 처리 과정에서의 동시성 제어, 토큰 인가 검증 및 트랜잭션 데이터 정합성을 검증하는 통합 테스트입니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:13307/turnstile_imp005?serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=root",
        "spring.datasource.password=imp005-test-only",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "jwt.secret=imp005-isolated-test-signing-key-only-2026",
        "reservation.policy.hold-time-seconds=120",
        "reservation.policy.payment-ttl-seconds=120"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Timeout(90)
class ReservationPaymentConsistencyTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired ConcertScheduleRepository schedules;
    @Autowired SeatRepository seats;
    @Autowired ReservationRepository reservations;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @Autowired ObjectMapper objectMapper;

    @Autowired
    MeterRegistry meterRegistry;

    // DB 경합과 HTTP 토큰 검증에 집중하며 Redis ACTIVE 판정 및 백그라운드 스케줄러는 모의 처리함
    @MockitoBean QueueRedisRepository queue;
    @MockitoBean CacheEvictor cacheEvictor;
    @MockitoBean SeatHoldScheduler seatHoldScheduler;
    @MockitoBean ReservationCleanupScheduler cleanupScheduler;

    private User userA;
    private User userB;
    private Concert concert;
    private List<Long> seatIds;

    @BeforeEach
    void prepare() {
        // 테스트 격리를 위해 기존 데이터 일괄 초기화 및 기본 사용자·콘서트·좌석 데이터 생성
        payments.deleteAllInBatch();
        reservations.deleteAllInBatch();
        seats.deleteAllInBatch();
        schedules.deleteAllInBatch();
        concerts.deleteAllInBatch();
        users.deleteAllInBatch();

        userA = users.save(User.builder()
                .username("test-user-a").name("테스트 A").balance(10000L).build());

        userB = users.save(User.builder()
                .username("test-user-b").name("테스트 B").balance(10000L).build());

        concert = concerts.save(Concert.builder()
                .title("정합성 검증 테스트 콘서트")
                .ticketingStartAt(LocalDateTime.now().minusDays(1))
                .build());

        ConcertSchedule schedule = schedules.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(LocalDateTime.now().plusDays(1))
                .totalSeats(4)
                .build());

        seatIds = new ArrayList<>();

        for (int number = 1; number <= 4; number++) {
            Seat seat = seats.save(Seat.builder()
                    .schedule(schedule)
                    .seatNo(number)
                    .price(1000L)
                    .status(SeatStatus.AVAILABLE)
                    .build());

            seatIds.add(seat.getId());
        }

        given(queue.isActiveUser(anyString(), anyString())).willReturn(true);
    }

    private ReservationResponse reserve(User user, List<Long> selected) {
        return reservationService.reserveSeats(
                user.getId(),
                new ReservationRequest(concert.getId(), selected)
        );
    }

    private PaymentRequest paymentRequest(String groupId, long amount) {
        return new PaymentRequest(groupId, amount, concert.getId());
    }

    /**
     * 두 개의 비동기 작업을 CountDownLatch로 동기화하여 동시에 실행하고 결과를 수집합니다.
     */
    private List<Object> race(Callable<?> first, Callable<?> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = new ArrayList<>();

            for (Callable<?> action : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 시작 대기 실패");
                    }

                    try {
                        return action.call();
                    } catch (TurnstileException e) {
                        return e.getErrorCode();
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = new ArrayList<>();

            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @RepeatedTest(3)
    @DisplayName("동일 사용자가 동시에 좌석 예약을 요청해도 단 하나의 결제 대기 예약 그룹만 생성되어야 한다")
    void should_not_create_two_pending_groups_for_same_user() throws Exception {
        // when: 동일 사용자가 서로 다른 좌석 조합으로 동시 예약 요청
        List<Object> results = race(
                () -> reserve(userA, seatIds.subList(0, 2)),
                () -> reserve(userA, seatIds.subList(2, 4))
        );

        // then: 한 건만 성공하고 나머지는 ALREADY_HAS_PENDING_RESERVATION 예외 발생 및 2석만 점유 확인
        assertThat(results.stream()
                .filter(ReservationResponse.class::isInstance).count())
                .isEqualTo(1);

        assertThat(results).contains(ErrorCode.ALREADY_HAS_PENDING_RESERVATION);
        assertThat(reservations.count()).isEqualTo(2);

        assertThat(seats.findAll().stream()
                .filter(s -> s.getStatus() == SeatStatus.HOLD).count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("서로 다른 사용자가 동일한 좌석을 동시에 예약하면 한 명만 점유에 성공해야 한다")
    void should_not_hold_same_seat_by_different_users() throws Exception {
        // given: 동일한 단일 좌석 대상 설정
        List<Long> selected = List.of(seatIds.getFirst());

        // when: 두 사용자가 동시에 동일 좌석 예약 요청
        List<Object> results = race(
                () -> reserve(userA, selected),
                () -> reserve(userB, selected)
        );

        // then: 한 명만 성공하고 나머지는 SEAT_ALREADY_HELD 예외 발생 확인
        assertThat(results.stream()
                .filter(ReservationResponse.class::isInstance).count())
                .isEqualTo(1);

        assertThat(results).contains(ErrorCode.SEAT_ALREADY_HELD);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 예약 건에 대해 동시에 중복 결제를 요청해도 잔액 차감 및 결제는 단 한 번만 처리되어야 한다")
    void should_deduct_only_once_on_concurrent_payment() throws Exception {
        // given: 좌석 임시 점유 완료 및 결제 요청 데이터 생성
        ReservationResponse reserved = reserve(userA, seatIds);
        PaymentRequest request = paymentRequest(reserved.groupId(), 4000L);

        // when: 동일 예약 그룹에 대해 동시에 두 번 결제 요청
        List<Object> results = race(
                () -> paymentService.processPayment(userA.getId(), request),
                () -> paymentService.processPayment(userA.getId(), request)
        );

        assertThat(results.stream()
                .filter(PaymentResponse.class::isInstance).count())
                .isEqualTo(1);

        // then: 한 건만 결제 성공하고 나머지는 ALREADY_PAID 예외 발생, 잔액 1회 차감 및 SOLD 전이 확인
        assertThat(results).contains(ErrorCode.ALREADY_PAID);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance())
                .isEqualTo(6000L);

        assertThat(seats.findAll())
                .allMatch(s -> s.getStatus() == SeatStatus.SOLD);

        assertThat(reservations.findAll())
                .allMatch(r -> r.getStatus() == ReservationStatus.PAID);
    }

    @Test
    @DisplayName("결제 API 호출 시 토큰의 예약 그룹 및 소유자 일치 여부를 검증하고 정상 요청만 승인해야 한다")
    void should_validate_token_group_and_owner_on_payment_http() throws Exception {
        // given: 사용자 A의 2석 임시 점유 완료
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        // when & then: 토큰의 예약 그룹과 요청 본문의 그룹이 불일치하면 403 응답 반환
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest("different-group", 2000L))))
                .andExpect(status().isForbidden());

        // when & then: 예약 그룹 클레임이 누락된 결제 토큰으로 요청하면 401 응답 반환
        String legacyToken =
                jwtProvider.generateToken(userA.getId(), TokenType.PAYMENT);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + legacyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isUnauthorized());

        // when & then: 서명과 그룹이 유효하더라도 토큰 소유자와 예약 소유자가 다르면 403 응답 반환
        String otherUserToken = jwtProvider.generatePaymentToken(
                userB.getId(),
                reserved.groupId(),
                new java.util.Date(System.currentTimeMillis() + 60000)
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isForbidden());

        assertThat(payments.count()).isZero();
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance())
                .isEqualTo(10000L);

        // when & then: 소유자와 예약 그룹이 모두 일치하는 정상 결제 토큰 요청 시 200 응답 및 결제 완료 확인
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isOk());

        assertThat(payments.count()).isEqualTo(1);
    }

    /**
     * 결제 실패 시 예외 코드와 함께 결제 이력 미생성, 잔액 유지, 예약 및 좌석 상태 롤백 여부를 일괄 검증합니다.
     */
    private void assertPaymentRollback(
            ReservationResponse reserved,
            ErrorCode expected,
            long expectedBalance
    ) {
        TurnstileException error = assertThrows(
                TurnstileException.class,
                () -> paymentService.processPayment(
                        userA.getId(),
                        paymentRequest(reserved.groupId(), 2000L)
                )
        );

        assertThat(error.getErrorCode()).isEqualTo(expected);
        assertThat(payments.count()).isZero();

        assertThat(users.findById(userA.getId()).orElseThrow().getBalance())
                .isEqualTo(expectedBalance);

        assertThat(reservations.findAll())
                .allMatch(r -> r.getStatus() == ReservationStatus.PENDING);

        for (Long id : seatIds.subList(0, 2)) {
            assertThat(seats.findById(id).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.HOLD);
        }
    }

    @Test
    @DisplayName("잔액이 부족한 경우 결제 실패와 함께 예약 확정 상태가 롤백되어야 한다")
    void should_rollback_reservation_confirmation_when_balance_is_insufficient() {
        // given: 임시 점유 완료 후 사용자 잔액을 0원으로 변경
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        jdbc.update("UPDATE users SET balance = 0 WHERE id = ?", userA.getId());

        // when & then: INSUFFICIENT_BALANCE 예외 발생 및 전체 트랜잭션 롤백 검증
        assertPaymentRollback(reserved, ErrorCode.INSUFFICIENT_BALANCE, 0L);
    }

    @Test
    @DisplayName("예약 그룹 내 좌석 중 하나라도 점유 시간이 만료된 경우 전체 결제가 롤백되어야 한다")
    void should_rollback_all_changes_when_one_seat_is_expired() {
        // given: 2석 임시 점유 후 첫 번째 좌석의 점유 만료 시각을 과거로 조작
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        jdbc.update(
                "UPDATE seat SET hold_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                seatIds.getFirst()
        );

        // when & then: INVALID_SEAT_HOLD 예외 발생 및 전체 트랜잭션 롤백 검증
        assertPaymentRollback(reserved, ErrorCode.INVALID_SEAT_HOLD, 10000L);
    }

    @Test
    @DisplayName("예약 그룹 내 좌석 중 하나라도 타 사용자 점유로 변경된 경우 전체 결제가 롤백되어야 한다")
    void should_rollback_all_changes_when_one_seat_has_foreign_hold() {
        // given: 2석 임시 점유 후 첫 번째 좌석의 점유 소유자를 사용자 B로 조작
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        jdbc.update(
                "UPDATE seat SET hold_user_id = ? WHERE id = ?",
                userB.getId(),
                seatIds.getFirst()
        );

        // when & then: INVALID_SEAT_HOLD 예외 발생 및 전체 트랜잭션 롤백 검증
        assertPaymentRollback(reserved, ErrorCode.INVALID_SEAT_HOLD, 10000L);
    }

    /**
     * 지정된 후처리 작업(operation)의 실패 메트릭 카운터 값을 조회합니다.
     */
    private double postCommitFailureCount(String operation) {
        Counter counter = meterRegistry
                .find("turnstile.post_commit.failures")
                .tag("operation", operation)
                .counter();

        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("결제 커밋 이후 Redis 후처리에서 지연이나 예외가 발생해도 결제 성공 응답과 DB 정합성은 유지되어야 한다")
    void should_keep_successful_payment_when_post_commit_redis_fails() throws Exception {
        // given: 2석 임시 점유 완료 및 이전 예약 커밋 시점의 모의 객체 호출 이력 초기화
        ReservationResponse reserved =
                reserve(userA, seatIds.subList(0, 2));

        clearInvocations(queue, cacheEvictor);

        double queueFailuresBefore = postCommitFailureCount("queue_remove");
        double cacheFailuresBefore = postCommitFailureCount("seat_cache_evict");

        // 후처리 경계에서의 네트워크 장애 상황을 재현하기 위해 지연 및 Redis 예외 주입
        doAnswer(invocation -> {
            Thread.sleep(200);
            throw new QueryTimeoutException("Injected queue timeout");
        }).when(queue).removeActiveUser(anyString(), anyString());

        doThrow(new RedisConnectionFailureException(
                "Injected cache connection failure"
        )).when(cacheEvictor).evictSeatCache(anyLong());

        PaymentRequest request = paymentRequest(reserved.groupId(), 2000L);

        String requestJson = objectMapper.writeValueAsString(request);

        long started = System.nanoTime();

        // when: 결제 API 호출
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk());

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // then: 프론트엔드 타임아웃(5초) 이내 정상 응답, DB 결제 확정 및 실패 메트릭 증가 확인
        assertThat(elapsedMillis).isLessThan(5000L);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(150L);

        assertThat(payments.count()).isEqualTo(1);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(8000L);
        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PAID);

        for (Long id : seatIds.subList(0, 2)) {
            assertThat(seats.findById(id).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.SOLD);
        }

        assertThat(postCommitFailureCount("queue_remove")).isEqualTo(queueFailuresBefore + 1);
        assertThat(postCommitFailureCount("seat_cache_evict")).isEqualTo(cacheFailuresBefore + 1);

        verify(queue).removeActiveUser(
                String.valueOf(concert.getId()),
                String.valueOf(userA.getId())
        );

        verify(cacheEvictor).evictSeatCache(anyLong());

        // when & then: 네트워크 지연으로 응답을 못 받은 사용자의 중복 재요청 시 400 응답 및 이중 차감·후처리 재실행 방지 확인
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest());

        assertThat(payments.count()).isEqualTo(1);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(8000L);

        verify(queue, times(1)).removeActiveUser(anyString(), anyString());
        verify(cacheEvictor, times(1)).evictSeatCache(anyLong());
    }

    @Test
    @DisplayName("결제 트랜잭션이 롤백되는 경우 커밋 이후 후처리 리스너는 실행되지 않아야 한다")
    void should_not_execute_post_commit_handlers_on_rollback() throws Exception {
        // given: 2석 임시 점유 완료 및 잔액 부족 롤백 유도를 위해 사용자 잔액을 0원으로 변경
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        clearInvocations(queue, cacheEvictor);

        double queueFailuresBefore = postCommitFailureCount("queue_remove");
        double cacheFailuresBefore = postCommitFailureCount("seat_cache_evict");

        jdbc.update(
                "UPDATE users SET balance = 0 WHERE id = ?",
                userA.getId()
        );

        // when: 잔액 부족 상태에서 결제 API 호출
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isBadRequest());

        // then: 트랜잭션 롤백에 따라 후처리 리스너 미실행 및 예약·좌석 상태 유지 확인
        verify(queue, never()).removeActiveUser(anyString(), anyString());
        verify(cacheEvictor, never()).evictSeatCache(anyLong());

        assertThat(postCommitFailureCount("queue_remove")).isEqualTo(queueFailuresBefore);
        assertThat(postCommitFailureCount("seat_cache_evict")).isEqualTo(cacheFailuresBefore);

        assertThat(payments.count()).isZero();
        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PENDING);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isZero();

        for (Long id : seatIds.subList(0, 2)) {
            assertThat(seats.findById(id).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.HOLD);
        }
    }
}