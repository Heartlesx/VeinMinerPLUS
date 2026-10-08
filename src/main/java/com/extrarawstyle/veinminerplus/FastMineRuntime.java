package com.extrarawstyle.veinminerplus;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns CPU-only workers used by fast-mining snapshots. World access stays on the server thread. */
final class FastMineRuntime {
    private static final int MAX_QUEUE_SIZE = 128;
    private static final long SHUTDOWN_WAIT_MILLIS = 2_000L;
    private static ExecutorService executor;

    private FastMineRuntime() {
    }

    static synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, Math.min(4, processors - 2));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "veinminerplus-fast-scan-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_SIZE), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    static synchronized void stop() {
        ExecutorService current = executor;
        executor = null;
        if (current == null) {
            return;
        }
        current.shutdownNow();
        try {
            if (!current.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                VeinMinerPlus.LOGGER.warn("Fast mining workers did not stop within {} ms", SHUTDOWN_WAIT_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            VeinMinerPlus.LOGGER.warn("Interrupted while stopping fast mining workers");
        }
    }

    static synchronized <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
        if (executor == null || executor.isShutdown()) {
            throw new IllegalStateException("Fast mining runtime is not running");
        }
        return executor.submit(task);
    }
}
