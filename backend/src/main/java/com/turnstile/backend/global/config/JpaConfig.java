package com.turnstile.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 엔티티의 공통 감시(Auditing) 기능을 활성화하는 설정 클래스입니다.
 */
@Configuration
@EnableJpaAuditing // JPA Auditing 기능 활성화
public class JpaConfig {

}