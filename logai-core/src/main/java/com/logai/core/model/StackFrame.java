package com.logai.core.model;

import java.util.Objects;

/**
 * Represents a single frame in a stack trace.
 */
public class StackFrame {

    private final String className;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;
    private final boolean nativeMethod;

    public StackFrame(String className, String methodName, String fileName, int lineNumber, boolean nativeMethod) {
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.nativeMethod = nativeMethod;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public boolean isNativeMethod() {
        return nativeMethod;
    }

    /**
     * Get simple class name without package.
     */
    public String getSimpleClassName() {
        if (className == null) {
            return null;
        }
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    /**
     * Get the package name.
     */
    public String getPackageName() {
        if (className == null) {
            return null;
        }
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(0, lastDot) : "";
    }

    /**
     * Check if this frame is from a framework/library (not user code).
     */
    public boolean isFrameworkFrame() {
        if (className == null) {
            return true;
        }
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("com.sun.") ||
               className.startsWith("jdk.") ||
               className.startsWith("org.springframework.") ||
               className.startsWith("org.apache.") ||
               className.startsWith("org.hibernate.") ||
               className.startsWith("org.slf4j.") ||
               className.startsWith("ch.qos.logback.") ||
               className.startsWith("com.zaxxer.hikari.") ||
               className.startsWith("io.netty.") ||
               className.startsWith("reactor.") ||
               className.startsWith("com.fasterxml.jackson.");
    }

    /**
     * Check if this frame has useful location information.
     */
    public boolean hasSourceInfo() {
        return fileName != null && lineNumber > 0;
    }

    /**
     * Generate a fingerprint for this frame (used for clustering).
     */
    public String fingerprint() {
        return className + "." + methodName + ":" + lineNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StackFrame that = (StackFrame) o;
        return lineNumber == that.lineNumber &&
               Objects.equals(className, that.className) &&
               Objects.equals(methodName, that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, lineNumber);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(className).append(".").append(methodName);
        if (nativeMethod) {
            sb.append("(Native Method)");
        } else if (fileName != null && lineNumber >= 0) {
            sb.append("(").append(fileName).append(":").append(lineNumber).append(")");
        } else if (fileName != null) {
            sb.append("(").append(fileName).append(")");
        } else {
            sb.append("(Unknown Source)");
        }
        return sb.toString();
    }
}

