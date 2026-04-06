package com.turnstile.backend.domain.auth.interceptor;

import com.turnstile.backend.domain.auth.enums.TokenType;
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
 * 모든 보호된 API 요청을 가로채어 토큰의 유효성과 3-Tier 권한(IDENTITY, QUEUE, PAYMENT)을 검증합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtProvider jwtProvider;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * HTTP 요청을 가로채어 토큰 검증 및 URI 기반의 권한 인가를 수행합니다.
     *
     * @param request  HTTP 요청 객체
     * @param response HTTP 응답 객체
     * @param handler  실행될 핸들러
     * @return 인가 성공 시 true, 실패 시 예외 발생
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS Preflight 요청은 인가 처리를 통과시킴
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            log.warn("[Auth] Verify Access | Fail - Token is missing (URI: {})", request.getRequestURI());
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }

        Long userId = jwtProvider.extractUserId(token);
        TokenType tokenType = jwtProvider.extractTokenType(token);
        String uri = request.getRequestURI();

        // 엔드포인트(URI) 성격에 따른 3-Tier 인가 정책 적용 (Fail-Fast)
        if (uri.startsWith("/api/v1/payments")) {
            // LEVEL 3: 결제 도메인은 반드시 PAYMENT 토큰만 허용
            if (!tokenType.hasAccess(TokenType.PAYMENT)) {
                log.warn("[Auth] Verify Access | Fail - Access Denied for Payment API (userId: {}, type: {})", userId, tokenType.name());
                throw new TurnstileException(ErrorCode.ACCESS_DENIED, "결제 API 접근 권한이 부족합니다.");
            }
        } else if (uri.equals("/api/v1/reservations/my")) {
            // LEVEL 1: 내 예매 내역 조회는 대기열 통과 없이 IDENTITY 이상으로 허용
            if (!tokenType.hasAccess(TokenType.IDENTITY)) {
                log.warn("[Auth] Verify Access | Fail - Access Denied for My Reservations API (userId: {}, type: {})", userId, tokenType.name());
                throw new TurnstileException(ErrorCode.ACCESS_DENIED, "마이페이지 접근 권한이 부족합니다.");
            }
        } else if (uri.startsWith("/api/v1/reservations") || uri.startsWith("/api/v1/schedules")) {
            // LEVEL 2: 좌석 조회 및 예약 도메인은 QUEUE 이상의 토큰 허용
            if (!tokenType.hasAccess(TokenType.QUEUE)) {
                log.warn("[Auth] Verify Access | Fail - Access Denied for Queue/Reservation API (userId: {}, type: {})", userId, tokenType.name());
                throw new TurnstileException(ErrorCode.ACCESS_DENIED, "대기열 통과 또는 예매 권한이 부족합니다.");
            }
        } else {
            // LEVEL 1: 대기열 폴링 및 콘서트 조회 등 기본 도메인은 IDENTITY 이상의 토큰 허용
            if (!tokenType.hasAccess(TokenType.IDENTITY)) {
                log.warn("[Auth] Verify Access | Fail - Access Denied for Base API (userId: {}, type: {})", userId, tokenType.name());
                throw new TurnstileException(ErrorCode.ACCESS_DENIED, "기본 접근 권한이 부족합니다.");
            }
        }

        request.setAttribute("userId", userId);

        return true;
    }

    /**
     * HTTP Request 헤더에서 Bearer 형식의 토큰을 안전하게 추출합니다.
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}