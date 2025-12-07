package com.logai.core.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a cluster of similar errors grouped by their characteristics.
 */
public class ErrorCluster {

    private String id;
    private String fingerprint;
    private String exceptionClass;
    private String messagePattern;
    private String primaryFile;
    private Integer primaryLine;
    private String primaryMethod;
    private String primaryClass;
    private List<LogEntry> entries;
    private Instant firstSeen;
    private Instant lastSeen;
    private int occurrenceCount;
    private ClusterSeverity severity;

    public ErrorCluster() {
        this.entries = new ArrayList<>();
        this.occurrenceCount = 0;
    }

    public ErrorCluster(String fingerprint) {
        this();
        this.fingerprint = fingerprint;
        this.id = generateId(fingerprint);
    }

    private static String generateId(String fingerprint) {
        // Create a short readable ID from the fingerprint
        int hash = fingerprint.hashCode();
        return String.format("ERR-%08X", hash & 0xFFFFFFFFL);
    }

    public void addEntry(LogEntry entry) {
        entries.add(entry);
        occurrenceCount++;
        
        Instant entryTime = entry.getTimestamp();
        if (firstSeen == null || entryTime.isBefore(firstSeen)) {
            firstSeen = entryTime;
        }
        if (lastSeen == null || entryTime.isAfter(lastSeen)) {
            lastSeen = entryTime;
        }
        
        // Update primary location from first entry with location info
        if (primaryFile == null && entry.getFileName() != null) {
            primaryFile = entry.getFileName();
            primaryLine = entry.getLineNumber();
            primaryMethod = entry.getMethodName();
            primaryClass = entry.getClassName();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getMessagePattern() {
        return messagePattern;
    }

    public void setMessagePattern(String messagePattern) {
        this.messagePattern = messagePattern;
    }

    public String getPrimaryFile() {
        return primaryFile;
    }

    public void setPrimaryFile(String primaryFile) {
        this.primaryFile = primaryFile;
    }

    public Integer getPrimaryLine() {
        return primaryLine;
    }

    public void setPrimaryLine(Integer primaryLine) {
        this.primaryLine = primaryLine;
    }

    public String getPrimaryMethod() {
        return primaryMethod;
    }

    public void setPrimaryMethod(String primaryMethod) {
        this.primaryMethod = primaryMethod;
    }

    public String getPrimaryClass() {
        return primaryClass;
    }

    public void setPrimaryClass(String primaryClass) {
        this.primaryClass = primaryClass;
    }

    public List<LogEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<LogEntry> entries) {
        this.entries = entries;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public ClusterSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(ClusterSeverity severity) {
        this.severity = severity;
    }

    /**
     * Calculate severity based on occurrence count and recency.
     */
    public ClusterSeverity calculateSeverity() {
        if (occurrenceCount >= 100) {
            return ClusterSeverity.CRITICAL;
        } else if (occurrenceCount >= 50) {
            return ClusterSeverity.HIGH;
        } else if (occurrenceCount >= 10) {
            return ClusterSeverity.MEDIUM;
        } else {
            return ClusterSeverity.LOW;
        }
    }

    /**
     * Get the most recent log entry in this cluster.
     */
    public LogEntry getMostRecentEntry() {
        return entries.stream()
                .max((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .orElse(null);
    }

    /**
     * Get a representative sample of entries (first, middle, last).
     */
    public List<LogEntry> getSampleEntries(int maxSamples) {
        if (entries.size() <= maxSamples) {
            return new ArrayList<>(entries);
        }
        
        List<LogEntry> samples = new ArrayList<>();
        samples.add(entries.get(0)); // First
        
        int step = entries.size() / (maxSamples - 1);
        for (int i = 1; i < maxSamples - 1; i++) {
            samples.add(entries.get(i * step));
        }
        
        samples.add(entries.get(entries.size() - 1)); // Last
        return samples;
    }

    /**
     * Get the full location string (class.method:line).
     */
    public String getFullLocation() {
        StringBuilder sb = new StringBuilder();
        if (primaryClass != null) {
            sb.append(primaryClass);
        }
        if (primaryMethod != null) {
            sb.append(".").append(primaryMethod);
        }
        if (primaryLine != null) {
            sb.append(":").append(primaryLine);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ErrorCluster that = (ErrorCluster) o;
        return Objects.equals(fingerprint, that.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint);
    }

    @Override
    public String toString() {
        return "ErrorCluster{" +
                "id='" + id + '\'' +
                ", exceptionClass='" + exceptionClass + '\'' +
                ", occurrenceCount=" + occurrenceCount +
                ", firstSeen=" + firstSeen +
                ", lastSeen=" + lastSeen +
                ", location=" + getFullLocation() +
                '}';
    }

    public enum ClusterSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}

