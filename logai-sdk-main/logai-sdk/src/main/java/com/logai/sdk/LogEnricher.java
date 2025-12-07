package com.logai.sdk;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;
import org.slf4j.MDC;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Enriches log events with additional metadata.
 * Extracts file name, line number, method name, and other contextual information.
 */
public class LogEnricher {

    private static final String TRACE_ID_KEY = "traceId";
    private static final int CALLER_STACK_DEPTH = 8; // Depth to find caller in stack

    /**
     * Enrich a Logback event into a LogEntry with full metadata.
     */
    public LogEntry enrich(ILoggingEvent event) {
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

        // If no exception, try to get caller info from the logging event
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
            if (mdcMap.containsKey(TRACE_ID_KEY)) {
                builder.traceId(mdcMap.get(TRACE_ID_KEY));
            }
        }

        return builder.build();
    }

    /**
     * Enrich from a Throwable directly.
     */
    public LogEntry enrichFromThrowable(Throwable throwable, String message, LogLevel level, String loggerName) {
        LogEntry.Builder builder = LogEntry.builder()
                .timestamp(Instant.now())
                .level(level)
                .logger(loggerName)
                .message(message != null ? message : throwable.getMessage())
                .threadName(Thread.currentThread().getName());

        // Format stack trace
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        builder.stackTrace(sw.toString());

        // Extract location from stack trace
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null && stackTrace.length > 0) {
            // Find first non-framework stack frame
            for (StackTraceElement element : stackTrace) {
                if (!isFrameworkClass(element.getClassName())) {
                    builder.className(element.getClassName());
                    builder.methodName(element.getMethodName());
                    builder.fileName(element.getFileName());
                    builder.lineNumber(element.getLineNumber());
                    break;
                }
            }
        }

        // Get current MDC context
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        if (mdcMap != null && !mdcMap.isEmpty()) {
            builder.mdcContext(mdcMap);
            if (mdcMap.containsKey(TRACE_ID_KEY)) {
                builder.traceId(mdcMap.get(TRACE_ID_KEY));
            }
        }

        return builder.build();
    }

    /**
     * Create a basic log entry without throwable.
     */
    public LogEntry createEntry(String message, LogLevel level, String loggerName) {
        LogEntry.Builder builder = LogEntry.builder()
                .timestamp(Instant.now())
                .level(level)
                .logger(loggerName)
                .message(message)
                .threadName(Thread.currentThread().getName());

        // Try to extract caller info from current stack
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack.length > CALLER_STACK_DEPTH) {
            for (int i = CALLER_STACK_DEPTH; i < stack.length; i++) {
                StackTraceElement element = stack[i];
                if (!isFrameworkClass(element.getClassName())) {
                    builder.className(element.getClassName());
                    builder.methodName(element.getMethodName());
                    builder.fileName(element.getFileName());
                    builder.lineNumber(element.getLineNumber());
                    break;
                }
            }
        }

        // Get current MDC context
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        if (mdcMap != null && !mdcMap.isEmpty()) {
            builder.mdcContext(mdcMap);
            if (mdcMap.containsKey(TRACE_ID_KEY)) {
                builder.traceId(mdcMap.get(TRACE_ID_KEY));
            }
        }

        return builder.build();
    }

    /**
     * Generate a unique trace ID.
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Set trace ID in MDC for the current thread.
     */
    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * Get current trace ID from MDC.
     */
    public static String getTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * Clear trace ID from MDC.
     */
    public static void clearTraceId() {
        MDC.remove(TRACE_ID_KEY);
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

    private boolean isFrameworkClass(String className) {
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("com.sun.") ||
               className.startsWith("jdk.") ||
               className.startsWith("org.slf4j.") ||
               className.startsWith("ch.qos.logback.") ||
               className.startsWith("com.logai.sdk.");
    }
}

