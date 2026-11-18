package com.turnstile.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 동시성 테스트에서 다수의 작업을 동시에 시작하고 시간 초과 및 작업 스레드의 예외를 호출자에게 전달하는 유틸리티 클래스입니다.
 */
public final class ConcurrentTestSupport {

    private ConcurrentTestSupport() {}

    /**
     * 동시 실행할 개별 테스트 작업을 정의하는 함수형 인터페이스입니다.
     */
    @FunctionalInterface
    public interface Action<T> {
        T run(int index) throws Exception;
    }

    /**
     * 지정된 수만큼의 가상 스레드를 생성해 모든 스레드가 준비된 시점에 작업을 동시에 실행하고 결과를 수집합니다.
     */
    public static <T> List<T> run(int count, Action<T> action) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(count); // 모든 작업 스레드의 준비 완료 대기용 래치
        CountDownLatch start = new CountDownLatch(1); // 동시 출발 신호 전달용 래치
        List<Future<T>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < count; index++) {
                final int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 시작 대기 시간 초과");
                    }
                    return action.run(taskIndex);
                }));
            }

            // 모든 스레드가 실행 직전 상태에 도달할 때까지 대기한 뒤 일괄 출발 신호 전달
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("작업 스레드 준비 시간 초과");
            }
            start.countDown();

            // 전체 동시 작업 완료 제한 시간(45초) 내에 각 스레드의 실행 결과 수집
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new java.util.concurrent.TimeoutException("동시 실행 완료 시간 초과");
                }
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            // 예외 발생 시 대기 중인 스레드의 교착 상태를 방지하기 위해 래치 해제 및 실행기 종료
            start.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 작업 스레드가 종료되지 않았습니다.");
            }
        }
    }
}