package com.turnstile.backend.domain.reservation.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.reservation.dto.MyReservationResponse;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 콘서트 좌석 예약 및 예약 내역 조회를 처리하는 컨트롤러입니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * 선택한 좌석들에 대해 임시 점유(예약)를 요청합니다.
     */
    @PostMapping
    public ResponseEntity<ReservationResponse> reserveSeats(
            @LoginUser Long loginUserId,
            @RequestBody ReservationRequest request) {

        log.info("[Reservation] API Request | Reserve Seats (userId: {}, concertId: {}, seatCount: {})", loginUserId, request.concertId(), request.seatIds().size());

        // 인터셉터에서 검증된 userId 주입
        ReservationRequest secureRequest = new ReservationRequest(
                loginUserId,
                request.concertId(),
                request.seatIds()
        );

        ReservationResponse response = reservationService.reserveSeats(secureRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * 로그인한 사용자의 전체 예약 내역을 조회합니다.
     */
    @GetMapping("/my")
    public ResponseEntity<List<MyReservationResponse>> getMyReservations(@LoginUser Long userId) {
        log.info("[Reservation] API Request | Get My Reservations (userId: {})", userId);
        return ResponseEntity.ok(reservationService.getMyReservations(userId));
    }
}