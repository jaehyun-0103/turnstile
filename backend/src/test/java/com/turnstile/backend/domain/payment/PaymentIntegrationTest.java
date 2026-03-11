package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.entity.PaymentStatus;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private QueueRedisRepository queueRedisRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private RedisTemplate<String, String> redisTemplate; // RedisTemplate 추가

    private User testUser;
    private Long testConcertId;
    private Long testSeatId;
    private final String testGroupId = "req-uuid-1234";

    @BeforeEach
    void setUp() {
        // 테스트 간 캐시 및 대기열 데이터 오염 방지를 위해 Redis 전체 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 테스트 간 데이터 오염 방지 및 완벽한 독립성 보장을 위해 연관된 DB 테이블 모두 초기화
        transactionTemplate.execute(status -> {
            reservationRepository.deleteAllInBatch();
            seatRepository.deleteAllInBatch();
            concertScheduleRepository.deleteAllInBatch();
            concertRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();

            // given: 초기 잔액 200,000원을 가진 테스트 유저 생성
            testUser = userRepository.save(User.builder()
                    .username("test")
                    .name("테스터")
                    .balance(200000L)
                    .build());

            // given: 결제 대상이 될 콘서트 및 스케줄 세팅
            Concert concert = concertRepository.save(Concert.builder()
                    .title("아이유 콘서트")
                    .ticketingStartAt(LocalDateTime.now().minusDays(1))
                    .build());
            testConcertId = concert.getId();

            ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder()
                    .concert(concert)
                    .concertDate(LocalDateTime.now().plusDays(10))
                    .totalSeats(100)
                    .build());

            // given: 테스트 유저가 임시 점유(HOLD)한 150,000원짜리 좌석 생성
            Seat seat = seatRepository.save(Seat.builder()
                    .schedule(schedule).seatNo(1).price(150000L).status(SeatStatus.HOLD)
                    .holdUserId(testUser.getId()).holdExpiresAt(LocalDateTime.now().plusMinutes(5))
                    .build());
            testSeatId = seat.getId();

            // given: 점유 좌석과 매핑된 결제 대기(PENDING) 상태의 예약 건 생성
            reservationRepository.save(Reservation.builder()
                    .groupId(testGroupId).user(testUser).seat(seat).status(ReservationStatus.PENDING)
                    .price(150000L).reservedAt(LocalDateTime.now())
                    .build());

            return null;
        });

        // given: 결제 진입을 위한 Redis 대기열 권한(활성 상태) 부여 세팅
        queueRedisRepository.enterQueue(String.valueOf(testConcertId), String.valueOf(testUser.getId()));
        queueRedisRepository.transitionQueue(String.valueOf(testConcertId), 1000, 1000, Instant.now().getEpochSecond() + 300);
    }

    @Test
    @DisplayName("원자적 잔액 차감: 잔액이 충분할 경우 차감에 성공하고 남은 잔액 반환")
    void deductBalanceSuccessTest() {
        // given: 200,000원의 잔액을 가진 유저 환경 세팅 (setUp에서 처리)
        Long deductAmount = 150000L;

        // when: 보유 잔액 내에서 150,000원 차감 시도
        Long remainingBalance = userService.deductBalance(testUser.getId(), deductAmount);

        // then: 반환된 남은 잔액이 정확히 50,000원인지 확인
        assertThat(remainingBalance).isEqualTo(50000L);

        // then: 실제 DB의 유저 잔액도 50,000원으로 정상 업데이트 되었는지 확인
        User resultUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(resultUser.getBalance()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("원자적 잔액 차감: 잔액이 부족할 경우 예외가 발생하고 잔액은 차감 없이 유지")
    void deductBalanceFailTest() {
        // given: 200,000원의 잔액을 가진 유저 환경 세팅 (setUp에서 처리)
        Long deductAmount = 210000L;

        // when & then: 검증
        // 보유 잔액보다 큰 금액(210,000원) 차감 시도 시 IllegalArgumentException 예외 발생 및 메시지 확인
        assertThatThrownBy(() -> userService.deductBalance(testUser.getId(), deductAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잔액이 부족합니다.");

        // 예외 발생 시 트랜잭션 롤백으로 기존 잔액(200,000원)이 깎이지 않고 그대로 유지
        User resultUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(resultUser.getBalance()).isEqualTo(200000L);
    }

    @Test
    @DisplayName("결제 최종 확정: 명세 조건을 모두 만족하면 상태가 전이되고 차감 후 남은 잔액 반환")
    void processPaymentSuccessTest() {
        // given: 200,000원 보유 유저가 150,000원짜리 점유 좌석에 대해 결제 요청 객체 생성
        PaymentRequest request = new PaymentRequest(testUser.getId(), testGroupId, 150000L);

        // when: 정상적인 결제 처리 요청
        PaymentResponse response = paymentService.processPayment(request);

        // then: 응답 객체의 결제 상태가 PAID로 변경되고, 차감 후 잔액이 50,000원으로 반환
        assertThat(response.status()).isEqualTo(PaymentStatus.PAID.name());
        assertThat(response.balance()).isEqualTo(50000L);

        // then: 결제 완료 후 대상 좌석의 상태가 판매 완료(SOLD)로 올바르게 변경
        Seat resultSeat = seatRepository.findById(testSeatId).orElseThrow();
        assertThat(resultSeat.getStatus()).isEqualTo(SeatStatus.SOLD);

        // then: 결제 완료 후 예약 건의 상태가 결제 완료(PAID)로 올바르게 변경
        Reservation resultReservation = reservationRepository.findAllByGroupId(testGroupId).get(0);
        assertThat(resultReservation.getStatus()).isEqualTo(ReservationStatus.PAID);
    }

    @Test
    @DisplayName("결제 검증 실패: 금액 불일치, 잔액 부족, 권한 만료 등 예외 상황 시 결제가 거부되어야 한다.")
    void paymentValidationFailTests() {
        // given: 결제 요청 금액이 실제 예약 금액과 불일치하는 상황 세팅 (예약은 15만원, 요청은 1만원)
        PaymentRequest wrongAmountRequest = new PaymentRequest(testUser.getId(), testGroupId, 10000L);

        // when & then: 금액 불일치 검증
        // 결제 요청 시 IllegalArgumentException 발생 및 에러 메시지 확인
        assertThatThrownBy(() -> paymentService.processPayment(wrongAmountRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일치하지 않습니다");

        // given: 유저의 잔액이 부족한 상황 세팅 (DB 잔액을 강제로 0원으로 차감)
        transactionTemplate.execute(s -> {
            userRepository.deductBalance(testUser.getId(), 200000L);
            return null;
        });
        PaymentRequest exactAmountRequest = new PaymentRequest(testUser.getId(), testGroupId, 150000L);

        // when & then: 잔액 부족 검증
        // 결제 요청 시 잔액 부족으로 인한 IllegalArgumentException 발생 및 에러 메시지 확인
        assertThatThrownBy(() -> paymentService.processPayment(exactAmountRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잔액이 부족합니다");

        // given: 대기열 권한(활성 상태)이 없는 엉뚱한 유저 세팅
        User unauthorizedUser = transactionTemplate.execute(s ->
                userRepository.save(User.builder().username("hacker").name("해커").balance(500000L).build()));
        PaymentRequest unauthorizedRequest = new PaymentRequest(unauthorizedUser.getId(), testGroupId, 150000L);

        // when & then: 대기열 권한 만료 검증
        // 결제 요청 시 권한 없음으로 인한 IllegalStateException 발생 및 에러 메시지 확인
        assertThatThrownBy(() -> paymentService.processPayment(unauthorizedRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("권한(대기열)이 만료되었습니다");
    }

    @Test
    @DisplayName("이중 결제 방어: 동일한 groupId로 결제를 두 번 시도하면 두 번째 요청은 상태 기반(CAS) 로직에 의해 차단")
    void doublePaymentPreventionTest() {
        // given: 200,000원 보유 유저가 150,000원 결제를 요청하는 환경 세팅
        PaymentRequest request = new PaymentRequest(testUser.getId(), testGroupId, 150000L);

        // when: 1차 결제 시도 (정상 성공)
        paymentService.processPayment(request);

        // then: 2차 결제 시도 검증 (연타 상황)
        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 결제가 완료되었거나 만료된 예약입니다");

        // then: 예외처리로 유저의 잔액이 이중 차감 없이 딱 1번(50,000원 남음)만 차감되었는지 확인
        User resultUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(resultUser.getBalance()).isEqualTo(50000L);
    }
}