package com.turnstile.backend.domain.reservation.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    // 좌석 임시 점유 API
    @PostMapping
    public ResponseEntity<ReservationResponse> reserveSeats(
            @LoginUser Long loginUserId,
            @RequestBody ReservationRequest request) {

        // 인터셉터에서 검증된 userId 주입
        ReservationRequest secureRequest = new ReservationRequest(
                loginUserId,
                request.concertId(),
                request.seatIds()
        );

        ReservationResponse response = reservationService.reserveSeats(secureRequest);
        return ResponseEntity.ok(response);
    }
}