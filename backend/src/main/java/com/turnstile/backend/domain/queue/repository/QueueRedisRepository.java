package com.turnstile.backend.domain.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * Redis와 Lua 스크립트를 활용하여 대기열의 원자적 상태 변경 및 조회를 담당하는 리포지토리입니다.
 */
@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> enterQueueScript;
    private final RedisScript<List<String>> transitionQueueScript;
    private final Clock clock;

    private static final String ACTIVE_CONCERTS_KEY = "queue:scheduler:active_concerts";

    /**
     * Lua 스크립트를 실행하여 사용자를 대기열에 원자적으로 진입시키고 스케줄러 트리거를 활성화합니다.
     * @return 대기 순번 (이미 활성 상태인 경우 -1 반환)
     */
    public Long enterQueue(String concertIdStr, String userIdStr) {
        String activeKey = "queue:concert:" + concertIdStr + ":active";
        String waitingKey = "queue:concert:" + concertIdStr + ":waiting";
        String counterKey = "queue:concert:" + concertIdStr + ":ticket_counter";

        return redisTemplate.execute(
                enterQueueScript,
                List.of(activeKey, waitingKey, counterKey, ACTIVE_CONCERTS_KEY),
                userIdStr,
                concertIdStr,
                String.valueOf(clock.instant().getEpochSecond())
        );
    }

    /**
     * 현재 기준 시계(Clock)의 시각을 기준으로 대기 중인 유저를 활성(입장) 상태로 전이합니다.
     * @return 활성 상태로 전이된 사용자 ID 목록
     */
    public List<String> transitionQueue(
            String concertIdStr,
            long maxCapacity,
            long batchSize,
            long expireScore
    ) {
        return transitionQueue(
                concertIdStr,
                maxCapacity,
                batchSize,
                expireScore,
                clock.instant().getEpochSecond()
        );
    }

    /**
     * 허용된 정원과 초당 유입 제한량(Batch Size)에 맞춰 만료된 활성 유저를 정리하고 대기 중인 유저를 활성(입장) 상태로 전이합니다.
     * 빈 콘서트일 경우 Lua 스크립트 내부에서 자동으로 스케줄러 순회 대상에서 제외(Self-Cleanup)합니다.
     * @return 활성 상태로 전이된 사용자 ID 목록
     */
    public List<String> transitionQueue(
            String concertIdStr,
            long maxCapacity,
            long batchSize,
            long expireScore,
            long nowEpochSeconds
    ) {
        String waitingKey = "queue:concert:" + concertIdStr + ":waiting";
        String activeKey = "queue:concert:" + concertIdStr + ":active";

        return redisTemplate.execute(
                transitionQueueScript,
                List.of(waitingKey, activeKey, ACTIVE_CONCERTS_KEY),
                String.valueOf(maxCapacity),
                String.valueOf(expireScore),
                String.valueOf(batchSize),
                concertIdStr,
                String.valueOf(nowEpochSeconds)
        );
    }

    /**
     * 대기열에 있는 특정 사용자의 현재 순위를 조회합니다.
     */
    public Long getWaitingRank(String concertIdStr, String userIdStr) {
        String waitingKey = "queue:concert:" + concertIdStr + ":waiting";
        return redisTemplate.opsForZSet().rank(waitingKey, userIdStr);
    }

    /**
     * 특정 사용자가 현재 만료되지 않은 유효한 활성(입장 완료) 상태인지 확인합니다.
     */
    public boolean isActiveUser(String concertIdStr, String userIdStr) {
        return getActiveUserScore(concertIdStr, userIdStr) != null;
    }

    /**
     * 대기열이 가동 중인 전체 콘서트 식별자 목록을 반환합니다.
     */
    public Set<String> getActiveConcerts() {
        return redisTemplate.opsForSet().members(ACTIVE_CONCERTS_KEY);
    }

    /**
     * 만료 시간(Score)이 지난 활성 상태 유저들을 일괄 삭제합니다.
     */
    public void removeExpiredActiveUsers(String concertIdStr, long now) {
        String activeKey = "queue:concert:" + concertIdStr + ":active";
        redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, now);
    }

    /**
     * 동시 접속 최대 허용 인원(Max Capacity) 설정값을 조회합니다.
     * 설정값이 없을 경우 기본값인 5000명을 반환합니다.
     */
    public long getMaxActiveConfig(String concertIdStr) {
        String maxActiveConfigKey = "queue:concert:" + concertIdStr + ":capacity";
        String maxActiveStr = redisTemplate.opsForValue().get(maxActiveConfigKey);

        return (maxActiveStr != null) ? Long.parseLong(maxActiveStr) : 5000L;
    }

    /**
     * 현재 유효한 활성(ACTIVE) 상태 사용자의 만료 시각(Score)을 조회합니다.
     * 존재하지 않거나 만료 시각이 경과한 경우 null을 반환하며, 키 멤버를 직접 삭제하지는 않습니다.
     */
    public Double getActiveUserScore(String concertIdStr, String userIdStr) {
        String activeKey = "queue:concert:" + concertIdStr + ":active";
        Double score = redisTemplate.opsForZSet().score(activeKey, userIdStr);
        long now = clock.instant().getEpochSecond();

        return score != null && score > now ? score : null;
    }

    /**
     * 결제 완료 등 비즈니스 목적을 달성한 유저를 활성열에서 즉시 퇴출합니다.
     * 대기열의 병목을 해소하고 회전율을 극대화하기 위해 사용됩니다.
     */
    public void removeActiveUser(String concertIdStr, String userIdStr) {
        String activeKey = "queue:concert:" + concertIdStr + ":active";
        redisTemplate.opsForZSet().remove(activeKey, userIdStr);
    }

    /**
     * 특정 콘서트의 현재 대기 중인 유저 수(WAITING)를 조회합니다.
     */
    public long getWaitingUserCount(String concertIdStr) {
        String waitingKey = "queue:concert:" + concertIdStr + ":waiting";
        Long count = redisTemplate.opsForZSet().zCard(waitingKey);
        return count != null ? count : 0L;
    }

    /**
     * 특정 콘서트의 현재 입장 완료된 유저 수(ACTIVE)를 조회합니다.
     */
    public long getActiveUserCount(String concertIdStr) {
        String activeKey = "queue:concert:" + concertIdStr + ":active";
        Long count = redisTemplate.opsForZSet().zCard(activeKey);
        return count != null ? count : 0L;
    }
}