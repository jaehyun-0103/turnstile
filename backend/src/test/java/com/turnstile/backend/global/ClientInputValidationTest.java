package com.turnstile.backend.global;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.domain.concert.controller.ConcertController;
import com.turnstile.backend.domain.concert.service.ConcertService;
import com.turnstile.backend.domain.queue.controller.QueueController;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.domain.reservation.controller.ReservationController;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.global.config.WebConfig;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.GlobalExceptionHandler;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 클라이언트 입력값 유효성 검증, 요청 바인딩 예외 처리 및 전역 예외 응답 규격을 검증하는 WebMvc 테스트입니다.
 */
@WebMvcTest(
        controllers = {
                ReservationController.class,
                ConcertController.class,
                QueueController.class
        },
        properties = {
                "jwt.secret=jwt-mvc-test-signing-key-only-2026"
        }
)
@Import({
        WebConfig.class,
        JwtProvider.class,
        AuthInterceptor.class,
        LoginUserArgumentResolver.class,
        GlobalExceptionHandler.class
})
class ClientInputValidationTest {

    private static final String VALID_BODY = "{\"concertId\":1,\"seatIds\":[1]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    @MockitoBean private ReservationService reservationService;
    @MockitoBean private ConcertService concertService;
    @MockitoBean private QueueService queueService;

    private String authorization(TokenType type) {
        return "Bearer " + jwtProvider.generateToken(1L, type);
    }

    private ReservationRequest validRequest() {
        return new ReservationRequest(1L, List.of(1L));
    }

    private void assertBadInput(MockHttpServletRequestBuilder request) throws Exception {
        // 잘못된 입력 요청 시 400(C001) 응답, 내부 예외 클래스명 미노출 및 서비스 레이어 미호출 검증
        var result = mockMvc.perform(
                        request.header(
                                "Authorization",
                                authorization(TokenType.QUEUE)
                        )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "HttpMessageNotReadableException",
                        "MethodArgumentTypeMismatchException",
                        "MissingServletRequestParameterException",
                        "java.lang."
                );

        verifyNoInteractions(
                reservationService,
                concertService,
                queueService
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"concertId\":1,\"seatIds\":[1,null]}",
            "{\"concertId\":1,\"seatIds\":[0]}",
            "{\"concertId\":1,\"seatIds\":[-1]}",
            "{\"concertId\":1,\"seatIds\":[]}",
            "{\"concertId\":1,\"seatIds\":null}"
    })
    @DisplayName("유효하지 않은 좌석 ID 목록이 포함된 예약 요청은 서비스 호출 전에 400 에러로 거부해야 한다")
    void should_reject_invalid_seat_ids_before_service(String body) throws Exception {
        // when & then: 잘못된 좌석 ID 배열·요소 요청 시 400(C001) 응답 및 서비스 미호출 검증
        assertBadInput(
                post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{",
            "{\"concertId\":1,\"seatIds\":[\"abc\"]}"
    })
    @DisplayName("파싱할 수 없는 JSON 형식이나 타입 불일치 본문 요청은 400 에러를 반환해야 한다")
    void should_return_400_on_unreadable_json(String body) throws Exception {
        // when & then: 손상된 JSON 또는 타입 불일치 본문 전송 시 400(C001) 응답 검증
        assertBadInput(
                post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    @Test
    @DisplayName("필수 요청 본문이 누락된 예약 요청은 400 에러를 반환해야 한다")
    void should_return_400_when_request_body_is_missing() throws Exception {
        // when & then: 본문 없이 POST 예약 API 호출 시 400(C001) 응답 검증
        assertBadInput(
                post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/schedules/abc/seats",
            "/api/v1/queue/status?concertId=abc",
            "/api/v1/queue/status"
    })
    @DisplayName("경로 변수나 쿼리 파라미터의 타입이 불일치하거나 누락된 경우 400 에러를 반환해야 한다")
    void should_return_400_on_invalid_or_missing_parameters(String url) throws Exception {
        // when & then: 잘못된 경로 변수 또는 누락된 필수 쿼리 파라미터 요청 시 400(C001) 응답 검증
        assertBadInput(get(url));
    }

    @Test
    @DisplayName("유효한 입력과 권한 토큰을 가진 예약 요청은 정상적으로 서비스에 전달되어야 한다")
    void should_pass_valid_reservation_request_to_service() throws Exception {
        // given: 유효한 예약 요청에 대한 서비스 정상 응답 모킹
        given(reservationService.reserveSeats(1L, validRequest()))
                .willReturn(new ReservationResponse(
                        "test-group",
                        "PENDING",
                        LocalDateTime.of(2030, 1, 1, 12, 0),
                        "test-payment-token"
                ));

        // when & then: QUEUE 토큰과 유효한 요청 본문 전송 시 200 응답 및 서비스 호출 검증
        mockMvc.perform(post("/api/v1/reservations")
                        .header(
                                "Authorization",
                                authorization(TokenType.QUEUE)
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value("test-group"));

        verify(reservationService).reserveSeats(1L, validRequest());
    }

    @Test
    @DisplayName("인증 토큰이 누락된 요청은 기존과 동일하게 401 에러를 반환해야 한다")
    void should_return_401_when_token_is_missing() throws Exception {
        // when & then: Authorization 헤더 없이 예약 API 호출 시 401(A001) 응답 및 서비스 미호출 검증
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"));

        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("권한 수준이 부족한 토큰으로 요청 시 기존과 동일하게 403 에러를 반환해야 한다")
    void should_return_403_when_permission_is_insufficient() throws Exception {
        // when & then: QUEUE가 아닌 IDENTITY 토큰으로 예약 API 호출 시 403(A003) 응답 및 서비스 미호출 검증
        mockMvc.perform(post("/api/v1/reservations")
                        .header(
                                "Authorization",
                                authorization(TokenType.IDENTITY)
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A003"));

        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("도메인 비즈니스 충돌 예외 발생 시 기존과 동일하게 409 에러를 반환해야 한다")
    void should_return_409_on_business_conflict() throws Exception {
        // given: 좌석 이미 점유됨(SEAT_ALREADY_HELD) 예외 발생 모킹
        given(reservationService.reserveSeats(1L, validRequest()))
                .willThrow(new TurnstileException(
                        ErrorCode.SEAT_ALREADY_HELD
                ));

        // when & then: 예약 API 호출 시 409(S004) 응답 검증
        mockMvc.perform(post("/api/v1/reservations")
                        .header(
                                "Authorization",
                                authorization(TokenType.QUEUE)
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("S004"));
    }

    @Test
    @DisplayName("예기치 않은 서버 내부 오류 발생 시 내부 상세 정보를 노출하지 않고 500 에러를 반환해야 한다")
    void should_return_500_without_internal_detail_on_unexpected_error() throws Exception {
        // given: 내부 상세 메시지가 포함된 런타임 예외 발생 모킹
        given(reservationService.reserveSeats(1L, validRequest()))
                .willThrow(new NullPointerException(
                        "INTERNAL_ERROR_DETAIL"
                ));

        // when & then: 예약 API 호출 시 500(C002) 표준 메시지 반환 및 내부 예외 상세 미노출 검증
        var result = mockMvc.perform(post("/api/v1/reservations")
                        .header(
                                "Authorization",
                                authorization(TokenType.QUEUE)
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message")
                        .value("서버 내부 오류가 발생했습니다."))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "INTERNAL_ERROR_DETAIL",
                        "NullPointerException"
                );
    }
}