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
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Spring 스캔에 의존하지 않고, 직접 컨트롤러와 어드바이스를 주입하여 MockMvc를 생성
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // 테스트용 임시 컨트롤러
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
    }

    @Test
    @DisplayName("TurnstileException 발생 시 400과 정의된 메시지 반환")
    void handleTurnstileExceptionTest() throws Exception {
        mockMvc.perform(get("/test/exception"))
                .andDo(print())
                .andExpect(status().isBadRequest()) // 400 확인
                .andExpect(jsonPath("$.code").value("U002"))
                .andExpect(jsonPath("$.message").value("잔액이 부족합니다."));
    }

    @Test
    @DisplayName("정의되지 않은 Exception 발생 시 500 Internal Server Error 반환")
    void handleGeneralExceptionTest() throws Exception {
        mockMvc.perform(get("/test/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }
}