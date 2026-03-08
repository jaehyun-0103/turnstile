package com.turnstile.backend.domain.reservation.entity;

public enum ReservationStatus {
    PENDING,   // 예약 신청 (결제 이전)
    PAID,      // 결제 완료 (예약 확정)
    EXPIRED    // 취소
}
