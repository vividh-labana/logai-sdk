package com.logai.core.analysis;

import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.core.model.ParsedStackTrace;
import com.logai.core.model.StackFrame;
import com.logai.core.parser.StackTraceParser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups similar errors into clusters for analysis.
 * Uses multiple strategies: stack trace fingerprinting, message similarity, and location matching.
 */
public class ErrorClusterer {

    private static final int DEFAULT_FINGERPRINT_FRAMES = 5;
    private static final double MESSAGE_SIMILARITY_THRESHOLD = 0.7;

    private final StackTraceParser stackTraceParser;
    private final int fingerprintFrameCount;

    public ErrorClusterer() {
        this(new StackTraceParser(), DEFAULT_FINGERPRINT_FRAMES);
    }

    public ErrorClusterer(StackTraceParser stackTraceParser, int fingerprintFrameCount) {
        this.stackTraceParser = stackTraceParser;
        this.fingerprintFrameCount = fingerprintFrameCount;
    }

    /**
     * Cluster a list of log entries by their error characteristics.
     */
    public List<ErrorCluster> cluster(List<LogEntry> entries) {
        Map<String, ErrorCluster> clusterMap = new LinkedHashMap<>();

        for (LogEntry entry : entries) {
            if (!entry.isError()) {
                continue;
            }

            String fingerprint = computeFingerprint(entry);
            ErrorCluster cluster = clusterMap.computeIfAbsent(fingerprint, ErrorCluster::new);
            
            cluster.addEntry(entry);
            
            // Set exception class if available
            if (cluster.getExceptionClass() == null && entry.hasStackTrace()) {
                String exceptionClass = stackTraceParser.extractExceptionClass(entry.getStackTrace());
                cluster.setExceptionClass(exceptionClass);
            }
            
            // Set message pattern from first entry
            if (cluster.getMessagePattern() == null) {
                cluster.setMessagePattern(normalizeMessage(entry.getMessage()));
            }
        }

        // Calculate severity for each cluster
        List<ErrorCluster> clusters = new ArrayList<>(clusterMap.values());
        clusters.forEach(c -> c.setSeverity(c.calculateSeverity()));

        // Sort by occurrence count (most frequent first)
        clusters.sort((a, b) -> Integer.compare(b.getOccurrenceCount(), a.getOccurrenceCount()));

        return clusters;
    }

    /**
     * Compute a fingerprint for an error entry.
     * The fingerprint is used to group similar errors together.
     */
    public String computeFingerprint(LogEntry entry) {
        StringBuilder fingerprint = new StringBuilder();

        // If we have a stack trace, use stack trace fingerprinting
        if (entry.hasStackTrace()) {
            ParsedStackTrace parsed = stackTraceParser.parse(entry.getStackTrace());
            if (parsed != null) {
                fingerprint.append(parsed.fingerprint(fingerprintFrameCount));
                return fingerprint.toString();
            }
        }

        // Fall back to location-based fingerprinting
        if (entry.getClassName() != null) {
            fingerprint.append(entry.getClassName());
        }
        if (entry.getMethodName() != null) {
            fingerprint.append(".").append(entry.getMethodName());
        }
        if (entry.getLineNumber() != null) {
            fingerprint.append(":").append(entry.getLineNumber());
        }

        // Add normalized message template if no location info
        if (fingerprint.length() == 0) {
            fingerprint.append(normalizeMessage(entry.getMessage()));
        }

        return fingerprint.toString();
    }

    /**
     * Normalize a message to create a template for matching.
     * Replaces variable parts like numbers, IDs, timestamps with placeholders.
     */
    public String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }

        String normalized = message
                // Replace UUIDs
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<UUID>")
                // Replace long numbers (like IDs)
                .replaceAll("\\b\\d{6,}\\b", "<ID>")
                // Replace timestamps
                .replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}", "<TIMESTAMP>")
                // Replace IP addresses
                .replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "<IP>")
                // Replace email addresses
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}", "<EMAIL>")
                // Replace quoted strings
                .replaceAll("\"[^\"]+\"", "\"<STRING>\"")
                .replaceAll("'[^']+'", "'<STRING>'")
                // Replace remaining numbers
                .replaceAll("\\b\\d+\\b", "<NUM>");

        return normalized.trim();
    }

    /**
     * Calculate Levenshtein distance between two strings (for message similarity).
     */
    public int levenshteinDistance(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return Integer.MAX_VALUE;
        }

        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /**
     * Calculate similarity ratio between two strings (0.0 to 1.0).
     */
    public double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }

        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) {
            return 1.0;
        }

        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * Check if two messages are similar enough to be clustered together.
     */
    public boolean areMessagesSimilar(String msg1, String msg2) {
        String norm1 = normalizeMessage(msg1);
        String norm2 = normalizeMessage(msg2);
        return calculateSimilarity(norm1, norm2) >= MESSAGE_SIMILARITY_THRESHOLD;
    }

    /**
     * Merge multiple clusters that are similar to each other.
     */
    public List<ErrorCluster> mergeSimilarClusters(List<ErrorCluster> clusters) {
        if (clusters.size() <= 1) {
            return clusters;
        }

        List<ErrorCluster> result = new ArrayList<>();
        boolean[] merged = new boolean[clusters.size()];

        for (int i = 0; i < clusters.size(); i++) {
            if (merged[i]) {
                continue;
            }

            ErrorCluster primary = clusters.get(i);
            
            for (int j = i + 1; j < clusters.size(); j++) {
                if (merged[j]) {
                    continue;
                }

                ErrorCluster secondary = clusters.get(j);
                
                // Check if clusters are similar enough to merge
                if (shouldMergeClusters(primary, secondary)) {
                    // Merge secondary into primary
                    for (LogEntry entry : secondary.getEntries()) {
                        primary.addEntry(entry);
                    }
                    merged[j] = true;
                }
            }

            result.add(primary);
        }

        // Recalculate severity after merging
        result.forEach(c -> c.setSeverity(c.calculateSeverity()));
        result.sort((a, b) -> Integer.compare(b.getOccurrenceCount(), a.getOccurrenceCount()));

        return result;
    }

    private boolean shouldMergeClusters(ErrorCluster c1, ErrorCluster c2) {
        // Same exception class and similar message
        if (Objects.equals(c1.getExceptionClass(), c2.getExceptionClass()) &&
            areMessagesSimilar(c1.getMessagePattern(), c2.getMessagePattern())) {
            return true;
        }

        // Same primary location
        if (c1.getPrimaryClass() != null && 
            Objects.equals(c1.getPrimaryClass(), c2.getPrimaryClass()) &&
            Objects.equals(c1.getPrimaryMethod(), c2.getPrimaryMethod()) &&
            Objects.equals(c1.getPrimaryLine(), c2.getPrimaryLine())) {
            return true;
        }

        return false;
    }
}

