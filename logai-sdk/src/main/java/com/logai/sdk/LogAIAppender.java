package com.logai.sdk;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Custom Logback appender that intercepts logs and stores them for analysis.
 * 
 * This appender is designed to be lightweight and non-blocking:
 * - Uses an async queue to avoid blocking the main thread
 * - Batches writes to the database for efficiency
 * - Only stores logs at or above the configured threshold
 * 
 * Configuration in logback.xml:
 * <pre>
 * &lt;appender name="LOGAI" class="com.logai.sdk.LogAIAppender"&gt;
 *     &lt;dbPath&gt;logai.db&lt;/dbPath&gt;
 *     &lt;threshold&gt;WARN&lt;/threshold&gt;
 *     &lt;batchSize&gt;100&lt;/batchSize&gt;
 *     &lt;flushIntervalMs&gt;5000&lt;/flushIntervalMs&gt;
 * &lt;/appender&gt;
 * </pre>
 */
public class LogAIAppender extends AppenderBase<ILoggingEvent> {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    private static final int DEFAULT_QUEUE_SIZE = 10000;

    // Configuration properties
    private String dbPath = "logai.db";
    private String threshold = "WARN";
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
    private int queueSize = DEFAULT_QUEUE_SIZE;
    private boolean includeCallerData = true;

    // Internal state
    private LogStore logStore;
    private LogEnricher enricher;
    private BlockingQueue<LogEntry> queue;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private LogLevel thresholdLevel;

    @Override
    public void start() {
        if (isStarted()) {
            return;
        }

        thresholdLevel = LogLevel.fromString(threshold);
        enricher = new LogEnricher();
        queue = new LinkedBlockingQueue<>(queueSize);

        try {
            logStore = new LogStore(dbPath);
        } catch (Exception e) {
            addError("Failed to initialize LogStore", e);
            return;
        }

        // Start background flush task
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "logai-flusher");
            t.setDaemon(true);
            return t;
        });

        running = true;
        scheduler.scheduleAtFixedRate(
                this::flushBatch,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
        );

        super.start();
        addInfo("LogAI Appender started with threshold=" + threshold + ", dbPath=" + dbPath);
    }

    @Override
    public void stop() {
        if (!isStarted()) {
            return;
        }

        running = false;

        // Shutdown scheduler
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Flush remaining entries
        flushBatch();

        // Close log store
        if (logStore != null) {
            logStore.close();
        }

        super.stop();
        addInfo("LogAI Appender stopped");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running) {
            return;
        }

        // Check threshold
        LogLevel eventLevel = convertLevel(event.getLevel());
        if (!eventLevel.isAtLeast(thresholdLevel)) {
            return;
        }

        // Request caller data if needed
        if (includeCallerData) {
            event.getCallerData();
        }

        try {
            // Enrich and queue the log entry
            LogEntry entry = enricher.enrich(event);
            
            // Non-blocking offer - drop if queue is full
            if (!queue.offer(entry)) {
                addWarn("LogAI queue full, dropping log entry");
            }

            // Flush immediately if batch size reached
            if (queue.size() >= batchSize) {
                flushBatch();
            }
        } catch (Exception e) {
            addError("Failed to process log event", e);
        }
    }

    /**
     * Flush queued entries to the database.
     */
    private void flushBatch() {
        if (queue.isEmpty()) {
            return;
        }

        List<LogEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);

        if (!batch.isEmpty()) {
            try {
                logStore.storeBatch(batch);
            } catch (Exception e) {
                addError("Failed to flush log batch", e);
            }
        }
    }

    private LogLevel convertLevel(ch.qos.logback.classic.Level level) {
        if (level == null) {
            return LogLevel.INFO;
        }
        return switch (level.levelInt) {
            case ch.qos.logback.classic.Level.TRACE_INT -> LogLevel.TRACE;
            case ch.qos.logback.classic.Level.DEBUG_INT -> LogLevel.DEBUG;
            case ch.qos.logback.classic.Level.INFO_INT -> LogLevel.INFO;
            case ch.qos.logback.classic.Level.WARN_INT -> LogLevel.WARN;
            case ch.qos.logback.classic.Level.ERROR_INT -> LogLevel.ERROR;
            default -> LogLevel.INFO;
        };
    }

    // Configuration setters (called by Logback)

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    public void setThreshold(String threshold) {
        this.threshold = threshold;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setFlushIntervalMs(int flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public void setIncludeCallerData(boolean includeCallerData) {
        this.includeCallerData = includeCallerData;
    }

    // Getters for testing

    public String getDbPath() {
        return dbPath;
    }

    public String getThreshold() {
        return threshold;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getPendingCount() {
        return queue != null ? queue.size() : 0;
    }
}

