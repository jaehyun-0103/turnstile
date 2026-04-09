package com.turnstile.backend.domain.queue.metrics;

import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueMetrics {

    private final MeterRegistry meterRegistry;
    private final QueueRedisRepository queueRedisRepository;
    private final Set<String> registeredConcerts = ConcurrentHashMap.newKeySet();

    /**
     * 10초마다 Redis를 확인하여 새롭게 열린 콘서트가 있다면 모니터링 지표에 동적으로 추가합니다.
     */
    @Scheduled(fixedDelay = 10000)
    public void registerDynamicMetrics() {
        Set<String> activeConcerts = queueRedisRepository.getActiveConcerts();
        if (activeConcerts == null || activeConcerts.isEmpty()) {
            return;
        }

        for (String concertId : activeConcerts) {
            // 아직 모니터링에 등록되지 않은 새로운 콘서트라면 지표 측정기(Gauge) 부착
            if (!registeredConcerts.contains(concertId)) {

                Gauge.builder("turnstile.queue.waiting.users", queueRedisRepository,
                                repo -> (double) repo.getWaitingUserCount(concertId))
                        .tag("concertId", concertId)
                        .description("현재 대기열에서 기다리고 있는 유저 수")
                        .register(meterRegistry);

                Gauge.builder("turnstile.queue.active.users", queueRedisRepository,
                                repo -> (double) repo.getActiveUserCount(concertId))
                        .tag("concertId", concertId)
                        .description("현재 입장 완료되어 활성 상태인 유저 수")
                        .register(meterRegistry);

                registeredConcerts.add(concertId);
                log.info("[Metrics] Registered dynamic queue metrics for Concert ID: {}", concertId);
            }
        }
    }
}