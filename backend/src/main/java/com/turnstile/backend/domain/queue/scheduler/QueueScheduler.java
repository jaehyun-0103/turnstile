package com.turnstile.backend.domain.queue.scheduler;

import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 대기열의 입장 처리 및 만료된 유저 정리를 주기적으로 수행하는 스케줄러입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final QueueRedisRepository queueRedisRepository;

    @Value("${queue.policy.admission-batch-size:100}")
    private long admissionBatchSize;

    @Value("${queue.policy.active-ttl-seconds:300}")
    private long expirationTime;

    /**
     * 주기적으로 대기열을 순회하며 빈자리가 있을 경우 대기자를 활성 상태로 전이시킵니다.
     */
    @Scheduled(fixedDelay = 1000) // 1초마다 실행
    public void scheduleAdmission() {
        Set<String> activeConcerts = queueRedisRepository.getActiveConcerts();
        if (activeConcerts == null || activeConcerts.isEmpty()) {
            return;
        }

        long now = Instant.now().getEpochSecond();
        long expireScore = now + expirationTime;

        for (String concertIdStr : activeConcerts) {
            long maxCapacity = queueRedisRepository.getMaxActiveConfig(concertIdStr);

            List<String> enteredUsers = queueRedisRepository.transitionQueue(
                    concertIdStr, maxCapacity, admissionBatchSize, expireScore);

            if (enteredUsers != null && !enteredUsers.isEmpty()) {
                log.info("[Queue] Scheduler Transition | Success (concertId: {}, enteredCount: {})", concertIdStr, enteredUsers.size());
            }
        }
    }

    /**
     * 주기적으로 활성 상태 유저 중 점유 시간이 만료된 유저를 정리합니다.
     */
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
            log.debug("[Queue] Scheduler Cleanup | Executed (concertId: {})", concertIdStr);
        }
    }
}