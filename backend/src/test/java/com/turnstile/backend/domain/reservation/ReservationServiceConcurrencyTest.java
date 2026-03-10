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
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
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

@SpringBootTest
class ReservationServiceConcurrencyTest {

    @Autowired
    private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private QueueRedisRepository queueRedisRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SeatHoldScheduler seatHoldScheduler;

    private Long testConcertId;
    private Long testScheduleId;
    private Long testSeatId1;
    private Long testSeatId2;
    private Long testSeatId3;
    private User testUser;

    @BeforeEach
    void setUp() {
        // 테스트 격리를 위해 트랜잭션 외부에서 DB 전체 초기화 및 기초 데이터 세팅
        transactionTemplate.execute(status -> {
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            concertScheduleRepository.deleteAllInBatch();
            concertRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();

            testUser = userRepository.save(User.builder().username("test_user").name("test").balance(0L).build());

            Concert concert = concertRepository.save(Concert.builder()
                    .title("아이유 콘서트")
                    .ticketingStartAt(LocalDateTime.now().minusDays(1))
                    .build());
            testConcertId = concert.getId();

            ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder()
                    .concert(concert)
                    .concertDate(LocalDateTime.now().plusDays(10))
                    .totalSeats(5000)
                    .build());
            testScheduleId = schedule.getId();

            // 테스트용 좌석 3개 생성 (AVAILABLE)
            testSeatId1 = seatRepository.save(Seat.builder().schedule(schedule).seatNo(1).price(100000L).status(SeatStatus.AVAILABLE).build()).getId();
            testSeatId2 = seatRepository.save(Seat.builder().schedule(schedule).seatNo(2).price(100000L).status(SeatStatus.AVAILABLE).build()).getId();
            testSeatId3 = seatRepository.save(Seat.builder().schedule(schedule).seatNo(3).price(100000L).status(SeatStatus.AVAILABLE).build()).getId();

            return null;
        });
    }

    @Test
    @DisplayName("동시 점유 정합성: 100명이 동시에 동일 좌석 요청 시 단 1명만 성공")
    void concurrentSeatReservationTest() throws Exception {
        // given: 100명의 유저를 대기열 화이트리스트에 세팅
        int threadCount = 100;
        long expireScore = Instant.now().getEpochSecond() + 300;
        for (long i = 1; i <= threadCount; i++) {
            queueRedisRepository.enterQueue(String.valueOf(testConcertId), String.valueOf(i));
            queueRedisRepository.transitionQueue(String.valueOf(testConcertId), 1000, 1000, expireScore);
        }

        // 가상 스레드 및 동시성 제어를 위한 래치 준비
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 100명의 유저가 일제히 1번 좌석 선점 시도
        for (long i = 1; i <= threadCount; i++) {
            final long userId = i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // 시작 신호 대기

                    ReservationRequest request = new ReservationRequest(userId, testConcertId, List.of(testSeatId1));
                    reservationService.reserveSeats(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 동시 공격 시작
        doneLatch.await();
        executorService.shutdown();

        // then: 결과 검증
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        // DB 상태 검증: 좌석이 HOLD 상태여야 하며 점유 유저 ID가 존재
        Seat resultSeat = seatRepository.findById(testSeatId1).orElseThrow();
        assertThat(resultSeat.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(resultSeat.getHoldUserId()).isNotNull();
    }

    @Test
    @DisplayName("연석 원자성 검증: 연석 예매 중 하나라도 선점된 경우 전체 롤백")
    void atomicRollbackTest() {
        // given: 3번 좌석을 다른 유저가 이미 선점한 상태로 세팅
        transactionTemplate.execute(status -> {
            seatRepository.holdSeatConditionally(testSeatId3, 999L, LocalDateTime.now().plusMinutes(5));
            return null;
        });

        // 테스트 유저 권한 부여
        queueRedisRepository.enterQueue(String.valueOf(testConcertId), String.valueOf(testUser.getId()));
        queueRedisRepository.transitionQueue(String.valueOf(testConcertId), 1000, 1000, Instant.now().getEpochSecond() + 300);

        // when: 테스트 유저가 2번(AVAILABLE)과 3번(HOLD)을 동시에 예매 시도
        ReservationRequest request = new ReservationRequest(testUser.getId(), testConcertId, List.of(testSeatId2, testSeatId3));

        // then: 이미 선점된 좌석이 포함되어 예외가 발생
        assertThatThrownBy(() -> reservationService.reserveSeats(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 선점된 좌석");

        // then: 트랜잭션 롤백으로 인해 2번 좌석은 여전히 AVAILABLE 상태
        Seat resultSeat2 = seatRepository.findById(testSeatId2).orElseThrow();
        assertThat(resultSeat2.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(resultSeat2.getHoldUserId()).isNull();
    }

    @Test
    @DisplayName("자원 회수 로직: 만료 시간이 지난 좌석과 예약은 스케줄러에 의해 각각 AVAILABLE과 EXPIRED로 정상 복구")
    void schedulerRecoveryTest() {
        // given: 5분이 지난 만료된 점유 좌석과 PENDING 상태의 예약 데이터 셋업
        final String groupId = "test-expired-group";

        transactionTemplate.execute(status -> {
            Seat seat = seatRepository.findById(testSeatId1).orElseThrow();

            // 현재 시간보다 1분 전으로 점유 만료 시간 강제 설정
            LocalDateTime pastExpiredTime = LocalDateTime.now().minusMinutes(1);
            seatRepository.holdSeatConditionally(seat.getId(), testUser.getId(), pastExpiredTime);

            // 해당 좌석을 점유 중인 PENDING 상태의 예약 생성 (6분 전 생성)
            reservationRepository.save(Reservation.builder()
                    .groupId(groupId)
                    .user(testUser)
                    .seat(seat)
                    .status(com.turnstile.backend.domain.reservation.entity.ReservationStatus.PENDING)
                    .price(seat.getPrice())
                    .reservedAt(LocalDateTime.now().minusMinutes(6))
                    .build());
            return null;
        });

        // when: 만료 자원 회수 스케줄러 강제 실행
        seatHoldScheduler.recoverExpiredSeats();

        // then: 좌석 상태가 AVAILABLE로 복구되고 점유 정보가 초기화되었는지 검증
        Seat recoveredSeat = seatRepository.findById(testSeatId1).orElseThrow();
        assertThat(recoveredSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(recoveredSeat.getHoldUserId()).isNull();
        assertThat(recoveredSeat.getHoldExpiresAt()).isNull();

        // then: 연관된 예약 건이 EXPIRED(만료) 처리되었는지 검증
        List<Reservation> reservations = reservationRepository.findAllByGroupId(groupId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus())
                .isEqualTo(com.turnstile.backend.domain.reservation.entity.ReservationStatus.EXPIRED);
    }
}