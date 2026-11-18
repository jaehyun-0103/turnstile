package com.turnstile.backend.global;

import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.turnstile.backend.support.ReservationPaymentTestSupport;

/**
 * 트랜잭션 커밋 이후 실행되는 후처리 이벤트(대기열 퇴장, 좌석 캐시 무효화)의 장애 격리 및 롤백 시 미실행 정책을 검증하는 통합 테스트입니다.
 */
class PostCommitEventIntegrationTest extends ReservationPaymentTestSupport {

    @Test
    @DisplayName("트랜잭션 커밋 후 Redis 후처리(대기열 제거, 캐시 무효화)가 실패하더라도 결제 상태는 정상 유지되고 재요청 시 이중 결제가 방지되어야 한다")
    void postCommitRedisFailuresKeepSuccessfulPayment() throws Exception {
        // given: 2개 좌석을 임시 점유한 상태 준비
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        // 예약 커밋 시 실행된 캐시 후처리 호출 기록 초기화
        clearInvocations(queue, cacheEvictor);

        double queueFailuresBefore = postCommitFailureCount("queue_remove");
        double cacheFailuresBefore = postCommitFailureCount("seat_cache_evict");

        // 실제 네트워크 장애 대신 후처리 경계에 지연 및 예외 주입
        doAnswer(invocation -> {
            Thread.sleep(200);
            throw new QueryTimeoutException("injected queue timeout");
        }).when(queue).removeActiveUser(anyString(), anyString());

        doThrow(new RedisConnectionFailureException(
                "injected cache connection failure"
        )).when(cacheEvictor).evictSeatCache(anyLong());

        PaymentRequest request = paymentRequest(reserved.groupId(), 2000L);

        String requestJson = objectMapper.writeValueAsString(request);

        // when: 결제 API 호출 및 응답 소요 시간 측정
        long started = System.nanoTime();

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk());

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        System.out.println("POST_COMMIT_HTTP_MS=" + elapsedMillis);

        // then: 프론트엔드 타임아웃(5초) 기준 응답 시간 검증
        assertThat(elapsedMillis).isLessThan(5000L);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(150L);

        // then: 결제 내역 생성, 잔액 차감, 예약 PAID 및 좌석 SOLD 상태 유지 검증
        assertThat(payments.count()).isEqualTo(1);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(8000L);
        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PAID);

        for (Long id : seatIds.subList(0, 2)) {
            assertThat(seats.findById(id).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        }

        // then: 후처리 실패 메트릭 증가 및 핸들러 호출 여부 검증
        assertThat(postCommitFailureCount("queue_remove")).isEqualTo(queueFailuresBefore + 1);
        assertThat(postCommitFailureCount("seat_cache_evict")).isEqualTo(cacheFailuresBefore + 1);

        verify(queue).removeActiveUser(
                String.valueOf(concert.getId()),
                String.valueOf(userA.getId())
        );

        verify(cacheEvictor).evictSeatCache(anyLong());

        // when & then: 응답 미수신으로 인한 사용자 재요청 가정 및 이중 차감 방지 검증
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest());

        assertThat(payments.count()).isEqualTo(1);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(8000L);

        // 재요청 시 후처리 핸들러 중복 미실행 검증
        verify(queue, times(1)).removeActiveUser(anyString(), anyString());
        verify(cacheEvictor, times(1)).evictSeatCache(anyLong());
    }

    @Test
    @DisplayName("결제 트랜잭션이 롤백될 경우 커밋 후처리 핸들러가 실행되지 않아야 한다")
    void rollbackDoesNotExecutePostCommitHandlers() throws Exception {
        // given: 2개 좌석을 임시 점유한 뒤 잔액을 0원으로 변경하여 결제 실패 조건 세팅
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        clearInvocations(queue, cacheEvictor);

        double queueFailuresBefore = postCommitFailureCount("queue_remove");
        double cacheFailuresBefore = postCommitFailureCount("seat_cache_evict");

        jdbc.update("UPDATE users SET balance = 0 WHERE id = ?", userA.getId());

        // when: 잔액 부족 상태에서 결제 API 호출
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isBadRequest());

        // then: 후처리 핸들러 미실행 및 실패 메트릭 불변 검증
        verify(queue, never()).removeActiveUser(anyString(), anyString());
        verify(cacheEvictor, never()).evictSeatCache(anyLong());

        assertThat(postCommitFailureCount("queue_remove")).isEqualTo(queueFailuresBefore);
        assertThat(postCommitFailureCount("seat_cache_evict")).isEqualTo(cacheFailuresBefore);

        // then: 결제 미생성 및 예약(PENDING), 잔액(0원), 좌석(HOLD) 상태 롤백 검증
        assertThat(payments.count()).isZero();
        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PENDING);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isZero();

        for (Long id : seatIds.subList(0, 2)) {
            assertThat(seats.findById(id).orElseThrow().getStatus()).isEqualTo(SeatStatus.HOLD);
        }
    }
}