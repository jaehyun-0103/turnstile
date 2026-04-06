package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.entity.SeatStatus;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 상태 변경(점유, 만료, 회수)을 처리하는 사용자 정의 @Query 메서드들의 동작을 검증하는 데이터 계층 테스트입니다.
 */
@DataJpaTest
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SeatRepositoryTest {

    @Autowired private SeatRepository seatRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private CacheEvictor cacheEvictor;

    @Test
    @DisplayName("좌석이 AVAILABLE 상태일 때만 HOLD로 상태가 안전하게 업데이트되어야 한다")
    void should_update_status_to_hold_when_seat_is_available() {
        // given: 더미 데이터 중 31번 좌석(AVAILABLE) 타겟팅
        Long targetSeatId = 31L;
        Long userId = 1L;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        // when: CAS 쿼리로 점유 시도
        int updatedRowCount = seatRepository.holdSeatsConditionally(List.of(targetSeatId), userId, expiresAt);

        // then: 업데이트 성공(1) 및 상태 변경 확인
        assertThat(updatedRowCount).isEqualTo(1);
        Seat updatedSeat = seatRepository.findById(targetSeatId).orElseThrow();
        assertThat(updatedSeat.getStatus()).isEqualTo(SeatStatus.HOLD);
    }

    @Test
    @DisplayName("이미 누군가 점유한 HOLD 상태인 좌석에 접근하면 업데이트를 거부하고 0을 반환해야 한다")
    void should_return_zero_when_seat_is_already_held() {
        // given: 32번 좌석을 1번 유저가 이미 점유한 상태로 강제 세팅
        Long targetSeatId = 32L;
        Long latecomerUserId = 2L;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = 1, hold_expires_at = ? WHERE id = ?", expiresAt, targetSeatId);

        // when: 다른 유저(2번)가 해당 좌석 점유 시도
        int updatedRowCount = seatRepository.holdSeatsConditionally(List.of(targetSeatId), latecomerUserId, expiresAt);

        // then: 업데이트 실패(0) 확인 (CAS 방어 성공)
        assertThat(updatedRowCount).isEqualTo(0);
    }

    @Test
    @DisplayName("만료 시간이 지난 HOLD 상태의 좌석을 AVAILABLE 상태로 일괄 변경해야 한다")
    void should_release_expired_seats() {
        // given: 33번 좌석을 10분 전 만료된 상태로 강제 세팅
        Long targetSeatId = 33L;
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(10);

        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = 1, hold_expires_at = ? WHERE id = ?", pastTime, targetSeatId);

        // when: 현재 시간 기준으로 만료된 좌석 일괄 회수 쿼리 실행
        int releasedCount = seatRepository.releaseExpiredSeats(LocalDateTime.now());

        // then: 업데이트 성공(최소 1개 이상) 및 상태가 AVAILABLE로 초기화되었는지 확인
        assertThat(releasedCount).isGreaterThanOrEqualTo(1);
        Seat updatedSeat = seatRepository.findById(targetSeatId).orElseThrow();
        assertThat(updatedSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(updatedSeat.getHoldUserId()).isNull();
        assertThat(updatedSeat.getHoldExpiresAt()).isNull();
    }

    @Test
    @DisplayName("만료된 좌석이 존재하는 회차(Schedule) ID 목록을 정확히 식별해야 한다")
    void should_find_schedule_ids_by_expired_seats() {
        // given: 1번 콘서트 회차(Schedule ID: 1)에 속한 34번 좌석을 만료 상태로 강제 세팅
        Long targetSeatId = 34L;
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(10);

        jdbcTemplate.update("UPDATE seat SET status = 'HOLD', hold_user_id = 1, hold_expires_at = ? WHERE id = ?", pastTime, targetSeatId);

        // when: 만료 좌석이 있는 회차 ID 목록 조회 쿼리 실행
        List<Long> scheduleIds = seatRepository.findScheduleIdsByExpiredSeats(LocalDateTime.now());

        // then: 1번 회차 ID가 결과에 포함되어 있는지 확인
        assertThat(scheduleIds).contains(1L);
    }
}