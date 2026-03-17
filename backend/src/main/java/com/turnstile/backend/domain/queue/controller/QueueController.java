package com.turnstile.backend.domain.queue.controller;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 콘서트 예매를 위한 대기열 진입 및 상태 폴링 요청을 처리하는 컨트롤러입니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /**
     * 사용자를 특정 콘서트의 대기열에 진입시킵니다.
     */
    @PostMapping("/entry")
    public ResponseEntity<QueueEntryResponse> enterQueue(
            @LoginUser Long userId,
            @RequestBody QueueEntryRequest request) {
        log.info("[Queue] API Request | Enter Queue (userId: {}, concertId: {})", userId, request.concertId());
        QueueEntryResponse response = queueService.enterQueue(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 사용자의 현재 대기열 순위 또는 입장 가능 상태를 조회합니다.
     */
    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getQueueStatus(
            @RequestParam Long concertId,
            @LoginUser Long userId) {
        // 폴링 요청이므로 디버그 레벨로 설정하여 평상시 로그 스팸을 방지함
        log.debug("[Queue] API Request | Get Queue Status (userId: {}, concertId: {})", userId, concertId);
        QueueStatusResponse status = queueService.getQueueStatus(concertId, userId);
        return ResponseEntity.ok(status);
    }
}