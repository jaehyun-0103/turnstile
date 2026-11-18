package com.turnstile.backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.scheduler.ReservationCleanupScheduler;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.common.SeatCacheKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

/**
 * 실제 인프라 통합 테스트의 공통 초기 데이터 구성 및 격리 환경을 제공하는 추상 기반 클래스입니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class DatabaseIntegrationTest extends IsolatedIntegrationTest {
    // JVM 전체의 기본 타임존을 한국 시간으로 고정
    @BeforeAll
    static void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    @Autowired protected ReservationService reservationService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected UserRepository users;
    @Autowired protected ConcertRepository concerts;
    @Autowired protected ConcertScheduleRepository schedules;
    @Autowired protected SeatRepository seats;
    @Autowired protected ReservationRepository reservations;
    @Autowired protected PaymentRepository payments;
    @Autowired protected QueueRedisRepository queue;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected JwtProvider jwtProvider;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected MeterRegistry meterRegistry;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected ApplicationEventPublisher events;

    // 백그라운드 스케줄러 자동 실행을 차단하고 테스트 코드에서 명시적으로 호출하도록 모킹
    @MockitoBean protected QueueScheduler automaticQueueScheduler;
    @MockitoBean protected SeatHoldScheduler automaticSeatHoldScheduler;
    @MockitoBean protected ReservationCleanupScheduler automaticReservationCleanupScheduler;

    protected User userA;
    protected User userB;
    protected Concert concert;
    protected ConcertSchedule schedule;
    protected List<Long> seatIds;

    /**
     * 한국 표준시(Asia/Seoul) 기준의 현재 시각을 반환합니다.
     */
    protected static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    /**
     * 각 테스트 실행 전 격리된 DB 테이블을 초기화하고 기본 사용자·공연·좌석 데이터를 생성합니다.
     */
    @BeforeEach
    void prepareDatabase() {
        // 격리된 테스트 전용 MySQL 인스턴스의 기존 데이터 일괄 삭제
        payments.deleteAllInBatch();
        reservations.deleteAllInBatch();
        seats.deleteAllInBatch();
        schedules.deleteAllInBatch();
        concerts.deleteAllInBatch();
        users.deleteAllInBatch();

        userA = createUser(10000L);
        userB = createUser(10000L);
        concert = concerts.save(Concert.builder()
                .title("통합 테스트 공연")
                .ticketingStartAt(now().minusDays(1))
                .build());
        schedule = schedules.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(now().plusDays(1))
                .totalSeats(4)
                .build());
        seatIds = new ArrayList<>();
        for (int number = 1; number <= 4; number++) {
            seatIds.add(seats.save(Seat.builder()
                    .schedule(schedule)
                    .seatNo(number)
                    .price(1000L)
                    .status(SeatStatus.AVAILABLE)
                    .build()).getId());
        }

        // 스키마 재생성으로 ID가 재시작될 때 이전 테스트 클래스의 잔여 Redis 캐시 오염 방지
        clearOwnRedisKeys();
    }

    /**
     * 지정된 초기 잔액을 가진 테스트용 사용자를 생성하여 저장합니다.
     */
    protected User createUser(long balance) {
        return users.save(User.builder()
                .username("test-" + UUID.randomUUID())
                .name("통합 테스트 사용자")
                .balance(balance)
                .build());
    }

    /**
     * 현재 테스트 공연에 종속된 대기열 Redis 키를 생성합니다.
     */
    protected String queueKey(String suffix) {
        return "queue:concert:" + concert.getId() + ":" + suffix;
    }

    /**
     * 특정 사용자를 현재 공연의 활성 대기열(ACTIVE) 상태로 등록합니다.
     */
    protected void activate(User user) {
        redis.opsForZSet().add(queueKey("active"), String.valueOf(user.getId()), Instant.now().getEpochSecond() + 300);
        redis.opsForSet().add("queue:scheduler:active_concerts", String.valueOf(concert.getId()));
    }

    /**
     * 사용자를 활성 대기열에 등록한 뒤 지정된 좌석들에 대한 임시 점유(예약)를 수행합니다.
     */
    protected ReservationResponse reserve(User user, List<Long> selected) {
        activate(user);
        return reservationService.reserveSeats(user.getId(), new ReservationRequest(concert.getId(), selected));
    }

    /**
     * 각 테스트 종료 후 현재 테스트 공연과 연관된 Redis 대기열 및 캐시 키를 정리합니다.
     */
    @AfterEach
    void clearOwnRedisKeys() {
        if (concert == null) return;
        redis.delete(List.of(queueKey("active"), queueKey("waiting"),
                queueKey("ticket_counter"), queueKey("capacity"),
                SeatCacheKeys.seats(schedule.getId()), SeatCacheKeys.lock(schedule.getId()),
                "concert:info:" + concert.getId(), "concert:info:all",
                "concert:schedules:" + concert.getId()));
        redis.opsForSet().remove("queue:scheduler:active_concerts", String.valueOf(concert.getId()));
    }
}