package com.turnstile.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserPersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("유저 저장 시 생성일 및 수정일 자동 기록")
    void checkAuditing() {
        // given: 유저 생성
        User user = User.builder()
                .username("test_username")
                .name("test_name")
                .balance(0L)
                .build();

        // when: DB 저장
        User savedUser = userRepository.save(user);

        // then: 검증
        assertThat(savedUser.getCreatedAt()).isNotNull(); // 생성일이 자동으로 저장되었는지 확인
        assertThat(savedUser.getUpdatedAt()).isNotNull(); // 수정일이 자동으로 저장되었는지 확인
    }
}
