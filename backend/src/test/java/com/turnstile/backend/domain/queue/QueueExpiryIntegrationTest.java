package com.turnstile.backend.domain.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.scheduler.QueueScheduler;
import com.turnstile.backend.domain.reservation.scheduler.ReservationCleanupScheduler;
import com.turnstile.backend.domain.reservation.scheduler.SeatHoldScheduler;
import com.turnstile.backend.support.IsolatedIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기준 시계(Clock) 기반의 대기열 활성 상태 만료 판정, 재진입 처리 및 스케줄러 정원 회수를 검증하는 통합 테스트입니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(90)
class QueueExpiryIntegrationTest extends IsolatedIntegrationTest {

    private static final String TRACKING = "queue:scheduler:active_concerts";

    @Autowired QueueRedisRepository queue;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean Clock clock;
    @MockitoBean QueueScheduler automaticQueueScheduler;
    @MockitoBean SeatHoldScheduler seatHoldScheduler;
    @MockitoBean ReservationCleanupScheduler reservationCleanupScheduler;

    private long now;
    private String concertId;
    private String activeKey;
    private String waitingKey;
    private String counterKey;
    private String capacityKey;

    @BeforeEach
    void prepare() {
        now = Instant.now().getEpochSecond();
        given(clock.instant()).willReturn(Instant.ofEpochSecond(now));

        concertId = String.valueOf(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
        );

        String prefix = "queue:concert:" + concertId;
        activeKey = prefix + ":active";
        waitingKey = prefix + ":waiting";
        counterKey = prefix + ":ticket_counter";
        capacityKey = prefix + ":capacity";
    }

    @AfterEach
    void cleanupOwnKeys() {
        if (activeKey != null) {
            redis.delete(List.of(
                    activeKey,
                    waitingKey,
                    counterKey,
                    capacityKey
            ));
            redis.opsForSet().remove(TRACKING, concertId);
        }
    }

    private String identityAuthorization() {
        return "Bearer " + jwtProvider.generateToken(
                1L,
                TokenType.IDENTITY
        );
    }

    private org.springframework.test.web.servlet.ResultActions
    getStatus() throws Exception {
        return mockMvc.perform(get("/api/v1/queue/status")
                .param("concertId", concertId)
                .header("Authorization", identityAuthorization()));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 120})
    @DisplayName("활성 유저 만료 시각 경계값(과거, 정각, 미래)에 따라 유효 여부와 토큰 발급이 정확히 분기되어야 한다")
    void should_validate_active_status_by_score_boundary(long offset) throws Exception {
        // given: 기준 시각 대비 offset만큼 차이나는 만료 시각(Score)으로 활성 ZSet 등록
        long expiry = now + offset;
        redis.opsForZSet().add(activeKey, "1", expiry);

        // when: 활성 상태 판별 및 상태 조회 API 호출
        assertThat(queue.isActiveUser(concertId, "1")).isEqualTo(offset > 0);

        var response = getStatus().andExpect(status().isOk());

        // then: 미래 시점일 때만 ACTIVE 및 QUEUE 토큰 발급, 만료 시점 이하는 INVALID 반환 및 물리 키 유지 검증
        if (offset > 0) {
            var result = response
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andReturn();

            String token = objectMapper.readTree(
                    result.getResponse().getContentAsString()
            ).path("accessToken").asText();

            var claims = jwtProvider.parseClaims(token);

            assertThat(claims.getExpiration().getTime()).isEqualTo(expiry * 1000);
            assertThat(claims.get("type", String.class)).isEqualTo("QUEUE");
        } else {
            response
                    .andExpect(jsonPath("$.status").value("INVALID"))
                    .andExpect(jsonPath("$.accessToken").doesNotHaveJsonPath());

            // 논리적 만료 판정만 수행하고 물리 삭제하지 않음
            assertThat(redis.opsForZSet().score(activeKey, "1")).isEqualTo((double) expiry);
        }
    }

    @Test
    @DisplayName("활성열과 대기열 모두에 존재하지 않는 사용자는 INVALID 상태를 반환해야 한다")
    void should_return_invalid_when_member_is_absent() throws Exception {
        // when & then: 미등록 사용자 상태 조회 시 200(INVALID) 응답 및 토큰 미발급 검증
        assertThat(queue.isActiveUser(concertId, "1")).isFalse();

        getStatus()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.accessToken").doesNotHaveJsonPath());
    }

    @Test
    @DisplayName("활성 상태가 만료된 사용자가 대기열에도 존재할 경우 WAITING 상태와 순위를 반환해야 한다")
    void should_fall_back_to_waiting_when_active_is_expired() throws Exception {
        // given: 만료된 활성 ZSet 멤버와 유효한 대기열 ZSet 멤버를 동시에 배치
        redis.opsForZSet().add(activeKey, "1", now - 1);
        redis.opsForZSet().add(waitingKey, "1", 7);

        // when & then: 상태 조회 시 만료된 활성 상태를 무시하고 WAITING 상태 및 대기 순번(0) 반환 검증
        getStatus()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(0))
                .andExpect(jsonPath("$.accessToken").doesNotHaveJsonPath());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    @DisplayName("활성 상태가 만료된 사용자가 대기열에 재진입하면 기존 만료 멤버를 제거하고 신규 대기 순번을 발급해야 한다")
    void should_rejoin_waiting_queue_when_active_entry_is_expired(long offset) {
        // given: 만료된 시각(과거 또는 현재 정각)의 활성 ZSet 멤버 등록
        redis.opsForZSet().add(activeKey, "1", now + offset);

        // when: 대기열 진입 실행
        Long ticket = queue.enterQueue(concertId, "1");

        // then: 양수 대기 순번 발급, 기존 만료 활성 키 제거 및 스케줄러 추적 대상 등록 검증
        assertThat(ticket).isPositive();
        assertThat(redis.opsForZSet().score(activeKey, "1")).isNull();
        assertThat(queue.getWaitingRank(concertId, "1")).isZero();
        assertThat(redis.opsForSet().isMember(TRACKING, concertId)).isTrue();
    }

    @Test
    @DisplayName("유효한 활성 상태를 유지 중인 사용자가 재진입하면 기존 만료 시각을 보존하고 -1을 반환해야 한다")
    void should_retain_expiry_and_restore_tracking_for_live_active_entry() {
        // given: 유효한 미래 만료 시각을 가진 활성 ZSet 멤버 등록
        redis.opsForZSet().add(activeKey, "1", now + 120);

        // when & then: 대기열 진입 시 -1 반환, 기존 만료 시각 유지 및 스케줄러 추적 대상 복원 검증
        assertThat(queue.enterQueue(concertId, "1")).isEqualTo(-1L);
        assertThat(redis.opsForZSet().score(activeKey, "1")).isEqualTo((double) (now + 120));
        assertThat(queue.getWaitingRank(concertId, "1")).isNull();
        assertThat(redis.opsForSet().isMember(TRACKING, concertId)).isTrue();
    }

    @Test
    @DisplayName("스케줄러 입장 처리 시 가용 정원 계산 전에 만료된 활성 유저를 먼저 제거하여 빈자리를 확보해야 한다")
    void should_remove_expired_users_before_counting_capacity_on_admission() {
        // given: 최대 정원 2명 중 1명은 만료, 1명은 유효 상태로 설정하고 신규 대기자 1명 진입
        redis.opsForZSet().add(activeKey, "expired", now);
        redis.opsForZSet().add(activeKey, "2", now + 120);
        redis.opsForValue().set(capacityKey, "2");

        queue.enterQueue(concertId, "3");

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        try {
            QueueScheduler scheduler = new QueueScheduler(
                    queue,
                    metrics,
                    clock
            );

            ReflectionTestUtils.setField(scheduler, "admissionBatchSize", 10L);
            ReflectionTestUtils.setField(scheduler, "expirationTime", 300L);

            // when: 스케줄러 입장 전이 로직 실행
            scheduler.scheduleAdmission();
        } finally {
            metrics.close();
        }

        // then: 만료 유저 제거, 기존 유효 유저 유지 및 신규 대기자의 활성열 승격 검증
        assertThat(redis.opsForZSet().score(activeKey, "expired")).isNull();
        assertThat(redis.opsForZSet().score(activeKey, "2")).isEqualTo((double) (now + 120));
        assertThat(redis.opsForZSet().score(activeKey, "3")).isEqualTo((double) (now + 300));
        assertThat(queue.getActiveUserCount(concertId)).isEqualTo(2);
        assertThat(queue.getWaitingRank(concertId, "3")).isNull();
    }

    @Test
    @DisplayName("만료된 유저 정리 후 대기열과 활성열이 모두 비면 스케줄러 추적에서 제외되고 신규 진입 시 재활성화되어야 한다")
    void should_cleanup_tracking_and_reactivate_on_new_entry() {
        // given: 만료된 활성 유저 1명만 존재하고 스케줄러 추적 셋에 콘서트가 등록된 상태
        redis.opsForZSet().add(activeKey, "expired", now);
        redis.opsForSet().add(TRACKING, concertId);

        // when: 대기열 전이 실행 시 만료 유저 정리 후 빈 콘서트 자동 정리(Self-Cleanup) 수행
        assertThat(queue.transitionQueue(concertId, 1, 1, now + 300)).isEmpty();

        // then: 스케줄러 추적 셋에서 제거됨을 검증
        assertThat(redis.opsForSet().isMember(TRACKING, concertId)).isFalse();

        // when: 신규 사용자 대기열 진입 후 재전이 실행
        queue.enterQueue(concertId, "1");

        // then: 스케줄러 추적 재활성화 및 신규 사용자 활성 상태 전이 검증
        assertThat(redis.opsForSet().isMember(TRACKING, concertId)).isTrue();
        assertThat(queue.transitionQueue(concertId, 1, 1, now + 300)).containsExactly("1");
        assertThat(queue.isActiveUser(concertId, "1")).isTrue();
    }

    @Test
    @DisplayName("만료 시각이 지난 QUEUE 토큰으로 보호된 API 요청 시 401 에러를 반환해야 한다")
    void should_return_401_when_queue_jwt_is_expired() throws Exception {
        // given: 기준 시각보다 1초 이전에 만료된 QUEUE 토큰 생성
        String token = jwtProvider.generateToken(
                1L,
                TokenType.QUEUE,
                Date.from(Instant.ofEpochSecond(now - 1))
        );

        // when & then: 만료된 QUEUE 토큰으로 좌석 조회 API 호출 시 401(A002) 응답 검증
        mockMvc.perform(get("/api/v1/schedules/1/seats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A002"));
    }
}