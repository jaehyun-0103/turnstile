package com.turnstile.backend;

import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BackendApplicationTests extends DatabaseIntegrationTest {
    @Test
    void contextLoadsWithIsolatedInfrastructure() {
        assertThat(reservationService).isNotNull();
        assertThat(paymentService).isNotNull();
        assertThat(queue).isNotNull();
    }
}
