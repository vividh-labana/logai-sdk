package com.logai.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a parsed stack trace with structured information.
 */
public class ParsedStackTrace {

    private final String exceptionClass;
    private final String exceptionMessage;
    private final List<StackFrame> frames;
    private final ParsedStackTrace causedBy;

    public ParsedStackTrace(String exceptionClass, String exceptionMessage, List<StackFrame> frames, ParsedStackTrace causedBy) {
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
        this.frames = frames != null ? new ArrayList<>(frames) : new ArrayList<>();
        this.causedBy = causedBy;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public List<StackFrame> getFrames() {
        return frames;
    }

    public ParsedStackTrace getCausedBy() {
        return causedBy;
    }

    /**
     * Get frames that are not from frameworks (likely user code).
     */
    public List<StackFrame> getUserFrames() {
        return frames.stream()
                .filter(f -> !f.isFrameworkFrame())
                .collect(Collectors.toList());
    }

    /**
     * Get the top N non-framework frames for fingerprinting.
     */
    public List<StackFrame> getTopUserFrames(int n) {
        return getUserFrames().stream()
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Get the first frame (top of stack).
     */
    public StackFrame getTopFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    /**
     * Get the first user (non-framework) frame.
     */
    public StackFrame getFirstUserFrame() {
        return frames.stream()
                .filter(f -> !f.isFrameworkFrame())
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the root cause exception (deepest caused-by).
     */
    public ParsedStackTrace getRootCause() {
        ParsedStackTrace current = this;
        while (current.causedBy != null) {
            current = current.causedBy;
        }
        return current;
    }

    /**
     * Generate a fingerprint for clustering similar stack traces.
     * Uses the top N non-framework frames.
     */
    public String fingerprint(int frameCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(exceptionClass != null ? exceptionClass : "Unknown");
        
        List<StackFrame> userFrames = getTopUserFrames(frameCount);
        for (StackFrame frame : userFrames) {
            sb.append("|").append(frame.fingerprint());
        }
        
        return sb.toString();
    }

    /**
     * Check if this stack trace has any user (non-framework) frames.
     */
    public boolean hasUserFrames() {
        return frames.stream().anyMatch(f -> !f.isFrameworkFrame());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(exceptionClass);
        if (exceptionMessage != null) {
            sb.append(": ").append(exceptionMessage);
        }
        sb.append("\n");
        
        for (StackFrame frame : frames) {
            sb.append("\tat ").append(frame).append("\n");
        }
        
        if (causedBy != null) {
            sb.append("Caused by: ").append(causedBy);
        }
        
        return sb.toString();
    }
}

