package com.turnstile.backend.support;

import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import io.micrometer.core.instrument.Counter;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import com.turnstile.backend.support.ConcurrentTestSupport;

/**
 * 예약 및 결제 통합 테스트의 공통 모킹 설정과 동시성 경합·롤백 검증 헬퍼를 제공하는 추상 기반 클래스입니다.
 */
@SpringBootTest(properties = {
        "reservation.policy.hold-time-seconds=120",
        "reservation.policy.payment-ttl-seconds=120"
})
public abstract class ReservationPaymentTestSupport extends DatabaseIntegrationTest {
    // DB 정합성 및 HTTP 검증에 집중하고 후처리 장애를 명시적으로 주입하기 위한 모킹 빈
    @MockitoBean protected CacheEvictor cacheEvictor;
    @MockitoBean private QueueRedisRepository queueOverride;

    /**
     * 모든 테스트 요청이 대기열 활성 상태(ACTIVE)를 통과하도록 기본 모킹을 설정합니다.
     */
    @BeforeEach
    void allowActiveUser() {
        given(queue.isActiveUser(anyString(), anyString())).willReturn(true);
    }

    /**
     * 현재 테스트 공연 기준의 결제 요청 DTO를 생성합니다.
     */
    protected PaymentRequest paymentRequest(String groupId, long amount) {
        return new PaymentRequest(groupId, amount, concert.getId());
    }

    /**
     * 두 작업을 동시에 실행하여 각 작업의 반환값 또는 발생한 비즈니스 예외 코드를 수집합니다.
     */
    protected List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        return ConcurrentTestSupport.run(2, index -> {
            try {
                return (index == 0 ? first : second).call();
            } catch (TurnstileException e) {
                return e.getErrorCode();
            }
        });
    }

    /**
     * 결제 실패 시 예상 예외 발생과 함께 결제 내역·잔액·예약 및 좌석 상태가 모두 롤백되었는지 검증합니다.
     */
    protected void assertPaymentRollback(
            ReservationResponse reserved,
            ErrorCode expected,
            long expectedBalance
    ) {
        TurnstileException error = assertThrows(
                TurnstileException.class,
                () -> paymentService.processPayment(
                        userA.getId(),
                        paymentRequest(reserved.groupId(), 2000L)
                )
        );

        assertThat(error.getErrorCode()).isEqualTo(expected);
        assertThat(payments.count()).isZero();
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(expectedBalance);
        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PENDING);

        for (Long id : seatIds.subList(0, 2)) {
            assertThat(seats.findById(id).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.HOLD);
        }
    }

    /**
     * 지정된 작업 유형에 대해 메트릭에 기록된 커밋 후 후처리 실패 횟수를 조회합니다.
     */
    protected double postCommitFailureCount(String operation) {
        Counter counter = meterRegistry
                .find("turnstile.post_commit.failures")
                .tag("operation", operation)
                .counter();

        return counter == null ? 0 : counter.count();
    }
}