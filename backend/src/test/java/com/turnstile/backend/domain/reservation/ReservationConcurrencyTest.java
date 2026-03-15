package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private QueueRedisRepository queueRedisRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long testConcertId;
    private Long testSeatId1;
    private Long testSeatId2;
    private User testUser;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            concertScheduleRepository.deleteAllInBatch();
            concertRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();

            testUser = userRepository.save(User.builder().username("test_user").name("test").balance(0L).build());
            Concert concert = concertRepository.save(Concert.builder().title("아이유 콘서트").ticketingStartAt(LocalDateTime.now().minusDays(1)).build());
            testConcertId = concert.getId();
            ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder().concert(concert).concertDate(LocalDateTime.now().plusDays(10)).totalSeats(5000).build());

            testSeatId1 = seatRepository.save(Seat.builder().schedule(schedule).seatNo(1).price(100000L).status(SeatStatus.AVAILABLE).build()).getId();
            testSeatId2 = seatRepository.save(Seat.builder().schedule(schedule).seatNo(2).price(100000L).status(SeatStatus.AVAILABLE).build()).getId();
            return null;
        });
    }

    @Test
    @DisplayName("동시 점유 정합성: 100명이 동시에 동일 좌석을 요청할 경우 단 1명만 선점에 성공해야 한다")
    void should_allow_only_one_user_when_concurrently_reserving_same_seat() throws Exception {
        // given: 100명의 유저 대기열 통과 처리
        int threadCount = 100;
        long expireScore = Instant.now().getEpochSecond() + 300;
        for (long i = 1; i <= threadCount; i++) {
            queueRedisRepository.enterQueue(String.valueOf(testConcertId), String.valueOf(i));
            queueRedisRepository.transitionQueue(String.valueOf(testConcertId), 1000, 1000, expireScore);
        }

        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        for (long i = 1; i <= threadCount; i++) {
            final long userId = i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    reservationService.reserveSeats(new ReservationRequest(userId, testConcertId, List.of(testSeatId1)));
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

        // then: 단 1명 성공, 99명 실패(예외) 검증
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        Seat resultSeat = seatRepository.findById(testSeatId1).orElseThrow();
        assertThat(resultSeat.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(resultSeat.getHoldUserId()).isNotNull();
    }

    @Test
    @DisplayName("연석 원자성 검증: 연석 예매 시도 중 하나라도 선점된 좌석이 포함되어 있다면 SEAT_ALREADY_HELD 예외가 발생하고 전체 롤백되어야 한다")
    void should_rollback_entirely_when_one_of_consecutive_seats_is_already_held() {
        // given: 2번 좌석을 다른 유저가 이미 선점한 상태로 세팅
        transactionTemplate.execute(status -> {
            seatRepository.holdSeatConditionally(testSeatId2, 999L, LocalDateTime.now().plusMinutes(5));
            return null;
        });

        queueRedisRepository.enterQueue(String.valueOf(testConcertId), String.valueOf(testUser.getId()));
        queueRedisRepository.transitionQueue(String.valueOf(testConcertId), 1000, 1000, Instant.now().getEpochSecond() + 300);

        // when & then: 1번(AVAILABLE)과 2번(HOLD)을 동시에 예매 시도 시 예외 발생 검증
        ReservationRequest request = new ReservationRequest(testUser.getId(), testConcertId, List.of(testSeatId1, testSeatId2));

        assertThatThrownBy(() -> reservationService.reserveSeats(request))
                .isInstanceOf(TurnstileException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SEAT_ALREADY_HELD); // 1단계 수정 반영

        // then: 롤백 검증 (1번 좌석은 여전히 AVAILABLE 유지)
        Seat resultSeat1 = seatRepository.findById(testSeatId1).orElseThrow();
        assertThat(resultSeat1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(resultSeat1.getHoldUserId()).isNull();
    }
}