package com.turnstile.backend.global.config;

import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 웹 계층의 인터셉터 및 파라미터 리졸버를 등록하는 설정 클래스입니다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final LoginUserArgumentResolver loginUserArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 보안이 필요한 비즈니스 API 경로를 화이트리스트 방식으로 관리
                .addPathPatterns("/api/v1/reservations/**", "/api/v1/payments/**", "/api/v1/schedules/*/seats")
                // 인증 없이 접근 가능한 공개 API 및 인증 관련 엔드포인트는 제외
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/queue/**", "/api/v1/concerts");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginUserArgumentResolver);
    }
}