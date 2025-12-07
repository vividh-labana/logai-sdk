package com.logai.core.model;

/**
 * Log level enumeration matching standard logging frameworks.
 */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int getSeverity() {
        return severity;
    }

    public boolean isAtLeast(LogLevel other) {
        return this.severity >= other.severity;
    }

    /**
     * Parse a log level from string, case-insensitive.
     */
    public static LogLevel fromString(String level) {
        if (level == null || level.isEmpty()) {
            return INFO;
        }
        try {
            return LogLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Handle common variations
            String upper = level.toUpperCase();
            if (upper.equals("WARNING")) {
                return WARN;
            }
            if (upper.equals("SEVERE")) {
                return ERROR;
            }
            return INFO;
        }
    }
}

