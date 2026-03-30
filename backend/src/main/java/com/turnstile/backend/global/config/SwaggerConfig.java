package com.turnstile.backend.global.config;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger API 문서 자동화를 위한 전역 설정 클래스입니다.
 * JWT 토큰 기반 인증 체계를 UI에 통합하고, 불필요한 파라미터 노출을 방지합니다.
 */
@Configuration
public class SwaggerConfig {

    // 클래스 로드 시점에 SpringDoc 설정에 개입하여, ArgumentResolver가 런타임에 주입하는 @LoginUser 파라미터 제외
    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(LoginUser.class);
    }

    @Bean
    public OpenAPI openAPI() {
        String jwtSchemeName = "jwtAuth";

        // 모든 API 엔드포인트에 전역적으로 Authorization 헤더 요구사항 적용
        SecurityRequirement securityRequirement = new SecurityRequirement().addList(jwtSchemeName);

        // Bearer 방식의 JWT SecurityScheme 정의
        Components components = new Components()
                .addSecuritySchemes(jwtSchemeName, new SecurityScheme()
                        .name(jwtSchemeName)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));

        return new OpenAPI()
                .info(apiInfo())
                .addSecurityItem(securityRequirement)
                .components(components);
    }

    private Info apiInfo() {
        return new Info()
                .title("Turnstile API Document")
                .description("대규모 트래픽 제어 기반 좌석 예매 시스템 Turnstile의 API 명세서입니다.<br><br>" +
                        "<b>[인가 안내]</b><br>" +
                        "Turnstile은 3-Tier 하이브리드 토큰 정책을 사용합니다. 각 API 설명에 명시된 토큰 권한을 확인 후, " +
                        "<b>'Authorize'</b>를 통해 적절한 토큰을 주입해 주세요.<br>" +
                        "<ul>" +
                        "<li><b>LEVEL 1 (IDENTITY)</b> : 로그인 시 발급되는 기본 토큰 (대기열 진입용)</li>" +
                        "<li><b>LEVEL 2 (QUEUE)</b> : 대기열 통과 시 발급되는 예매 토큰 (좌석 점유용)</li>" +
                        "<li><b>LEVEL 3 (PAYMENT)</b> : 좌석 점유 시 발급되는 1회성 토큰 (최종 결제용)</li>" +
                        "</ul>")
                .version("v1.0.0");
    }
}