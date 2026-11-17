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
        assertThat(Arrays.asList(environment.getActiveProfiles()))
                .contains("test")
                .doesNotContain("local", "perf", "prod");

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo(MYSQL.getJdbcUrl());

        assertThat(environment.getProperty("spring.data.redis.host"))
                .isEqualTo(REDIS.getHost());

        assertThat(environment.getProperty(
                "spring.data.redis.port",
                Integer.class
        )).isEqualTo(REDIS.getMappedPort(6379));

        assertThat(applicationContext.getBeansOfType(
                DummyDataInitializer.class
        )).isEmpty();
    }
}