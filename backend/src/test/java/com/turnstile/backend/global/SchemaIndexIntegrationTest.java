package com.turnstile.backend.global;

import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.turnstile.backend.support.DatabaseIntegrationTest;

/**
 * 데이터베이스 스키마 초기화 시 조회 성능 최적화를 위한 핵심 인덱스가 정상적으로 생성되는지 검증하는 통합 테스트입니다.
 */
class SchemaIndexIntegrationTest extends DatabaseIntegrationTest {

    @Test
    @DisplayName("신규 스키마 초기화 시 예약 그룹 및 만료 좌석 조회용 인덱스가 올바른 컬럼 순서로 생성되어야 한다")
    void queryIndexesAreCreatedForFreshSchema() {
        // given: information_schema.STATISTICS에서 특정 테이블과 인덱스의 구성 컬럼 순서를 조회하는 쿼리 정의
        String indexColumnsSql = """
                SELECT GROUP_CONCAT(
                    COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','
                )
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                  AND NON_UNIQUE = 1
                """;

        // when: reservation 테이블과 seat 테이블의 인덱스 구성 컬럼 조회
        String reservationIndexColumns = jdbc.queryForObject(
                indexColumnsSql,
                String.class,
                "reservation",
                "idx_reservation_group_id"
        );

        String seatIndexColumns = jdbc.queryForObject(
                indexColumnsSql,
                String.class,
                "seat",
                "idx_seat_status_hold_expires"
        );

        // then: 각 인덱스가 의도한 단일 및 복합 컬럼 순서로 생성되었는지 검증
        assertThat(reservationIndexColumns).isEqualTo("group_id");
        assertThat(seatIndexColumns).isEqualTo("status,hold_expires_at");
    }
}