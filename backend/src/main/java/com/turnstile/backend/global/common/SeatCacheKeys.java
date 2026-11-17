package com.turnstile.backend.global.common;

/**
 * 좌석 조회 캐시 및 분산 락에 사용되는 Redis 키 네임스페이스를 생성하는 유틸리티 클래스입니다.
 */
public final class SeatCacheKeys {

    private SeatCacheKeys() {
    }

    /**
     * 특정 회차의 좌석 목록 캐시 키를 생성합니다.
     */
    public static String seats(Long scheduleId) {
        return "concert:schedule:" + scheduleId + ":seats";
    }

    /**
     * 특정 회차의 좌석 캐시 적재 시 동시성 제어를 위한 분산 락 키를 생성합니다.
     */
    public static String lock(Long scheduleId) {
        return "lock:concert:schedule:" + scheduleId;
    }
}