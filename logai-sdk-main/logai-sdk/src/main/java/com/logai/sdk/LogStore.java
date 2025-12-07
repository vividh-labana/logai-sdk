package com.logai.sdk;

import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-based storage for log entries.
 * Provides persistent storage for logs that can be analyzed offline.
 */
public class LogStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LogStore.class);
    
    private static final String DEFAULT_DB_PATH = "logai.db";
    
    private final String dbPath;
    private Connection connection;

    public LogStore() {
        this(DEFAULT_DB_PATH);
    }

    public LogStore(String dbPath) {
        this.dbPath = dbPath;
        initialize();
    }

    private void initialize() {
        try {
            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");
            
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createSchema();
            
            logger.info("LogStore initialized with database: {}", dbPath);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void createSchema() throws SQLException {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS log_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp BIGINT NOT NULL,
                level VARCHAR(10) NOT NULL,
                logger VARCHAR(255),
                message TEXT,
                stack_trace TEXT,
                file_name VARCHAR(255),
                line_number INTEGER,
                method_name VARCHAR(255),
                class_name VARCHAR(255),
                trace_id VARCHAR(64),
                thread_name VARCHAR(255),
                mdc_context TEXT,
                created_at BIGINT NOT NULL
            )
            """;

        String createIndexSQL = """
            CREATE INDEX IF NOT EXISTS idx_log_entries_timestamp 
            ON log_entries(timestamp)
            """;
        
        String createLevelIndexSQL = """
            CREATE INDEX IF NOT EXISTS idx_log_entries_level 
            ON log_entries(level)
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
            stmt.execute(createIndexSQL);
            stmt.execute(createLevelIndexSQL);
        }
    }

    /**
     * Store a log entry in the database.
     */
    public void store(LogEntry entry) {
        String sql = """
            INSERT INTO log_entries (
                timestamp, level, logger, message, stack_trace,
                file_name, line_number, method_name, class_name,
                trace_id, thread_name, mdc_context, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, entry.getTimestamp() != null ? entry.getTimestamp().toEpochMilli() : System.currentTimeMillis());
            pstmt.setString(2, entry.getLevel() != null ? entry.getLevel().name() : "INFO");
            pstmt.setString(3, entry.getLogger());
            pstmt.setString(4, entry.getMessage());
            pstmt.setString(5, entry.getStackTrace());
            pstmt.setString(6, entry.getFileName());
            pstmt.setObject(7, entry.getLineNumber());
            pstmt.setString(8, entry.getMethodName());
            pstmt.setString(9, entry.getClassName());
            pstmt.setString(10, entry.getTraceId());
            pstmt.setString(11, entry.getThreadName());
            pstmt.setString(12, serializeMdc(entry.getMdcContext()));
            pstmt.setLong(13, entry.getCreatedAt() != null ? entry.getCreatedAt().toEpochMilli() : System.currentTimeMillis());
            
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to store log entry", e);
        }
    }

    /**
     * Store multiple log entries in a batch.
     */
    public void storeBatch(List<LogEntry> entries) {
        String sql = """
            INSERT INTO log_entries (
                timestamp, level, logger, message, stack_trace,
                file_name, line_number, method_name, class_name,
                trace_id, thread_name, mdc_context, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            
            for (LogEntry entry : entries) {
                pstmt.setLong(1, entry.getTimestamp() != null ? entry.getTimestamp().toEpochMilli() : System.currentTimeMillis());
                pstmt.setString(2, entry.getLevel() != null ? entry.getLevel().name() : "INFO");
                pstmt.setString(3, entry.getLogger());
                pstmt.setString(4, entry.getMessage());
                pstmt.setString(5, entry.getStackTrace());
                pstmt.setString(6, entry.getFileName());
                pstmt.setObject(7, entry.getLineNumber());
                pstmt.setString(8, entry.getMethodName());
                pstmt.setString(9, entry.getClassName());
                pstmt.setString(10, entry.getTraceId());
                pstmt.setString(11, entry.getThreadName());
                pstmt.setString(12, serializeMdc(entry.getMdcContext()));
                pstmt.setLong(13, entry.getCreatedAt() != null ? entry.getCreatedAt().toEpochMilli() : System.currentTimeMillis());
                pstmt.addBatch();
            }
            
            pstmt.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            logger.error("Failed to store log entries batch", e);
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                logger.error("Failed to rollback transaction", ex);
            }
        }
    }

    /**
     * Query log entries within a time range.
     */
    public List<LogEntry> queryByTimeRange(Instant start, Instant end) {
        String sql = """
            SELECT * FROM log_entries 
            WHERE timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            """;

        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, start.toEpochMilli());
            pstmt.setLong(2, end.toEpochMilli());
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapResultSetToLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query log entries", e);
        }
        return entries;
    }

    /**
     * Query error log entries (ERROR and FATAL levels).
     */
    public List<LogEntry> queryErrors(Instant start, Instant end) {
        String sql = """
            SELECT * FROM log_entries 
            WHERE timestamp >= ? AND timestamp <= ?
            AND level IN ('ERROR', 'FATAL')
            ORDER BY timestamp DESC
            """;

        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, start.toEpochMilli());
            pstmt.setLong(2, end.toEpochMilli());
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapResultSetToLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query error entries", e);
        }
        return entries;
    }

    /**
     * Query log entries by level.
     */
    public List<LogEntry> queryByLevel(LogLevel level, int limit) {
        String sql = """
            SELECT * FROM log_entries 
            WHERE level = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, level.name());
            pstmt.setInt(2, limit);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapResultSetToLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query log entries by level", e);
        }
        return entries;
    }

    /**
     * Query recent log entries.
     */
    public List<LogEntry> queryRecent(int limit) {
        String sql = """
            SELECT * FROM log_entries 
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapResultSetToLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query recent entries", e);
        }
        return entries;
    }

    /**
     * Get count of log entries by level.
     */
    public Map<LogLevel, Long> getCountByLevel() {
        String sql = """
            SELECT level, COUNT(*) as count 
            FROM log_entries 
            GROUP BY level
            """;

        Map<LogLevel, Long> counts = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                LogLevel level = LogLevel.fromString(rs.getString("level"));
                counts.put(level, rs.getLong("count"));
            }
        } catch (SQLException e) {
            logger.error("Failed to get count by level", e);
        }
        return counts;
    }

    /**
     * Delete old log entries.
     */
    public int deleteOlderThan(Instant cutoff) {
        String sql = "DELETE FROM log_entries WHERE timestamp < ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, cutoff.toEpochMilli());
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete old entries", e);
            return 0;
        }
    }

    private LogEntry mapResultSetToLogEntry(ResultSet rs) throws SQLException {
        return LogEntry.builder()
                .id(rs.getLong("id"))
                .timestamp(Instant.ofEpochMilli(rs.getLong("timestamp")))
                .level(LogLevel.fromString(rs.getString("level")))
                .logger(rs.getString("logger"))
                .message(rs.getString("message"))
                .stackTrace(rs.getString("stack_trace"))
                .fileName(rs.getString("file_name"))
                .lineNumber(rs.getObject("line_number") != null ? rs.getInt("line_number") : null)
                .methodName(rs.getString("method_name"))
                .className(rs.getString("class_name"))
                .traceId(rs.getString("trace_id"))
                .threadName(rs.getString("thread_name"))
                .mdcContext(deserializeMdc(rs.getString("mdc_context")))
                .build();
    }

    private String serializeMdc(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private Map<String, String> deserializeMdc(String mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (String pair : mdc.split(";")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                result.put(pair.substring(0, eqIndex), pair.substring(eqIndex + 1));
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("LogStore closed");
            } catch (SQLException e) {
                logger.error("Error closing database connection", e);
            }
        }
    }
}

