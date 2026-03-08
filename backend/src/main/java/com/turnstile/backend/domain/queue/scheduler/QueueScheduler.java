package com.turnstile.backend.domain.queue.scheduler;

import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final QueueRedisRepository queueRedisRepository;

    @Scheduled(fixedDelay = 1000) // 1초마다 실행
    public void scheduleAdmission() {
        // 활성화된 콘서트 목록 조회
        Set<String> activeConcerts = queueRedisRepository.getActiveConcerts();
        if (activeConcerts == null || activeConcerts.isEmpty()) {
            return;
        }

        long now = Instant.now().getEpochSecond();
        long expireScore = now + 300; // 진입 가능 상태 수명 5분

        for (String concertIdStr : activeConcerts) {
            // 동적 허용 인원 설정값 조회 (기본값: 100)
            long maxCapacity = queueRedisRepository.getMaxActiveConfig(concertIdStr);

            // Lua 스크립트 실행을 통한 원자적 대기열 상태 전이
            List<String> enteredUsers = queueRedisRepository.transitionQueue(concertIdStr, maxCapacity, expireScore);

            if (enteredUsers != null && !enteredUsers.isEmpty()) {
                log.info("콘서트 {} 입장 처리 완료: {}명", concertIdStr, enteredUsers.size());
            }
        }
    }

    // 만료자 정리
    @Scheduled(fixedRate = 10000) // 10초마다 실행
    public void scheduleCleanup() {
        Set<String> activeConcerts = queueRedisRepository.getActiveConcerts();
        if (activeConcerts == null || activeConcerts.isEmpty()) {
            return;
        }

        long now = Instant.now().getEpochSecond();

        for (String concertIdStr : activeConcerts) {
            // 10초마다 만료된 진입 완료 유저 데이터 일괄 삭제
            queueRedisRepository.removeExpiredActiveUsers(concertIdStr, now);
        }
    }
}