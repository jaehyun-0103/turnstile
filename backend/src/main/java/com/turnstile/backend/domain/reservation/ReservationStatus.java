package com.turnstile.backend.domain.reservation;

public enum ReservationStatus {
    PENDING,   // 예약 신청 (결제 이전)
    PAID,      // 결제 완료 (예약 확정)
    CANCELLED  // 취소
}
