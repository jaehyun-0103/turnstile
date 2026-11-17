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
import org.springframework.test.context.ActiveProfiles;
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
        "spring.datasource.url=jdbc:mysql://localhost:13307/turnstile_imp005?serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=root",
        "spring.datasource.password=imp005-test-only",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=16379",
        "jwt.secret=imp007-isolated-test-signing-key-only-2026",
        "seat-cache.ttl-seconds=2"
})
@ActiveProfiles("imp007")
@Timeout(90)
class ConcertCacheIntegrationTest {

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
        payments.deleteAllInBatch();
        reservations.deleteAllInBatch();
        seats.deleteAllInBatch();
        schedules.deleteAllInBatch();
        concerts.deleteAllInBatch();
        users.deleteAllInBatch();

        userA = users.save(User.builder()
                .username("imp007-a").name("테스트 A").balance(10000L).build());

        userB = users.save(User.builder()
                .username("imp007-b").name("테스트 B").balance(10000L).build());

        concert = concerts.save(Concert.builder()
                .title("IMP-007 테스트")
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
        return concertService.getSeats(schedule.getId())
                .seats().getFirst().status();
    }

    private void makeHoldExpired() {
        jdbc.update(
                "UPDATE seat SET hold_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                seat.getId()
        );
    }

    private void runRecovery(boolean rollback) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            // 자동 실행은 막고 실제 복구 메서드를 명시적 트랜잭션에서 실행함
            new SeatHoldScheduler(seats, reservations, events)
                    .recoverExpiredSeats();

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

            // Repository의 추상 메서드를 직접 호출하지 않고 실제 DB를 조회함
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
    void cacheHitAndConfiguredTtl() {
        assertThat(state()).isEqualTo("AVAILABLE");
        assertThat(state()).isEqualTo("AVAILABLE");

        verify(seats, times(1)).findAllBySchedule_Id(schedule.getId());

        Long ttl = redis.getExpire(cacheKey, TimeUnit.MILLISECONDS);

        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(2000L);
    }

    @Test
    void holdAndPaymentEvictOnlyTargetCache() {
        assertThat(state()).isEqualTo("AVAILABLE");
        redis.expire(cacheKey, Duration.ofSeconds(30));
        redis.opsForValue().set(otherKey, "other-schedule", Duration.ofSeconds(30));

        ReservationResponse reserved = reserve(userA);

        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(redis.opsForValue().get(otherKey)).isEqualTo("other-schedule");
        assertThat(state()).isEqualTo("HOLD");

        redis.expire(cacheKey, Duration.ofSeconds(30));

        paymentService.processPayment(
                userA.getId(),
                new PaymentRequest(reserved.groupId(), 1000L, concert.getId())
        );

        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(redis.opsForValue().get(otherKey)).isEqualTo("other-schedule");
        assertThat(state()).isEqualTo("SOLD");
    }

    @Test
    void expiredHoldRecoveryEvictsAfterCommit() {
        reserve(userA);
        assertThat(state()).isEqualTo("HOLD");
        redis.expire(cacheKey, Duration.ofSeconds(30));

        makeHoldExpired();
        runRecovery(false);

        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(state()).isEqualTo("AVAILABLE");

        assertThat(reservations.findAll())
                .allMatch(r -> r.getStatus() == ReservationStatus.EXPIRED);
    }

    @Test
    void rollbackKeepsCache() {
        reserve(userA);
        assertThat(state()).isEqualTo("HOLD");
        redis.expire(cacheKey, Duration.ofSeconds(30));

        String before = redis.opsForValue().get(cacheKey);

        makeHoldExpired();
        runRecovery(true);

        assertThat(redis.opsForValue().get(cacheKey)).isEqualTo(before);

        assertThat(seats.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HOLD);

        assertThat(reservations.findAll())
                .allMatch(r -> r.getStatus() == ReservationStatus.PENDING);
    }

    @Test
    void expiredOwnerCannotDeleteReplacementLock() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstSeatRead(read, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<SeatListResponse> future =
                    executor.submit(() -> concertService.getSeats(schedule.getId()));

            if (!read.await(5, TimeUnit.SECONDS)) {
                // 작업이 예외로 끝났다면 실제 원인을 출력하도록 함
                future.get(1, TimeUnit.SECONDS);

                throw new AssertionError(
                        "좌석 조회 지연 지점에 도달하지 못했습니다."
                );
            }

            String firstToken = redis.opsForValue().get(lockKey);
            assertThat(firstToken).isNotBlank().isNotEqualTo("locked");

            // 실제 3초 락 TTL을 넘긴 뒤 다른 소유자의 락을 획득함
            Thread.sleep(3200);

            assertThat(redis.opsForValue().setIfAbsent(
                    lockKey,
                    "replacement-owner",
                    Duration.ofSeconds(10)
            )).isTrue();

            release.countDown();
            future.get(5, TimeUnit.SECONDS);

            assertThat(redis.opsForValue().get(lockKey))
                    .isEqualTo("replacement-owner");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void staleRefillExpiresAndDbRejectsDuplicateHold() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstSeatRead(read, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<SeatListResponse> future =
                    executor.submit(() -> concertService.getSeats(schedule.getId()));

            if (!read.await(5, TimeUnit.SECONDS)) {
                // 작업이 예외로 끝났다면 실제 원인을 출력하도록 함
                future.get(1, TimeUnit.SECONDS);

                throw new AssertionError(
                        "좌석 조회 지연 지점에 도달하지 못했습니다."
                );
            }

            // 조회가 AVAILABLE을 읽은 뒤 HOLD가 커밋되고 캐시 삭제가 실행됨
            reserve(userA);
            assertThat(redis.hasKey(cacheKey)).isFalse();

            // 지연된 조회가 이전 AVAILABLE 목록을 다시 저장함
            release.countDown();

            assertThat(future.get(5, TimeUnit.SECONDS)
                    .seats().getFirst().status()).isEqualTo("AVAILABLE");

            assertThat(state()).isEqualTo("AVAILABLE");

            TurnstileException error = assertThrows(
                    TurnstileException.class,
                    () -> reserve(userB)
            );

            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_HELD);
            assertThat(reservations.count()).isEqualTo(1);

            assertThat(seats.findById(seat.getId()).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.HOLD);

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

            System.out.println("IMP007_STALE_RECOVERY_MS=" + elapsed);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}