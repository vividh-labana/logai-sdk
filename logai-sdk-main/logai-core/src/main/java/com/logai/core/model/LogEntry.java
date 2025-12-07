package com.logai.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Domain model representing an enriched log entry.
 * Contains the original log information plus metadata extracted from the runtime.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogEntry {

    private Long id;
    private Instant timestamp;
    private LogLevel level;
    private String logger;
    private String message;
    private String stackTrace;
    private String fileName;
    private Integer lineNumber;
    private String methodName;
    private String className;
    private String traceId;
    private String threadName;
    private Map<String, String> mdcContext;
    private Instant createdAt;

    public LogEntry() {
        this.createdAt = Instant.now();
    }

    public LogEntry(LogLevel level, String logger, String message) {
        this();
        this.timestamp = Instant.now();
        this.level = level;
        this.logger = logger;
        this.message = message;
    }

    // Builder pattern for fluent construction
    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public LogLevel getLevel() {
        return level;
    }

    public void setLevel(LogLevel level) {
        this.level = level;
    }

    public String getLogger() {
        return logger;
    }

    public void setLogger(String logger) {
        this.logger = logger;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public Map<String, String> getMdcContext() {
        return mdcContext;
    }

    public void setMdcContext(Map<String, String> mdcContext) {
        this.mdcContext = mdcContext;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Check if this log entry represents an error (ERROR or higher level).
     */
    public boolean isError() {
        return level == LogLevel.ERROR || level == LogLevel.FATAL;
    }

    /**
     * Check if this log entry has a stack trace.
     */
    public boolean hasStackTrace() {
        return stackTrace != null && !stackTrace.isEmpty();
    }

    /**
     * Get the fully qualified class name with method.
     */
    public String getFullLocation() {
        if (className != null && methodName != null) {
            return className + "." + methodName;
        }
        return className;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogEntry logEntry = (LogEntry) o;
        return Objects.equals(id, logEntry.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "LogEntry{" +
                "id=" + id +
                ", timestamp=" + timestamp +
                ", level=" + level +
                ", logger='" + logger + '\'' +
                ", message='" + (message != null && message.length() > 50 ? message.substring(0, 50) + "..." : message) + '\'' +
                ", className='" + className + '\'' +
                ", methodName='" + methodName + '\'' +
                ", lineNumber=" + lineNumber +
                '}';
    }

    public static class Builder {
        private final LogEntry entry = new LogEntry();

        public Builder id(Long id) {
            entry.setId(id);
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            entry.setTimestamp(timestamp);
            return this;
        }

        public Builder level(LogLevel level) {
            entry.setLevel(level);
            return this;
        }

        public Builder logger(String logger) {
            entry.setLogger(logger);
            return this;
        }

        public Builder message(String message) {
            entry.setMessage(message);
            return this;
        }

        public Builder stackTrace(String stackTrace) {
            entry.setStackTrace(stackTrace);
            return this;
        }

        public Builder fileName(String fileName) {
            entry.setFileName(fileName);
            return this;
        }

        public Builder lineNumber(Integer lineNumber) {
            entry.setLineNumber(lineNumber);
            return this;
        }

        public Builder methodName(String methodName) {
            entry.setMethodName(methodName);
            return this;
        }

        public Builder className(String className) {
            entry.setClassName(className);
            return this;
        }

        public Builder traceId(String traceId) {
            entry.setTraceId(traceId);
            return this;
        }

        public Builder threadName(String threadName) {
            entry.setThreadName(threadName);
            return this;
        }

        public Builder mdcContext(Map<String, String> mdcContext) {
            entry.setMdcContext(mdcContext);
            return this;
        }

        public LogEntry build() {
            return entry;
        }
    }
}

