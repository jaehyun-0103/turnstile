package com.turnstile.backend.domain.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.domain.queue.controller.QueueController;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대기열 도메인의 컨트롤러 엔드포인트 동작과 응답 규격을 검증하는 테스트입니다.
 */
@WebMvcTest(QueueController.class)
class QueueControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private QueueService queueService;
    @MockitoBean private AuthInterceptor authInterceptor;
    @MockitoBean private LoginUserArgumentResolver loginUserArgumentResolver;

    @BeforeEach
    void setUp() throws Exception {
        // 인터셉터 우회 모킹
        given(authInterceptor.preHandle(any(), any(), any())).willReturn(true);
        given(loginUserArgumentResolver.supportsParameter(any())).willReturn(true);
        given(loginUserArgumentResolver.resolveArgument(any(), any(), any(), any())).willReturn(100L);
    }

    @Test
    @DisplayName("대기열 진입 API 요청 시 정상적으로 진입 상태와 순번을 반환해야 한다")
    void should_return_queue_entry_status_on_post_request() throws Exception {
        // given: 진입 응답 모킹
        QueueEntryRequest request = new QueueEntryRequest(1L);
        QueueEntryResponse response = new QueueEntryResponse(1L, 5012L, "WAITING");
        given(queueService.enterQueue(anyLong(), any(QueueEntryRequest.class))).willReturn(response);

        // when & then: JSON 포맷 규격 검증
        mockMvc.perform(post("/api/v1/queue/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(1L))
                .andExpect(jsonPath("$.rank").value(5012L))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("대기열 상태 폴링 API 요청 시 정상적으로 상태와 순번/토큰을 반환해야 한다")
    void should_return_queue_status_and_token_on_get_request() throws Exception {
        // given: 폴링 응답 모킹
        QueueStatusResponse response = new QueueStatusResponse(1L, 0L, "ACTIVE", "mock-token");
        given(queueService.getQueueStatus(anyLong(), anyLong())).willReturn(response);

        // when & then: 파라미터 맵핑 및 JSON 포맷 규격 검증
        mockMvc.perform(get("/api/v1/queue/status")
                        .param("concertId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(1L))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rank").value(0L))
                .andExpect(jsonPath("$.accessToken").value("mock-token"));
    }

    @Test
    @DisplayName("대기열 진입 시 concertId가 null이면 C001 에러를 반환해야 한다")
    void should_return_C001_when_concertId_is_null() throws Exception {
        // given: 필수 값인 concertId가 null인 요청 객체 생성
        QueueEntryRequest invalidRequest = new QueueEntryRequest(null);

        // when & then: 400 Bad Request 및 ErrorCode.C001 반환 검증
        mockMvc.perform(post("/api/v1/queue/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("필수")));
    }

    @Test
    @DisplayName("대기열 진입 시 concertId가 양수가 아니면 C001 에러를 반환해야 한다")
    void should_return_C001_when_concertId_is_not_positive() throws Exception {
        // given: concertId가 0이거나 음수인 요청 객체 생성
        QueueEntryRequest invalidRequest = new QueueEntryRequest(0L);

        // when & then: 400 Bad Request 및 양수 검증 실패 메시지 확인
        mockMvc.perform(post("/api/v1/queue/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("양수")));
    }

    @Test
    @DisplayName("대기열 상태가 WAITING일 때 응답 JSON에서 accessToken 키가 완전히 제외되어야 한다")
    void should_exclude_accessToken_from_json_when_status_is_waiting() throws Exception {
        // given: 폴링 응답 모킹 (WAITING 상태, accessToken은 null)
        QueueStatusResponse response = new QueueStatusResponse(1L, 100L, "WAITING", null);
        given(queueService.getQueueStatus(anyLong(), anyLong())).willReturn(response);

        // when & then: JSON 포맷 규격 검증 (accessToken 필드가 아예 존재하지 않는지 확인)
        mockMvc.perform(get("/api/v1/queue/status")
                        .param("concertId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(1L))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(100L))
                .andExpect(jsonPath("$.accessToken").doesNotExist()); // 핵심 검증: 키 생략 여부
    }
}