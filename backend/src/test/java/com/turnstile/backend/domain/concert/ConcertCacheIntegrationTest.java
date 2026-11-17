package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.concert.service.ConcertService;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
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
import com.turnstile.backend.global.common.SeatCacheKeys;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.turnstile.backend.support.IsolatedIntegrationTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "seat-cache.ttl-seconds=2"
})
@Timeout(90)
class ConcertCacheIntegrationTest extends IsolatedIntegrationTest {

    @Autowired ConcertService concertService;
    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired ConcertRepository concerts;
    @Autowired ConcertScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired ReservationRepository reservations;
    @Autowired PaymentRepository payments;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    @MockitoSpyBean SeatRepository seats;
    @MockitoBean QueueRedisRepository queue;
    @MockitoBean SeatHoldScheduler automaticCleanup;
    @MockitoBean ReservationCleanupScheduler oldReservationCleanup;

    private User userA;
    private User userB;
    private Concert concert;
    private ConcertSchedule schedule;
    private Seat seat;
    private String cacheKey;
    private String lockKey;
    private String otherKey;

    @BeforeEach
    void prepare() {
        // 테스트 격리를 위한 기존 데이터 및 Redis 키 초기화
        payments.deleteAllInBatch();
        reservations.deleteAllInBatch();
        seats.deleteAllInBatch();
        schedules.deleteAllInBatch();
        concerts.deleteAllInBatch();
        users.deleteAllInBatch();

        userA = users.save(User.builder().username("test-user-a").name("테스트 A").balance(10000L).build());
        userB = users.save(User.builder().username("test-user-b").name("테스트 B").balance(10000L).build());

        concert = concerts.save(Concert.builder()
                .title("캐시 통합 테스트 콘서트")
                .ticketingStartAt(LocalDateTime.now().minusDays(1))
                .build());

        schedule = schedules.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(LocalDateTime.now().plusDays(1))
                .totalSeats(1)
                .build());

        cacheKey = SeatCacheKeys.seats(schedule.getId());
        lockKey = SeatCacheKeys.lock(schedule.getId());
        otherKey = SeatCacheKeys.seats(schedule.getId() + 100000L);

        redis.delete(List.of(cacheKey, lockKey, otherKey));

        seat = seats.save(Seat.builder()
                .schedule(schedule)
                .seatNo(1)
                .price(1000L)
                .status(SeatStatus.AVAILABLE)
                .build());

        given(queue.isActiveUser(anyString(), anyString())).willReturn(true);
    }

    @AfterEach
    void clearOwnKeys() {
        if (cacheKey != null) {
            redis.delete(List.of(cacheKey, lockKey, otherKey));
        }
    }

    private ReservationResponse reserve(User user) {
        return reservationService.reserveSeats(
                user.getId(),
                new ReservationRequest(concert.getId(), List.of(seat.getId()))
        );
    }

    private String state() {
        return concertService.getSeats(schedule.getId()).seats().getFirst().status();
    }

    private void makeHoldExpired() {
        jdbc.update(
                "UPDATE seat SET hold_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                seat.getId()
        );
    }

    private void runRecovery(boolean rollback) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            // 스케줄러 자동 실행은 차단하고 실제 복구 메서드를 명시적 트랜잭션 내에서 호출
            new SeatHoldScheduler(seats, reservations, events).recoverExpiredSeats();

            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }

    private void blockFirstSeatRead(
            CountDownLatch read,
            CountDownLatch release
    ) {
        AtomicBoolean first = new AtomicBoolean(true);

        doAnswer(invocation -> {
            Long scheduleId = invocation.getArgument(0);

            // 스파이 객체의 추상 메서드 재귀 호출을 방지하고 실제 DB 쿼리 실행
            List<Seat> result = entityManager.createQuery(
                            "SELECT s FROM Seat s WHERE s.schedule.id = :scheduleId",
                            Seat.class
                    )
                    .setParameter("scheduleId", scheduleId)
                    .getResultList();

            if (first.compareAndSet(true, false)) {
                read.countDown();

                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("조회 지연 해제 시간 초과");
                }
            }

            return result;
        }).when(seats).findAllBySchedule_Id(schedule.getId());
    }

    @Test
    @DisplayName("좌석 목록 반복 조회 시 캐시가 적중하여 DB를 1회만 조회하고 설정된 TTL이 적용되어야 한다")
    void should_hit_cache_and_apply_configured_ttl() {
        assertThat(state()).isEqualTo("AVAILABLE");
        assertThat(state()).isEqualTo("AVAILABLE");

        // then: DB 조회 1회 발생 및 설정된 TTL(2초 이하) 적용 검증
        verify(seats, times(1)).findAllBySchedule_Id(schedule.getId());

        Long ttl = redis.getExpire(cacheKey, TimeUnit.MILLISECONDS);

        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(2000L);
    }

    @Test
    @DisplayName("좌석 점유 및 결제 완료 시 대상 회차의 캐시만 무효화되고 타 회차 캐시는 유지되어야 한다")
    void should_evict_only_target_cache_on_hold_and_payment() {
        // given: 대상 회차 및 타 회차 캐시 사전 적재
        assertThat(state()).isEqualTo("AVAILABLE");
        redis.expire(cacheKey, Duration.ofSeconds(30));
        redis.opsForValue().set(otherKey, "other-schedule", Duration.ofSeconds(30));

        // when & then: 좌석 점유 시 대상 캐시 무효화, 타 회차 캐시 유지 및 HOLD 상태 반영 검증
        ReservationResponse reserved = reserve(userA);

        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(redis.opsForValue().get(otherKey)).isEqualTo("other-schedule");
        assertThat(state()).isEqualTo("HOLD");

        redis.expire(cacheKey, Duration.ofSeconds(30));

        // when & then: 결제 완료 시 대상 캐시 무효화, 타 회차 캐시 유지 및 SOLD 상태 반영 검증
        paymentService.processPayment(
                userA.getId(),
                new PaymentRequest(reserved.groupId(), 1000L, concert.getId())
        );

        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(redis.opsForValue().get(otherKey)).isEqualTo("other-schedule");
        assertThat(state()).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("만료된 점유 좌석 복구 트랜잭션이 커밋되면 좌석 상태가 초기화되고 캐시가 무효화되어야 한다")
    void should_evict_cache_after_commit_when_recovering_expired_hold() {
        // given: 좌석 점유 후 만료 시점 경과 처리
        reserve(userA);
        assertThat(state()).isEqualTo("HOLD");
        redis.expire(cacheKey, Duration.ofSeconds(30));

        makeHoldExpired();

        // when: 만료 좌석 복구 실행 및 커밋
        runRecovery(false);

        // then: 캐시 무효화, 좌석 AVAILABLE 복구 및 예약 EXPIRED 상태 변경 검증
        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(state()).isEqualTo("AVAILABLE");

        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("만료 좌석 복구 트랜잭션이 롤백되면 기존 캐시와 점유 상태가 그대로 유지되어야 한다")
    void should_keep_cache_and_hold_state_when_recovery_rolls_back() {
        // given: 좌석 점유 후 만료 시점 경과 및 기존 캐시 값 확보
        reserve(userA);
        assertThat(state()).isEqualTo("HOLD");
        redis.expire(cacheKey, Duration.ofSeconds(30));

        String before = redis.opsForValue().get(cacheKey);

        makeHoldExpired();

        // when: 만료 좌석 복구 실행 후 강제 롤백
        runRecovery(true);

        // then: 기존 캐시 데이터, 좌석 HOLD 상태 및 예약 PENDING 상태 유지 검증
        assertThat(redis.opsForValue().get(cacheKey)).isEqualTo(before);

        assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HOLD);

        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("분산 락 만료 이후 뒤늦게 작업을 마친 기존 소유자는 타인의 신규 락을 해제할 수 없어야 한다")
    void should_not_delete_replacement_lock_by_expired_owner() throws Exception {
        // given: 첫 번째 조회 스레드가 DB 조회 지점에서 지연되도록 설정
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstSeatRead(read, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<SeatListResponse> future =
                    executor.submit(() -> concertService.getSeats(schedule.getId()));

            if (!read.await(5, TimeUnit.SECONDS)) {
                // 작업이 예외로 종료된 경우 실제 원인을 즉시 노출
                future.get(1, TimeUnit.SECONDS);

                throw new AssertionError(
                        "좌석 조회 지연 지점에 도달하지 못했습니다."
                );
            }

            String firstToken = redis.opsForValue().get(lockKey);
            assertThat(firstToken).isNotBlank().isNotEqualTo("locked");

            // when: 기존 락 TTL 만료 대기 후 새로운 소유자가 동일 키의 락을 선점하고 기존 스레드 재개
            Thread.sleep(3200);

            assertThat(redis.opsForValue().setIfAbsent(
                    lockKey,
                    "replacement-owner",
                    Duration.ofSeconds(10)
            )).isTrue();

            release.countDown();
            future.get(5, TimeUnit.SECONDS);

            // then: 기존 소유자 종료 후에도 신규 소유자의 락 토큰이 삭제되지 않고 유지되는지 검증
            assertThat(redis.opsForValue().get(lockKey)).isEqualTo("replacement-owner");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("지연된 조회로 구 상태 캐시가 재적재되어도 DB 낙관적 검증으로 중복 점유를 차단하고 TTL 만료 후 최신 상태를 복구해야 한다")
    void should_reject_duplicate_hold_and_recover_from_stale_cache_refill() throws Exception {
        // given: 첫 번째 조회 스레드가 AVAILABLE 상태를 읽은 뒤 지연되도록 설정
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstSeatRead(read, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<SeatListResponse> future = executor.submit(() -> concertService.getSeats(schedule.getId()));

            if (!read.await(5, TimeUnit.SECONDS)) {
                // 작업이 예외로 종료된 경우 실제 원인을 즉시 노출
                future.get(1, TimeUnit.SECONDS);

                throw new AssertionError(
                        "좌석 조회 지연 지점에 도달하지 못했습니다."
                );
            }

            // 조회 스레드가 AVAILABLE을 읽은 직후 선행 예약이 커밋되어 캐시 무효화 실행
            reserve(userA);
            assertThat(redis.hasKey(cacheKey)).isFalse();

            // when: 지연되었던 조회 스레드가 재개되어 이전 AVAILABLE 목록을 캐시에 재적재
            release.countDown();

            assertThat(future.get(5, TimeUnit.SECONDS).seats().getFirst().status()).isEqualTo("AVAILABLE");

            assertThat(state()).isEqualTo("AVAILABLE");

            // then: 캐시가 AVAILABLE로 보이더라도 실제 DB 조건부 업데이트 단계에서 중복 점유 차단 검증
            TurnstileException error = assertThrows(
                    TurnstileException.class,
                    () -> reserve(userB)
            );

            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_HELD);
            assertThat(reservations.count()).isEqualTo(1);

            assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HOLD);

            // then: 설정된 짧은 TTL 만료 후 캐시가 제거되고 DB 기준 HOLD 상태로 정상 복구되는지 검증
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(5);

            while (
                    Boolean.TRUE.equals(redis.hasKey(cacheKey))
                            && System.nanoTime() < deadline
            ) {
                Thread.sleep(50);
            }

            assertThat(redis.hasKey(cacheKey)).isFalse();
            assertThat(state()).isEqualTo("HOLD");

            long elapsed = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );

            System.out.println("STALE_RECOVERY_MS=" + elapsed);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}