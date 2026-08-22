package com.huang.demo.common.resilience;

import org.slf4j.Logger;

import java.util.concurrent.Callable;

public final class DependencyRetryTemplate {

    private DependencyRetryTemplate() {
    }

    public static <T> T execute(String scene,
                                int maxRetryTimes,
                                long backoffMillis,
                                Logger log,
                                Callable<T> callable) throws Exception {
        Exception lastException = null;
        int attempts = Math.max(0, maxRetryTimes) + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return callable.call();
            } catch (Exception ex) {
                lastException = ex;
                if (attempt >= attempts) {
                    break;
                }
                if (log != null) {
                    log.warn("external dependency call failed, will retry, scene={}, attempt={}, maxAttempts={}",
                            scene, attempt, attempts, ex);
                }
                sleep(backoffMillis);
            }
        }
        throw lastException;
    }

    public static void execute(String scene,
                               int maxRetryTimes,
                               long backoffMillis,
                               Logger log,
                               RunnableWithException runnable) throws Exception {
        execute(scene, maxRetryTimes, backoffMillis, log, () -> {
            runnable.run();
            return null;
        });
    }

    private static void sleep(long backoffMillis) {
        if (backoffMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待依赖重试时被中断", ex);
        }
    }

    public interface RunnableWithException {

        void run() throws Exception;
    }
}
