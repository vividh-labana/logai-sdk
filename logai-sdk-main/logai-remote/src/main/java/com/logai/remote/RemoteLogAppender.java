package com.logai.remote;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Logback appender that sends logs to LogAI cloud.
 * 
 * Minimal configuration in logback.xml (only appId required!):
 * <pre>
 * &lt;appender name="LOGAI" class="com.logai.remote.RemoteLogAppender"&gt;
 *     &lt;appId&gt;your-app-id-from-dashboard&lt;/appId&gt;
 * &lt;/appender&gt;
 * </pre>
 * 
 * Optional configuration:
 * <pre>
 * &lt;appender name="LOGAI" class="com.logai.remote.RemoteLogAppender"&gt;
 *     &lt;appId&gt;your-app-id&lt;/appId&gt;
 *     &lt;threshold&gt;WARN&lt;/threshold&gt;
 *     &lt;batchSize&gt;50&lt;/batchSize&gt;
 *     &lt;flushIntervalMs&gt;5000&lt;/flushIntervalMs&gt;
 * &lt;/appender&gt;
 * </pre>
 */
public class RemoteLogAppender extends AppenderBase<ILoggingEvent> {

    // LogAI Cloud defaults - users don't need to configure these!
    private static final String DEFAULT_SUPABASE_URL = "https://rdlhvdpuxddbryhepukn.supabase.co";
    private static final String DEFAULT_SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJkbGh2ZHB1eGRkYnJ5aGVwdWtuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjQ0MDQyNjksImV4cCI6MjA3OTk4MDI2OX0.8Pm2NH4twqhtJgBxdysbmB-y6V-dh_-FHwyUJUHG5kk";
    
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    private static final int DEFAULT_QUEUE_SIZE = 5000;

    // Configuration properties (with sensible defaults)
    private String supabaseUrl = DEFAULT_SUPABASE_URL;
    private String supabaseKey = DEFAULT_SUPABASE_KEY;
    private String appId;  // Only this is required!
    private String threshold = "WARN";
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
    private int queueSize = DEFAULT_QUEUE_SIZE;
    private boolean includeCallerData = true;

    // Internal state
    private SupabaseClient supabaseClient;
    private BlockingQueue<LogEntry> queue;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private LogLevel thresholdLevel;

    @Override
    public void start() {
        if (isStarted()) {
            return;
        }

        // Validate configuration - only appId is required!
        if (appId == null || appId.isEmpty()) {
            addError("appId is required - get it from the LogAI dashboard at https://logai-frontend-app.vercel.app");
            return;
        }

        thresholdLevel = LogLevel.fromString(threshold);
        queue = new LinkedBlockingQueue<>(queueSize);

        try {
            supabaseClient = new SupabaseClient(supabaseUrl, supabaseKey);
        } catch (Exception e) {
            addError("Failed to initialize SupabaseClient", e);
            return;
        }

        // Start background flush task
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "logai-remote-flusher");
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
        addInfo("LogAI Remote Appender started - sending to " + supabaseUrl);
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
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Flush remaining entries
        flushBatch();

        super.stop();
        addInfo("LogAI Remote Appender stopped");
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
            LogEntry entry = convertToLogEntry(event);
            
            // Non-blocking offer - drop if queue is full
            if (!queue.offer(entry)) {
                addWarn("LogAI remote queue full, dropping log entry");
            }

            // Flush immediately if batch size reached
            if (queue.size() >= batchSize) {
                scheduler.submit(this::flushBatch);
            }
        } catch (Exception e) {
            addError("Failed to process log event", e);
        }
    }

    /**
     * Flush queued entries to Supabase.
     */
    private void flushBatch() {
        if (queue.isEmpty()) {
            return;
        }

        List<LogEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);

        if (!batch.isEmpty()) {
            try {
                boolean success = supabaseClient.insertLogEntries(appId, batch);
                if (!success) {
                    addError("Failed to send log batch to Supabase");
                    // Could implement retry logic here
                }
            } catch (Exception e) {
                addError("Error sending logs to Supabase", e);
            }
        }
    }

    private LogEntry convertToLogEntry(ILoggingEvent event) {
        LogEntry.Builder builder = LogEntry.builder()
                .timestamp(Instant.ofEpochMilli(event.getTimeStamp()))
                .level(convertLevel(event.getLevel()))
                .logger(event.getLoggerName())
                .message(event.getFormattedMessage())
                .threadName(event.getThreadName());

        // Extract stack trace if present
        if (event.getThrowableProxy() != null) {
            builder.stackTrace(formatThrowable(event.getThrowableProxy()));
            
            // Extract location from first stack frame
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable.getStackTraceElementProxyArray() != null && 
                throwable.getStackTraceElementProxyArray().length > 0) {
                StackTraceElementProxy firstFrame = throwable.getStackTraceElementProxyArray()[0];
                StackTraceElement element = firstFrame.getStackTraceElement();
                builder.className(element.getClassName());
                builder.methodName(element.getMethodName());
                builder.fileName(element.getFileName());
                builder.lineNumber(element.getLineNumber());
            }
        }

        // Get caller info if no exception
        if (event.getThrowableProxy() == null && event.hasCallerData()) {
            StackTraceElement[] callerData = event.getCallerData();
            if (callerData != null && callerData.length > 0) {
                StackTraceElement caller = callerData[0];
                builder.className(caller.getClassName());
                builder.methodName(caller.getMethodName());
                builder.fileName(caller.getFileName());
                builder.lineNumber(caller.getLineNumber());
            }
        }

        // Extract MDC context
        Map<String, String> mdcMap = event.getMDCPropertyMap();
        if (mdcMap != null && !mdcMap.isEmpty()) {
            builder.mdcContext(new HashMap<>(mdcMap));
            
            // Extract traceId if present
            if (mdcMap.containsKey("traceId")) {
                builder.traceId(mdcMap.get("traceId"));
            }
        }

        return builder.build();
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

    private String formatThrowable(IThrowableProxy throwableProxy) {
        StringBuilder sb = new StringBuilder();
        formatThrowableRecursive(throwableProxy, sb, "");
        return sb.toString();
    }

    private void formatThrowableRecursive(IThrowableProxy throwable, StringBuilder sb, String prefix) {
        sb.append(prefix)
          .append(throwable.getClassName())
          .append(": ")
          .append(throwable.getMessage())
          .append("\n");

        for (StackTraceElementProxy step : throwable.getStackTraceElementProxyArray()) {
            sb.append("\tat ")
              .append(step.getStackTraceElement())
              .append("\n");
        }

        if (throwable.getCause() != null) {
            sb.append("Caused by: ");
            formatThrowableRecursive(throwable.getCause(), sb, "");
        }
    }

    // Configuration setters (called by Logback)

    public void setSupabaseUrl(String supabaseUrl) {
        this.supabaseUrl = supabaseUrl;
    }

    public void setSupabaseKey(String supabaseKey) {
        this.supabaseKey = supabaseKey;
    }

    public void setAppId(String appId) {
        this.appId = appId;
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
    public int getPendingCount() {
        return queue != null ? queue.size() : 0;
    }
}

