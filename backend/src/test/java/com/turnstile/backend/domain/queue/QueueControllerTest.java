package com.turnstile.backend.domain.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.JwtProvider;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.RedisTemplate;
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

@WebMvcTest(QueueController.class)
class QueueControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private QueueService queueService;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("POST /api/v1/queue/entry: JSON 요청 시 진입 상태와 대기 순번 반환")
    void enterQueueApiTest() throws Exception {
        // given: 요청 및 응답 값 세팅
        QueueEntryRequest request = new QueueEntryRequest(100L, 1L);
        QueueEntryResponse response = new QueueEntryResponse(1L, 5012L, "WAITING");

        given(queueService.enterQueue(any(QueueEntryRequest.class))).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/queue/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(1L))
                .andExpect(jsonPath("$.rank").value(5012L))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("GET /api/v1/queue/status: 폴링 시 상태, 순번, 토큰 정보 반환")
    void getQueueStatusApiTest() throws Exception {
        // given: 응답 값 세팅
        QueueStatusResponse response = new QueueStatusResponse(1L, 0L, "ACTIVE", "mock-access-token");
        given(queueService.getQueueStatus(anyLong(), anyLong())).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/queue/status")
                        .param("concertId", "1")
                        .param("userId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(1L))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rank").value(0L))
                .andExpect(jsonPath("$.accessToken").value("mock-access-token"));
    }
}