package com.turnstile.backend.support;

import com.turnstile.backend.global.init.DummyDataInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers 기반의 격리된 MySQL 및 Redis 인프라 환경을 제공하는 통합 테스트 추상 클래스입니다.
 */
@ActiveProfiles("test")
public abstract class IsolatedIntegrationTest {

    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0")
                    .withDatabaseName("turnstile_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withUrlParam("serverTimezone", "Asia/Seoul")
                    .withUrlParam("allowPublicKeyRetrieval", "true")
                    .withUrlParam("useSSL", "false");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine")
                    .withExposedPorts(6379)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*Ready to accept connections.*\\n",
                                    1
                            )
                    )
                    .withStartupTimeout(Duration.ofSeconds(60));

    static {
        // 통합 테스트 실행 전 격리된 MySQL 및 Redis 컨테이너 기동
        MYSQL.start();
        REDIS.start();

        System.out.printf(
                "IMP009_INFRA mysql=%s:%d redis=%s:%d%n",
                MYSQL.getHost(),
                MYSQL.getMappedPort(3306),
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        // 동적으로 할당된 컨테이너 접속 정보를 Spring 프로퍼티에 주입
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add(
                "spring.data.redis.port",
                () -> REDIS.getMappedPort(6379)
        );
    }

    @BeforeEach
    void verifyIsolation() {
        // 활성 프로필 격리 여부 및 더미 데이터 초기화 빈 미등록 상태 검증
        assertThat(Arrays.asList(environment.getActiveProfiles())).contains("test").doesNotContain("local", "perf", "prod");

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(MYSQL.getJdbcUrl());
        assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo(REDIS.getHost());
        assertThat(environment.getProperty("spring.data.redis.port", Integer.class)).isEqualTo(REDIS.getMappedPort(6379));

        assertThat(applicationContext.getBeansOfType(DummyDataInitializer.class)).isEmpty();
    }
}