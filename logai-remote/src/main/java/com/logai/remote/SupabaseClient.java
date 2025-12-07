package com.logai.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for Supabase REST API.
 * Handles communication with Supabase PostgreSQL database.
 */
public class SupabaseClient {

    private static final Logger logger = LoggerFactory.getLogger(SupabaseClient.class);
    
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final String REST_API_PATH = "/rest/v1";

    private final String supabaseUrl;
    private final String supabaseKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SupabaseClient(String supabaseUrl, String supabaseKey) {
        this.supabaseUrl = supabaseUrl.endsWith("/") 
                ? supabaseUrl.substring(0, supabaseUrl.length() - 1) 
                : supabaseUrl;
        this.supabaseKey = supabaseKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Insert a single log entry.
     */
    public boolean insertLogEntry(String appId, LogEntry entry) {
        try {
            ObjectNode json = logEntryToJson(appId, entry);
            return post("log_entries", json.toString());
        } catch (Exception e) {
            logger.error("Failed to insert log entry", e);
            return false;
        }
    }

    /**
     * Insert multiple log entries in a batch.
     */
    public boolean insertLogEntries(String appId, List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return true;
        }

        try {
            ArrayNode jsonArray = objectMapper.createArrayNode();
            for (LogEntry entry : entries) {
                jsonArray.add(logEntryToJson(appId, entry));
            }
            return post("log_entries", jsonArray.toString());
        } catch (Exception e) {
            logger.error("Failed to insert log entries batch", e);
            return false;
        }
    }

    /**
     * Query log entries for an app within a time range.
     */
    public List<LogEntry> queryLogEntries(String appId, Instant start, Instant end, int limit) {
        String query = String.format(
                "app_id=eq.%s&timestamp=gte.%s&timestamp=lte.%s&order=timestamp.desc&limit=%d",
                appId, start.toString(), end.toString(), limit
        );
        
        return queryAndParseLogEntries(query);
    }

    /**
     * Query error log entries for an app.
     */
    public List<LogEntry> queryErrors(String appId, Instant start, Instant end) {
        String query = String.format(
                "app_id=eq.%s&timestamp=gte.%s&timestamp=lte.%s&level=in.(ERROR,FATAL)&order=timestamp.desc",
                appId, start.toString(), end.toString()
        );
        
        return queryAndParseLogEntries(query);
    }

    /**
     * Get or create an error cluster.
     */
    public Optional<String> upsertErrorCluster(String appId, ErrorCluster cluster) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("app_id", appId);
            json.put("fingerprint", cluster.getFingerprint());
            json.put("exception_class", cluster.getExceptionClass());
            json.put("message_pattern", cluster.getMessagePattern());
            json.put("primary_file", cluster.getPrimaryFile());
            json.put("primary_line", cluster.getPrimaryLine());
            json.put("primary_method", cluster.getPrimaryMethod());
            json.put("primary_class", cluster.getPrimaryClass());
            json.put("occurrence_count", cluster.getOccurrenceCount());
            json.put("severity", cluster.getSeverity() != null ? cluster.getSeverity().name() : "MEDIUM");
            json.put("first_seen", cluster.getFirstSeen() != null ? cluster.getFirstSeen().toString() : Instant.now().toString());
            json.put("last_seen", cluster.getLastSeen() != null ? cluster.getLastSeen().toString() : Instant.now().toString());

            // Use upsert with on_conflict
            String response = postWithReturn("error_clusters?on_conflict=app_id,fingerprint", json.toString());
            if (response != null) {
                JsonNode responseJson = objectMapper.readTree(response);
                if (responseJson.isArray() && responseJson.size() > 0) {
                    return Optional.ofNullable(responseJson.get(0).get("id").asText());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to upsert error cluster", e);
            return Optional.empty();
        }
    }

    /**
     * Get all error clusters for an app.
     */
    public List<ErrorCluster> getErrorClusters(String appId) {
        String query = String.format("app_id=eq.%s&order=occurrence_count.desc", appId);
        
        try {
            String response = get("error_clusters?" + query);
            if (response == null) {
                return new ArrayList<>();
            }

            List<ErrorCluster> clusters = new ArrayList<>();
            JsonNode jsonArray = objectMapper.readTree(response);
            
            for (JsonNode node : jsonArray) {
                ErrorCluster cluster = new ErrorCluster(node.get("fingerprint").asText());
                cluster.setId(node.get("id").asText());
                cluster.setExceptionClass(getTextOrNull(node, "exception_class"));
                cluster.setMessagePattern(getTextOrNull(node, "message_pattern"));
                cluster.setPrimaryFile(getTextOrNull(node, "primary_file"));
                cluster.setPrimaryLine(node.has("primary_line") && !node.get("primary_line").isNull() 
                        ? node.get("primary_line").asInt() : null);
                cluster.setPrimaryMethod(getTextOrNull(node, "primary_method"));
                cluster.setPrimaryClass(getTextOrNull(node, "primary_class"));
                cluster.setOccurrenceCount(node.get("occurrence_count").asInt());
                
                String severity = getTextOrNull(node, "severity");
                if (severity != null) {
                    cluster.setSeverity(ErrorCluster.ClusterSeverity.valueOf(severity));
                }
                
                if (node.has("first_seen") && !node.get("first_seen").isNull()) {
                    cluster.setFirstSeen(Instant.parse(node.get("first_seen").asText()));
                }
                if (node.has("last_seen") && !node.get("last_seen").isNull()) {
                    cluster.setLastSeen(Instant.parse(node.get("last_seen").asText()));
                }
                
                clusters.add(cluster);
            }
            
            return clusters;
        } catch (Exception e) {
            logger.error("Failed to get error clusters", e);
            return new ArrayList<>();
        }
    }

    /**
     * Save analysis result for a cluster.
     */
    public boolean saveAnalysisResult(String clusterId, String explanation, String rootCause, 
                                       String recommendation, String patch, String confidence) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("cluster_id", clusterId);
            json.put("explanation", explanation);
            json.put("root_cause", rootCause);
            json.put("recommendation", recommendation);
            json.put("patch", patch);
            json.put("confidence", confidence);
            
            return post("analysis_results", json.toString());
        } catch (Exception e) {
            logger.error("Failed to save analysis result", e);
            return false;
        }
    }

    /**
     * Get all applications.
     */
    public List<Map<String, Object>> getApplications() {
        try {
            String response = get("applications?order=created_at.desc");
            if (response == null) {
                return new ArrayList<>();
            }
            
            return objectMapper.readValue(response, 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            logger.error("Failed to get applications", e);
            return new ArrayList<>();
        }
    }

    /**
     * Validate API key for an application.
     */
    public Optional<String> validateApiKey(String apiKey) {
        try {
            String response = get("applications?api_key=eq." + apiKey + "&select=id");
            if (response == null) {
                return Optional.empty();
            }
            
            JsonNode jsonArray = objectMapper.readTree(response);
            if (jsonArray.isArray() && jsonArray.size() > 0) {
                return Optional.of(jsonArray.get(0).get("id").asText());
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to validate API key", e);
            return Optional.empty();
        }
    }

    /**
     * Create a new scan history record.
     */
    public Optional<String> createScanHistory(String appId) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("app_id", appId);
            json.put("status", "RUNNING");
            
            String response = postWithReturn("scan_history", json.toString());
            if (response != null) {
                JsonNode responseJson = objectMapper.readTree(response);
                if (responseJson.isArray() && responseJson.size() > 0) {
                    return Optional.of(responseJson.get(0).get("id").asText());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to create scan history", e);
            return Optional.empty();
        }
    }

    /**
     * Update scan history with results.
     */
    public boolean updateScanHistory(String scanId, String status, int logsScanned, 
                                      int errorsFound, int clustersCreated, int clustersAnalyzed,
                                      String errorMessage) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("status", status);
            json.put("logs_scanned", logsScanned);
            json.put("errors_found", errorsFound);
            json.put("clusters_created", clustersCreated);
            json.put("clusters_analyzed", clustersAnalyzed);
            json.put("completed_at", Instant.now().toString());
            if (errorMessage != null) {
                json.put("error_message", errorMessage);
            }
            
            return patch("scan_history?id=eq." + scanId, json.toString());
        } catch (Exception e) {
            logger.error("Failed to update scan history", e);
            return false;
        }
    }

    // HTTP methods

    private boolean post(String endpoint, String body) {
        return postWithReturn(endpoint, body) != null;
    }

    private String postWithReturn(String endpoint, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + REST_API_PATH + "/" + endpoint))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=representation")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(DEFAULT_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                logger.error("Supabase POST error: {} - {}", response.statusCode(), response.body());
                return null;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to POST to Supabase", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String get(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + REST_API_PATH + "/" + endpoint))
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .GET()
                    .timeout(DEFAULT_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                logger.error("Supabase GET error: {} - {}", response.statusCode(), response.body());
                return null;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to GET from Supabase", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private boolean patch(String endpoint, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + REST_API_PATH + "/" + endpoint))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .timeout(DEFAULT_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            } else {
                logger.error("Supabase PATCH error: {} - {}", response.statusCode(), response.body());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to PATCH to Supabase", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    // Helper methods

    private ObjectNode logEntryToJson(String appId, LogEntry entry) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("app_id", appId);
        json.put("timestamp", entry.getTimestamp() != null ? entry.getTimestamp().toString() : Instant.now().toString());
        json.put("level", entry.getLevel() != null ? entry.getLevel().name() : "INFO");
        json.put("logger", entry.getLogger());
        json.put("message", entry.getMessage());
        json.put("stack_trace", entry.getStackTrace());
        json.put("file_name", entry.getFileName());
        json.put("line_number", entry.getLineNumber());
        json.put("method_name", entry.getMethodName());
        json.put("class_name", entry.getClassName());
        json.put("trace_id", entry.getTraceId());
        json.put("thread_name", entry.getThreadName());
        
        if (entry.getMdcContext() != null && !entry.getMdcContext().isEmpty()) {
            json.set("mdc_context", objectMapper.valueToTree(entry.getMdcContext()));
        }
        
        return json;
    }

    private List<LogEntry> queryAndParseLogEntries(String query) {
        try {
            String response = get("log_entries?" + query);
            if (response == null) {
                return new ArrayList<>();
            }

            List<LogEntry> entries = new ArrayList<>();
            JsonNode jsonArray = objectMapper.readTree(response);
            
            for (JsonNode node : jsonArray) {
                LogEntry entry = LogEntry.builder()
                        .id(node.get("id").asLong())
                        .timestamp(Instant.parse(node.get("timestamp").asText()))
                        .level(LogLevel.fromString(node.get("level").asText()))
                        .logger(getTextOrNull(node, "logger"))
                        .message(getTextOrNull(node, "message"))
                        .stackTrace(getTextOrNull(node, "stack_trace"))
                        .fileName(getTextOrNull(node, "file_name"))
                        .lineNumber(node.has("line_number") && !node.get("line_number").isNull() 
                                ? node.get("line_number").asInt() : null)
                        .methodName(getTextOrNull(node, "method_name"))
                        .className(getTextOrNull(node, "class_name"))
                        .traceId(getTextOrNull(node, "trace_id"))
                        .threadName(getTextOrNull(node, "thread_name"))
                        .build();
                entries.add(entry);
            }
            
            return entries;
        } catch (Exception e) {
            logger.error("Failed to query log entries", e);
            return new ArrayList<>();
        }
    }

    private String getTextOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /**
     * Check if the client is properly configured.
     */
    public boolean isConfigured() {
        return supabaseUrl != null && !supabaseUrl.isEmpty() &&
               supabaseKey != null && !supabaseKey.isEmpty();
    }
}

