package com.turnstile.backend.global.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 로컬 개발 및 통합 테스트 환경에서 사용할 기준(Mock) 데이터를 자동 삽입하는 초기화 클래스입니다.
 * 컨테이너 재빌드 없이도 다양한 예매 시나리오(오픈 전, 예매 중, 종료)를 검증할 수 있도록 다중 콘서트를 구성합니다.
 */
@Slf4j
@Profile({"local", "perf"}) // 운영 환경에서 실수로 실행되지 않도록 격리
@Component
@RequiredArgsConstructor
public class DummyDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 이미 데이터가 존재하면 삽입 로직을 스킵하여 서버 재시작 시 중복 오류 방지
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (userCount != null && userCount > 0) {
            log.info("[Global] Dummy Data Init | Skipped (reason: Data already exists)");
            return;
        }

        log.info("[Global] Dummy Data Init | Started (Target: 100 Users, 4 Concerts, 200 Seats)");

        // 유저 100명 삽입 (1~90번은 잔액 50만 원, 91~100번은 잔액 0원으로 경계값 테스트 구성)
        List<Object[]> userBatchArgs = new ArrayList<>();
        for (int i = 1; i <= 10000; i++) {
            // 1~9000번은 잔액 50만 원, 9001~10000번은 잔액 0원
            long balance = (i <= 9000) ? 500000L : 0L;
            userBatchArgs.add(new Object[]{"user" + i, "테스터" + i, balance});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO users (username, name, balance, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                userBatchArgs
        );

        // 상태별 콘서트 3종 삽입 (진행 중 / 과거 종료 / 미래 오픈 예정)
        String insertConcertsSql = "INSERT INTO concert (id, title, description, ticketing_start_at, created_at, updated_at) VALUES " +
                "(1, '[진행중] 턴스타일 스프링 콘서트1', '현재 예매 가능한 기본 콘서트입니다.', '2024-01-01 00:00:00.000000', NOW(6), NOW(6)), " +
                "(2, '[진행중] 턴스타일 스프링 콘서트2', '현재 예매 가능한 기본 콘서트입니다.', '2024-01-01 00:00:00.000000', NOW(6), NOW(6)), " +
                "(3, '[종료] 턴스타일 윈터 콘서트', '이미 예매와 공연이 모두 종료된 콘서트입니다.', '2023-01-01 00:00:00.000000', NOW(6), NOW(6)), " +
                "(4, '[오픈예정] 턴스타일 썸머 콘서트', '아직 예매 시간이 되지 않은 콘서트입니다.', '2026-12-01 00:00:00.000000', NOW(6), NOW(6))";
        jdbcTemplate.execute(insertConcertsSql);

        // 콘서트별 회차(Schedule) 1개씩 삽입
        String insertSchedulesSql = "INSERT INTO concert_schedule (id, concert_id, concert_date, total_seats, created_at, updated_at) VALUES " +
                "(1, 1, '2026-12-24 19:00:00.000000', 50, NOW(6), NOW(6)), " +
                "(2, 2, '2023-12-24 19:00:00.000000', 50, NOW(6), NOW(6)), " +
                "(3, 3, '2023-12-24 19:00:00.000000', 50, NOW(6), NOW(6)), " +
                "(4, 4, '2027-01-01 19:00:00.000000', 50, NOW(6), NOW(6))";
        jdbcTemplate.execute(insertSchedulesSql);

        // 각 회차별 좌석 50개씩 총 150석 삽입 (재귀 CTE를 재사용하여 간결하게 구성)
        String insertSeatsSql = "INSERT INTO seat (schedule_id, seat_no, price, status, created_at, updated_at) " +
                "WITH RECURSIVE cte AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 50) " +
                "SELECT 1, n, 150000, 'AVAILABLE', NOW(6), NOW(6) FROM cte UNION ALL " +
                "SELECT 2, n, 150000, 'AVAILABLE', NOW(6), NOW(6) FROM cte UNION ALL " +
                "SELECT 3, n, 150000, 'AVAILABLE', NOW(6), NOW(6) FROM cte UNION ALL " +
                "SELECT 4, n, 150000, 'AVAILABLE', NOW(6), NOW(6) FROM cte";
        jdbcTemplate.execute(insertSeatsSql);

        log.info("[Global] Dummy Data Init | Success (Inserted: 100 Users, 4 Concerts, 4 Schedules, 200 Seats)");
    }
}