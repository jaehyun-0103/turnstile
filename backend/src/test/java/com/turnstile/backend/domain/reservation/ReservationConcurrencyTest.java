package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 다중 스레드 환경에서 좌석 선점(CAS)의 동시성 제어와 원자성을 검증하는 통합 테스트입니다.
 */
@SpringBootTest
class ReservationConcurrencyTest {

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QueueRedisRepository queueRedisRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 현재 예매 진행 중인 1번 콘서트 사용
    private final Long TARGET_CONCERT_ID = 1L;

    @AfterEach
    void tearDown() {
        // 멀티스레드 테스트로 인해 오염된 예약 내역 삭제 및 좌석 상태만 원래대로 복구
        reservationRepository.deleteAllInBatch();
        jdbcTemplate.update("UPDATE seat SET status = 'AVAILABLE', hold_user_id = NULL, hold_expires_at = NULL WHERE id IN (?, ?, ?)", 1L, 2L, 3L);
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("동시 점유 정합성: 100명이 동시에 동일 좌석을 요청할 경우 단 1명만 선점에 성공해야 한다")
    void should_allow_only_one_user_when_concurrently_reserving_same_seat() throws Exception {
        // given: 더미 데이터에 이미 존재하는 1~100번 유저를 그대로 로드
        int threadCount = 100;
        Long targetSeatId = 1L;
        long expireScore = Instant.now().getEpochSecond() + 300;

        // 100명의 실제 유저를 DB에 생성 및 대기열 통과 처리
        List<User> concurrentUsers = userRepository.findAllById(
                LongStream.rangeClosed(1, threadCount).boxed().toList()
        );

        // Redis 대기열 통과 상태 세팅
        for (User u : concurrentUsers) {
            queueRedisRepository.enterQueue(String.valueOf(TARGET_CONCERT_ID), String.valueOf(u.getId()));
        }
        queueRedisRepository.transitionQueue(String.valueOf(TARGET_CONCERT_ID), 1000, 1000, expireScore);

        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 100명의 유저가 1번 좌석을 동시에 요청
        for (User u : concurrentUsers) {
            final long userId = u.getId();
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    reservationService.reserveSeats(userId, new ReservationRequest(TARGET_CONCERT_ID, List.of(targetSeatId)));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        // then: 단 1명만 성공하고 99명은 실패해야 함
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        Seat resultSeat = seatRepository.findById(targetSeatId).orElseThrow();
        assertThat(resultSeat.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(resultSeat.getHoldUserId()).isNotNull();
    }

    @Test
    @DisplayName("연석 원자성 검증: 연석 예매 시도 중 하나라도 선점된 좌석이 포함되어 있다면 예외가 발생하고 전체 롤백되어야 한다")
    void should_rollback_entirely_when_one_of_consecutive_seats_is_already_held() {
        // given: 3번 좌석을 99번 유저가 이미 정상적으로 점유(HOLD)한 상태로 세팅
        Long availableSeatId = 2L;
        Long heldSeatId = 3L;
        Long requestUserId = 1L;

        // DB 억지 조작이 아닌 실제 비즈니스 로직을 태워 자연스럽게 HOLD 상태 확보
        queueRedisRepository.enterQueue(String.valueOf(TARGET_CONCERT_ID), String.valueOf(99L));
        queueRedisRepository.transitionQueue(String.valueOf(TARGET_CONCERT_ID), 1000, 1000, Instant.now().getEpochSecond() + 300);
        reservationService.reserveSeats(99L, new ReservationRequest(TARGET_CONCERT_ID, List.of(heldSeatId)));

        // 1번 유저 대기열 인가 처리
        queueRedisRepository.enterQueue(String.valueOf(TARGET_CONCERT_ID), String.valueOf(requestUserId));
        queueRedisRepository.transitionQueue(String.valueOf(TARGET_CONCERT_ID), 1000, 1000, Instant.now().getEpochSecond() + 300);

        // when & then: 1번 유저가 2번(AVAILABLE)과 3번(HOLD) 좌석을 동시에 예매 시도 시 거절 검증
        ReservationRequest request = new ReservationRequest(TARGET_CONCERT_ID, List.of(availableSeatId, heldSeatId));

        assertThatThrownBy(() -> reservationService.reserveSeats(requestUserId, request))
                .isInstanceOf(TurnstileException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SEAT_ALREADY_HELD);

        // then: 롤백 검증 (1번 좌석은 여전히 AVAILABLE 유지)
        Seat resultSeat1 = seatRepository.findById(availableSeatId).orElseThrow();
        assertThat(resultSeat1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(resultSeat1.getHoldUserId()).isNull();
    }

    @Test
    @DisplayName("연석 교차 경합: 두 유저가 서로 엇갈린 순서의 연석(A: 1->2, B: 2->1)을 동시에 예매 시도해도 교착 상태(Deadlock) 없이 안전하게 한쪽 트랜잭션이 롤백되어야 한다")
    void should_prevent_deadlock_when_concurrently_reserving_consecutive_seats_in_cross_order() throws Exception {
        // given: 동일한 콘서트의 1번, 2번 좌석과 경합할 유저 A(1L), 유저 B(2L) 세팅
        Long targetConcertId = 1L;
        Long userA = 1L;
        Long userB = 2L;

        List<Long> requestSeatsA = List.of(1L, 2L); // A는 정방향 요청
        List<Long> requestSeatsB = List.of(2L, 1L); // B는 역방향 요청

        long expireScore = Instant.now().getEpochSecond() + 300;

        // 두 유저 모두 대기열 통과 상태로 세팅
        queueRedisRepository.enterQueue(String.valueOf(targetConcertId), String.valueOf(userA));
        queueRedisRepository.enterQueue(String.valueOf(targetConcertId), String.valueOf(userB));
        queueRedisRepository.transitionQueue(String.valueOf(targetConcertId), 1000, 1000, expireScore);

        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: User A와 User B가 정확히 동시에 엇갈린 순서로 예약 로직 타격
        executorService.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                reservationService.reserveSeats(userA, new ReservationRequest(targetConcertId, requestSeatsA));
                successCount.incrementAndGet();
            } catch (TurnstileException | InterruptedException e) {
                failCount.incrementAndGet(); // Fail-Fast 예외 확인
            } finally {
                doneLatch.countDown();
            }
        });

        executorService.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                reservationService.reserveSeats(userB, new ReservationRequest(targetConcertId, requestSeatsB));
                successCount.incrementAndGet();
            } catch (TurnstileException | InterruptedException e) {
                failCount.incrementAndGet(); // Fail-Fast 예외 확인
            } finally {
                doneLatch.countDown();
            }
        });

        readyLatch.await();
        startLatch.countDown(); // 동시 출발 총성
        doneLatch.await();
        executorService.shutdown();

        // then: DB 데드락이 발생하지 않고, 한 명은 성공하고 한 명은 깔끔하게 예외로 실패해야 함
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        // then: 두 좌석 모두 한 명의 유저에게만 귀속되어야 함 (트랜잭션 롤백 무결성 보장)
        Seat seat1 = seatRepository.findById(1L).orElseThrow();
        Seat seat2 = seatRepository.findById(2L).orElseThrow();

        assertThat(seat1.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(seat2.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(seat1.getHoldUserId()).isEqualTo(seat2.getHoldUserId());
    }
}