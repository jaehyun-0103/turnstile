package com.turnstile.backend.global.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 환경에서 부하 테스트를 위한 15,000건의 더미 데이터를 자동 삽입하는 초기화 클래스입니다.
 * JPA 대신 JdbcTemplate과 재귀 CTE 쿼리를 사용하여 1초 이내에 벌크 인서트를 완료합니다.
 */
@Slf4j
@Profile("local") // 운영 환경에서 실수로 실행되지 않도록 격리
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

        log.info("[Global] Dummy Data Init | Started (Target: 10,000 Users, 5,000 Seats)");

        // 재귀 쿼리 깊이 제한 해제 (1만 건 생성)
        jdbcTemplate.execute("SET SESSION cte_max_recursion_depth = 10000");

        // 유저 1만 명 삽입
        String insertUsersSql = "INSERT INTO users (username, name, balance, created_at, updated_at) " +
                "WITH RECURSIVE cte AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 10000) " +
                "SELECT CONCAT('user', n), CONCAT('테스터', n), IF(n <= 9000, 500000, 0), NOW(6), NOW(6) FROM cte";
        jdbcTemplate.execute(insertUsersSql);

        // 콘서트 및 스케줄 삽입
        jdbcTemplate.execute("INSERT INTO concert (id, title, description, ticketing_start_at, created_at, updated_at) " +
                "VALUES (1, '[Load Test] 턴스타일 대규모 콘서트', '1만명 부하 테스트용', '2024-01-01 00:00:00.000000', NOW(6), NOW(6))");

        jdbcTemplate.execute("INSERT INTO concert_schedule (id, concert_id, concert_date, total_seats, created_at, updated_at) " +
                "VALUES (1, 1, '2026-12-24 19:00:00.000000', 5000, NOW(6), NOW(6))");

        // 좌석 5천 개 삽입
        String insertSeatsSql = "INSERT INTO seat (schedule_id, seat_no, price, status, created_at, updated_at) " +
                "WITH RECURSIVE cte AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 5000) " +
                "SELECT 1, n, 150000, 'AVAILABLE', NOW(6), NOW(6) FROM cte";
        jdbcTemplate.execute(insertSeatsSql);

        log.info("[Global] Dummy Data Init | Success (Inserted: 10000 Users, 1 Concert, 1 Schedule, 5000 Seats)");
    }
}