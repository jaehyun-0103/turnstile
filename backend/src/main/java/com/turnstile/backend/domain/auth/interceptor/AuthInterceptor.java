package com.turnstile.backend.domain.auth.interceptor;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtProvider jwtProvider;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 처리를 위한 OPTIONS 메서드는 인증 없이 통과
        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }

        String token = resolveToken(request);

        if (token == null) {
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }

        // Provider 내부에서 검증 실패 시 상세 에러 코드를 담은 TurnstileException이 발생함
        jwtProvider.validateToken(token);

        Long userId = jwtProvider.extractUserId(token);
        request.setAttribute("userId", userId);

        return true;
    }

    // 헤더에서 Bearer 토큰 추출
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}