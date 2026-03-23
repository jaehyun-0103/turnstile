package com.turnstile.backend.global.event;

/**
 * 좌석 상태 변경 트랜잭션이 성공적으로 커밋되었을 때,
 * 해당 회차의 좌석표 캐시를 무효화하기 위해 발행되는 이벤트 객체입니다.
 */
public record SeatCacheEvictEvent(
        Long scheduleId
) {}