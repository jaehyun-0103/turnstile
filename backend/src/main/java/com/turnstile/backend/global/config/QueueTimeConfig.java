package com.turnstile.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 대기열 만료 시각 계산 및 상태 판별에 사용할 기준 시계(Clock) 빈을 등록하는 설정 클래스입니다.
 */
@Configuration
public class QueueTimeConfig {

    @Bean
    public Clock queueClock() {
        return Clock.systemUTC();
    }
}