package com.turnstile.backend.domain.concert;

public enum SeatStatus {
    AVAILABLE, // 예약 가능
    HOLD,      // 임시 배정 (결제 대기 중)
    SOLD       // 판매 완료
}
