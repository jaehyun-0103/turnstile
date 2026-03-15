package com.turnstile.backend.domain.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.queue.controller.QueueController;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.service.QueueService;
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

    @BeforeEach
    void setUp() throws Exception {
        // 인터셉터 우회 모킹
        given(authInterceptor.preHandle(any(), any(), any())).willReturn(true);
    }

    @Test
    @DisplayName("대기열 진입 API 요청 시 정상적으로 진입 상태와 순번을 반환해야 한다")
    void should_return_queue_entry_status_on_post_request() throws Exception {
        // given: 진입 응답 모킹
        QueueEntryRequest request = new QueueEntryRequest(100L, 1L);
        QueueEntryResponse response = new QueueEntryResponse(1L, 5012L, "WAITING");
        given(queueService.enterQueue(any(QueueEntryRequest.class))).willReturn(response);

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
                        .param("concertId", "1")
                        .param("userId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(1L))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rank").value(0L))
                .andExpect(jsonPath("$.accessToken").value("mock-token"));
    }
}