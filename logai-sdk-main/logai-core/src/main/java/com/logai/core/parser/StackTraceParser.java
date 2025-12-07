package com.logai.core.parser;

import com.logai.core.model.ParsedStackTrace;
import com.logai.core.model.StackFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Java stack traces.
 * Extracts structured information from stack trace strings.
 */
public class StackTraceParser {

    // Pattern for exception line: "java.lang.NullPointerException: message"
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
            "^([\\w.$]+(?:Exception|Error|Throwable))(?::\\s*(.*))?$"
    );

    // Pattern for stack frame: "at com.example.Class.method(File.java:123)"
    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "^\\s*at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([^)]+)\\)$"
    );

    // Pattern for file location: "File.java:123" or "Native Method" or "Unknown Source"
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "^([\\w]+\\.java):(\\d+)$"
    );

    // Pattern for "Caused by:" lines
    private static final Pattern CAUSED_BY_PATTERN = Pattern.compile(
            "^Caused by:\\s*(.+)$"
    );

    // Pattern for "... N more" lines
    private static final Pattern MORE_PATTERN = Pattern.compile(
            "^\\s*\\.\\.\\.\\s*(\\d+)\\s+more\\s*$"
    );

    /**
     * Parse a stack trace string into a structured representation.
     */
    public ParsedStackTrace parse(String stackTrace) {
        if (stackTrace == null || stackTrace.isEmpty()) {
            return null;
        }

        String[] lines = stackTrace.split("\\r?\\n");
        return parseLines(lines, 0).stackTrace;
    }

    /**
     * Parse a Throwable into a structured representation.
     */
    public ParsedStackTrace parse(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        List<StackFrame> frames = new ArrayList<>();
        for (StackTraceElement element : throwable.getStackTrace()) {
            frames.add(new StackFrame(
                    element.getClassName(),
                    element.getMethodName(),
                    element.getFileName(),
                    element.getLineNumber(),
                    element.isNativeMethod()
            ));
        }

        ParsedStackTrace causedBy = null;
        if (throwable.getCause() != null) {
            causedBy = parse(throwable.getCause());
        }

        return new ParsedStackTrace(
                throwable.getClass().getName(),
                throwable.getMessage(),
                frames,
                causedBy
        );
    }

    private ParseResult parseLines(String[] lines, int startIndex) {
        if (startIndex >= lines.length) {
            return new ParseResult(null, startIndex);
        }

        String exceptionClass = null;
        String exceptionMessage = null;
        List<StackFrame> frames = new ArrayList<>();
        ParsedStackTrace causedBy = null;
        int currentIndex = startIndex;

        // Parse exception line
        String firstLine = lines[currentIndex].trim();
        Matcher exceptionMatcher = EXCEPTION_PATTERN.matcher(firstLine);
        if (exceptionMatcher.matches()) {
            exceptionClass = exceptionMatcher.group(1);
            exceptionMessage = exceptionMatcher.group(2);
            currentIndex++;
        } else {
            // Try to extract exception info even if format doesn't match exactly
            int colonIndex = firstLine.indexOf(':');
            if (colonIndex > 0) {
                exceptionClass = firstLine.substring(0, colonIndex).trim();
                exceptionMessage = firstLine.substring(colonIndex + 1).trim();
            } else {
                exceptionClass = firstLine;
            }
            currentIndex++;
        }

        // Parse stack frames
        while (currentIndex < lines.length) {
            String line = lines[currentIndex];

            // Check for "Caused by:"
            Matcher causedByMatcher = CAUSED_BY_PATTERN.matcher(line);
            if (causedByMatcher.matches()) {
                // Extract exception info from "Caused by:" line and parse remaining
                String causedByExceptionLine = causedByMatcher.group(1);
                ParseResult causedByResult = parseCausedBy(causedByExceptionLine, lines, currentIndex + 1);
                causedBy = causedByResult.stackTrace;
                currentIndex = causedByResult.endIndex;
                break;
            }

            // Check for "... N more"
            Matcher moreMatcher = MORE_PATTERN.matcher(line);
            if (moreMatcher.matches()) {
                currentIndex++;
                continue;
            }

            // Try to parse as stack frame
            StackFrame frame = parseFrame(line);
            if (frame != null) {
                frames.add(frame);
                currentIndex++;
            } else {
                // Not a stack frame, might be the start of a new exception or end
                break;
            }
        }

        ParsedStackTrace stackTrace = new ParsedStackTrace(exceptionClass, exceptionMessage, frames, causedBy);
        return new ParseResult(stackTrace, currentIndex);
    }

    private StackFrame parseFrame(String line) {
        Matcher frameMatcher = FRAME_PATTERN.matcher(line.trim());
        if (!frameMatcher.matches()) {
            return null;
        }

        String className = frameMatcher.group(1);
        String methodName = frameMatcher.group(2);
        String locationPart = frameMatcher.group(3);

        String fileName = null;
        int lineNumber = -1;
        boolean nativeMethod = false;

        if ("Native Method".equals(locationPart)) {
            nativeMethod = true;
        } else if (!"Unknown Source".equals(locationPart)) {
            Matcher locationMatcher = LOCATION_PATTERN.matcher(locationPart);
            if (locationMatcher.matches()) {
                fileName = locationMatcher.group(1);
                lineNumber = Integer.parseInt(locationMatcher.group(2));
            } else {
                // Just file name without line number
                fileName = locationPart;
            }
        }

        return new StackFrame(className, methodName, fileName, lineNumber, nativeMethod);
    }

    /**
     * Extract the first line (exception info) from a stack trace string.
     */
    public String extractExceptionLine(String stackTrace) {
        if (stackTrace == null || stackTrace.isEmpty()) {
            return null;
        }
        int newlineIndex = stackTrace.indexOf('\n');
        if (newlineIndex > 0) {
            return stackTrace.substring(0, newlineIndex).trim();
        }
        return stackTrace.trim();
    }

    /**
     * Extract exception class name from a stack trace.
     */
    public String extractExceptionClass(String stackTrace) {
        String firstLine = extractExceptionLine(stackTrace);
        if (firstLine == null) {
            return null;
        }

        Matcher matcher = EXCEPTION_PATTERN.matcher(firstLine);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        int colonIndex = firstLine.indexOf(':');
        if (colonIndex > 0) {
            return firstLine.substring(0, colonIndex).trim();
        }
        return firstLine;
    }

    /**
     * Parse a "Caused by:" section where we already extracted the exception line.
     */
    private ParseResult parseCausedBy(String exceptionLine, String[] lines, int startIndex) {
        String exceptionClass = null;
        String exceptionMessage = null;
        
        // Parse the exception line
        Matcher exceptionMatcher = EXCEPTION_PATTERN.matcher(exceptionLine.trim());
        if (exceptionMatcher.matches()) {
            exceptionClass = exceptionMatcher.group(1);
            exceptionMessage = exceptionMatcher.group(2);
        } else {
            int colonIndex = exceptionLine.indexOf(':');
            if (colonIndex > 0) {
                exceptionClass = exceptionLine.substring(0, colonIndex).trim();
                exceptionMessage = exceptionLine.substring(colonIndex + 1).trim();
            } else {
                exceptionClass = exceptionLine.trim();
            }
        }
        
        List<StackFrame> frames = new ArrayList<>();
        ParsedStackTrace causedBy = null;
        int currentIndex = startIndex;
        
        // Parse stack frames
        while (currentIndex < lines.length) {
            String line = lines[currentIndex];
            
            // Check for another "Caused by:"
            Matcher causedByMatcher = CAUSED_BY_PATTERN.matcher(line);
            if (causedByMatcher.matches()) {
                String nestedExceptionLine = causedByMatcher.group(1);
                ParseResult nestedResult = parseCausedBy(nestedExceptionLine, lines, currentIndex + 1);
                causedBy = nestedResult.stackTrace;
                currentIndex = nestedResult.endIndex;
                break;
            }
            
            // Check for "... N more"
            Matcher moreMatcher = MORE_PATTERN.matcher(line);
            if (moreMatcher.matches()) {
                currentIndex++;
                continue;
            }
            
            // Try to parse as stack frame
            StackFrame frame = parseFrame(line);
            if (frame != null) {
                frames.add(frame);
                currentIndex++;
            } else {
                break;
            }
        }
        
        ParsedStackTrace stackTrace = new ParsedStackTrace(exceptionClass, exceptionMessage, frames, causedBy);
        return new ParseResult(stackTrace, currentIndex);
    }

    private static class ParseResult {
        final ParsedStackTrace stackTrace;
        final int endIndex;

        ParseResult(ParsedStackTrace stackTrace, int endIndex) {
            this.stackTrace = stackTrace;
            this.endIndex = endIndex;
        }
    }
}

