package com.turnstile.backend.global;

import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.GlobalExceptionHandler;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 예외 핸들러가 다양한 예외 상황에 대해 올바른 HTTP 응답을 반환하는지 검증하는 테스트입니다.
 */
class GlobalExceptionHandlerControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 테스트 목적으로 사용하는 임시 컨트롤러입니다.
     */
    @RestController
    static class TestController {
        @GetMapping("/test/exception")
        public void throwException() {
            throw new TurnstileException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        @GetMapping("/test/unhandled")
        public void throwUnhandled() {
            throw new RuntimeException("Unexpected Error");
        }

        @GetMapping("/test/method")
        public void methodNotAllowed() {
        }
    }

    @Test
    @DisplayName("TurnstileException 발생 시 정의된 에러 코드와 4xx 응답을 반환한다")
    void handleTurnstileExceptionTest() throws Exception {
        // given: 테스트용 API 경로가 존재함

        // when & then: 커스텀 예외 발생 시 JSON 응답 규격을 확인함
        mockMvc.perform(get("/test/exception"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("U002"))
                .andExpect(jsonPath("$.message").value("잔액이 부족합니다."));
    }

    @Test
    @DisplayName("정의되지 않은 시스템 예외 발생 시 500 응답과 공통 메시지를 반환한다")
    void handleGeneralExceptionTest() throws Exception {
        // given: 예상치 못한 런타임 예외가 발생함

        // when & then: 내부 메시지는 숨기고 표준 500 응답을 확인함
        mockMvc.perform(get("/test/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP Method 호출 시 405 응답을 반환한다")
    void handleMethodNotSupportedExceptionTest() throws Exception {
        // given: GET만 허용된 엔드포인트에 POST로 요청함

        // when & then: 405 Method Not Allowed 응답을 확인함
        mockMvc.perform(post("/test/method"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("C003"));
    }
}