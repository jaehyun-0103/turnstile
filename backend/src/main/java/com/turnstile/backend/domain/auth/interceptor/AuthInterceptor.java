package com.turnstile.backend.domain.auth.interceptor;

import com.turnstile.backend.domain.auth.JwtProvider;
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

        // 토큰 검증 통과 시 request에 userId를 추가해서 컨트롤러로 넘김
        if (token != null && jwtProvider.validateToken(token)) {
            Long userId = jwtProvider.extractUserId(token);
            request.setAttribute("userId", userId);
            return true;
        }

        // 토큰 검증 실패 시 에러 반환 및 컨트롤러 진입 차단
        log.warn("인가 실패: 유효하지 않거나 누락된 JWT 토큰입니다. URI: {}", request.getRequestURI());
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 토큰입니다.");
        return false;
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