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

/**
 * HTTP 요청의 JWT 토큰 유효성을 검증하고 인증 정보를 설정하는 인터셉터입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtProvider jwtProvider;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 브라우저의 CORS 예비 요청은 인증 확인 대상에서 제외함
        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }

        String token = resolveToken(request);

        if (token == null) {
            log.warn("[Auth] Authorize | Fail - Token missing (URI: {})", request.getRequestURI());
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }

        jwtProvider.validateToken(token);

        Long userId = jwtProvider.extractUserId(token);
        request.setAttribute("userId", userId);

        return true;
    }

    /**
     * HTTP 요청 헤더에서 Bearer 토큰 문자열을 추출합니다.
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}