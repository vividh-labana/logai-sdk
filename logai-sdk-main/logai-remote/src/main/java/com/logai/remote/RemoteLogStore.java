package com.logai.remote;

import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remote log store that uses Supabase as the backend.
 * Provides the same interface as the local LogStore but stores data remotely.
 */
public class RemoteLogStore implements AutoCloseable {

    private final SupabaseClient client;
    private final String appId;

    public RemoteLogStore(String supabaseUrl, String supabaseKey, String appId) {
        this.client = new SupabaseClient(supabaseUrl, supabaseKey);
        this.appId = appId;
    }

    public RemoteLogStore(SupabaseClient client, String appId) {
        this.client = client;
        this.appId = appId;
    }

    /**
     * Store a single log entry.
     */
    public boolean store(LogEntry entry) {
        return client.insertLogEntry(appId, entry);
    }

    /**
     * Store multiple log entries in a batch.
     */
    public boolean storeBatch(List<LogEntry> entries) {
        return client.insertLogEntries(appId, entries);
    }

    /**
     * Query log entries within a time range.
     */
    public List<LogEntry> queryByTimeRange(Instant start, Instant end) {
        return client.queryLogEntries(appId, start, end, 1000);
    }

    /**
     * Query log entries within a time range with limit.
     */
    public List<LogEntry> queryByTimeRange(Instant start, Instant end, int limit) {
        return client.queryLogEntries(appId, start, end, limit);
    }

    /**
     * Query error log entries (ERROR and FATAL levels).
     */
    public List<LogEntry> queryErrors(Instant start, Instant end) {
        return client.queryErrors(appId, start, end);
    }

    /**
     * Get or create an error cluster.
     */
    public Optional<String> upsertErrorCluster(ErrorCluster cluster) {
        return client.upsertErrorCluster(appId, cluster);
    }

    /**
     * Get all error clusters for the app.
     */
    public List<ErrorCluster> getErrorClusters() {
        return client.getErrorClusters(appId);
    }

    /**
     * Save analysis result for a cluster.
     */
    public boolean saveAnalysisResult(String clusterId, String explanation, String rootCause,
                                       String recommendation, String patch, String confidence) {
        return client.saveAnalysisResult(clusterId, explanation, rootCause, recommendation, patch, confidence);
    }

    /**
     * Create a new scan history record.
     */
    public Optional<String> createScanHistory() {
        return client.createScanHistory(appId);
    }

    /**
     * Update scan history with results.
     */
    public boolean updateScanHistory(String scanId, String status, int logsScanned,
                                      int errorsFound, int clustersCreated, int clustersAnalyzed,
                                      String errorMessage) {
        return client.updateScanHistory(scanId, status, logsScanned, errorsFound,
                clustersCreated, clustersAnalyzed, errorMessage);
    }

    /**
     * Get the app ID.
     */
    public String getAppId() {
        return appId;
    }

    /**
     * Check if the store is properly configured.
     */
    public boolean isConfigured() {
        return client.isConfigured() && appId != null && !appId.isEmpty();
    }

    @Override
    public void close() {
        // No resources to close for HTTP client
    }
}

