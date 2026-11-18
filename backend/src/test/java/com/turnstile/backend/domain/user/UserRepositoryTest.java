package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.support.RepositoryIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유저 엔티티의 JPA 영속성 및 Auditing 동작을 검증하는 데이터 계층 테스트입니다.
 */
class UserRepositoryTest extends RepositoryIntegrationTest {

    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("새로운 유저 저장 시 생성일 및 수정일이 자동으로 기록되어야 한다")
    void should_auto_populate_auditing_fields_on_save() {
        // given: 영속화되지 않은 유저 객체 생성
        User user = User.builder()
                .username("test_username")
                .name("test_name")
                .balance(0L)
                .build();

        // when: DB 저장 (영속성 컨텍스트 반영)
        User savedUser = userRepository.save(user);

        // then: JPA Auditing에 의해 시간 필드가 채워졌는지 검증
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(savedUser.getUpdatedAt()).isNotNull();
    }
}