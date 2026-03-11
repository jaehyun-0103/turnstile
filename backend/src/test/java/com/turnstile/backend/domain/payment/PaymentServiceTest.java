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
import com.turnstile.backend.global.common.CacheEvictor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional // 테스트 종료 시 자동 롤백
class PaymentServiceTest {

    @Autowired private PaymentService paymentService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;

    @MockitoBean private QueueRedisRepository queueRedisRepository;
    @MockitoBean private CacheEvictor cacheEvictor;

    private User savedUser;
    private Concert savedConcert;
    private final String testGroupId = "req-group-1234";
    private final long seatPrice = 150000L;

    @BeforeEach
    void setUp() {
        // given: 초기 잔액 500,000원을 가진 테스트 유저 생성
        savedUser = userRepository.save(User.builder()
                .username("payment_test_user")
                .name("결제테스터")
                .balance(500000L)
                .build());

        // given: 결제 대상이 될 콘서트 및 회차(스케줄) 정보 세팅
        savedConcert = concertRepository.save(Concert.builder()
                .title("결제 테스트 콘서트")
                .ticketingStartAt(LocalDateTime.now().minusDays(1))
                .build());

        ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.builder()
                .concert(savedConcert)
                .concertDate(LocalDateTime.now().plusDays(5))
                .totalSeats(100)
                .build());

        // given: 테스트 유저가 임시 점유(HOLD)한 150,000원짜리 좌석 2개(연석) 세팅
        Seat seat1 = seatRepository.save(Seat.builder()
                .schedule(schedule).seatNo(1).price(seatPrice).status(SeatStatus.HOLD)
                .holdUserId(savedUser.getId()).holdExpiresAt(LocalDateTime.now().plusMinutes(5))
                .build());

        Seat seat2 = seatRepository.save(Seat.builder()
                .schedule(schedule).seatNo(2).price(seatPrice).status(SeatStatus.HOLD)
                .holdUserId(savedUser.getId()).holdExpiresAt(LocalDateTime.now().plusMinutes(5))
                .build());

        // given: 점유 좌석들과 매핑된 결제 대기(PENDING) 상태의 예약 건 2개 세팅
        reservationRepository.save(Reservation.builder()
                .groupId(testGroupId).user(savedUser).seat(seat1)
                .status(ReservationStatus.PENDING).price(seatPrice).reservedAt(LocalDateTime.now())
                .build());

        reservationRepository.save(Reservation.builder()
                .groupId(testGroupId).user(savedUser).seat(seat2)
                .status(ReservationStatus.PENDING).price(seatPrice).reservedAt(LocalDateTime.now())
                .build());

        // given: 원활한 비즈니스 로직(결제) 테스트를 위해 Redis 대기열 인가 통과 및 캐시 무효화 호출 무시(Mock) 처리
        when(queueRedisRepository.isActiveUser(anyString(), anyString())).thenReturn(true);
        doNothing().when(cacheEvictor).evictSeatCache(any());
    }

    @Test
    @DisplayName("중복 쿼리 제거 후: 정상 결제 시 잔액 차감 및 상태가 원자적으로 확정")
    void processPayment_Success() {
        // given: 500,000원 보유 유저가 총 300,000원(15만*2) 결제를 요청하는 환경 세팅
        long totalAmount = seatPrice * 2; // 300,000원
        PaymentRequest request = new PaymentRequest(savedUser.getId(), testGroupId, totalAmount);

        // when: 정상적인 결제 처리 요청
        PaymentResponse response = paymentService.processPayment(request);

        // then: 검증
        // 응답 객체의 결제 상태가 PAID로 변경되고, 차감 후 잔액이 200,000원으로 반환
        assertThat(response.status()).isEqualTo(PaymentStatus.PAID.name());
        assertThat(response.balance()).isEqualTo(200000L); // 50만 - 30만

        // DB의 모든 해당 그룹 예약 건 상태가 결제 완료(PAID)로 올바르게 변경
        List<Reservation> reservations = reservationRepository.findAllByGroupId(testGroupId);
        assertThat(reservations).allMatch(r -> r.getStatus() == ReservationStatus.PAID);

        // 결제 완료 후 대상 좌석들의 상태가 모두 판매 완료(SOLD)로 올바르게 변경
        List<Seat> seats = seatRepository.findAllById(reservations.stream().map(r -> r.getSeat().getId()).toList());
        assertThat(seats).allMatch(s -> s.getStatus() == SeatStatus.SOLD);
    }

    @Test
    @DisplayName("이중 결제 방어: 중복 결제 요청 시 CAS 로직(PENDING 검증)에 의해 예외 발생")
    void processPayment_DoublePaymentPrevention() {
        // given: 500,000원 보유 유저가 총 300,000원 결제를 요청하는 환경 세팅 (setUp에서 처리)
        long totalAmount = seatPrice * 2;
        PaymentRequest request = new PaymentRequest(savedUser.getId(), testGroupId, totalAmount);

        // when: 1차 결제 시도 (정상 처리되어 PENDING -> PAID 상태로 변경됨)
        paymentService.processPayment(request);

        // then: 2차 결제 시도
        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 결제가 완료되었거나 만료된 예약입니다.");
    }
}