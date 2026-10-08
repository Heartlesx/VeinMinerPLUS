package com.extrarawstyle.veinminerplus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import net.neoforged.fml.loading.FMLPaths;

/** Lightweight lifecycle log kept separate from Minecraft's rolling latest.log. */
final class PerformanceFileLog {
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get()
            .resolve("logs").resolve("veinminerplus-performance.log");
    private static final long HEALTH_CHECK_INTERVAL_NANOS = 1_000_000_000L;
    private static long nextHealthCheckNanos;

    /**
     * Reopens the telemetry file if it was removed while the server was running.
     * A BufferedWriter may continue writing to an unlinked file, so merely creating
     * a new empty path is not enough; restart the logger lifecycle as well.
     */
    static synchronized void ensureHealthy() {
        long now = System.nanoTime();
        if (now < nextHealthCheckNanos) {
            return;
        }
        nextHealthCheckNanos = now + HEALTH_CHECK_INTERVAL_NANOS;
        if (writer != null && Files.exists(LOG_PATH)) {
            return;
        }
        // Calling the lifecycle methods directly avoids reflection on every
        // recovery and keeps the writer state under the same monitor.
        stop();
        start();
    }
    private static BufferedWriter writer;

    private PerformanceFileLog() {
    }

    static synchronized void start() {
        if (writer != null) {
            return;
        }
        try {
            Files.createDirectories(LOG_PATH.getParent());
            writer = Files.newBufferedWriter(LOG_PATH, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            write("event=logger_started path=" + LOG_PATH.toAbsolutePath());
        } catch (IOException error) {
            VeinMinerPlus.LOGGER.error("Unable to open VeinMinerPlus performance log {}", LOG_PATH, error);
        }
    }

    static synchronized void write(String message) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(Instant.now() + " " + message);
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            VeinMinerPlus.LOGGER.warn("Unable to write VeinMinerPlus performance log", error);
        }
    }

    static synchronized void stop() {
        if (writer == null) {
            return;
        }
        try {
            write("event=logger_stopped");
            writer.close();
        } catch (IOException error) {
            VeinMinerPlus.LOGGER.warn("Unable to close VeinMinerPlus performance log", error);
        } finally {
            writer = null;
            nextHealthCheckNanos = 0L;
        }
    }
}
