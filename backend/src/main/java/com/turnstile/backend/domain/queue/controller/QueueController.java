package com.turnstile.backend.domain.queue.controller;

import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    // 대기열 진입 API
    @PostMapping("/entry")
    public ResponseEntity<QueueEntryResponse> enterQueue(@RequestBody QueueEntryRequest request) {
        QueueEntryResponse response = queueService.enterQueue(request);
        return ResponseEntity.ok(response);
    }

    // 대기열 상태 조회 API
    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getQueueStatus(
            @RequestParam Long concertId,
            @RequestParam Long userId) {

        QueueStatusResponse status = queueService.getQueueStatus(concertId, userId);
        return ResponseEntity.ok(status);
    }
}