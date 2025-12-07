package com.logai.report;

import com.logai.core.analysis.ErrorClusterer;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;
import com.logai.llm.model.AnalysisResult;
import com.logai.report.model.ReportData;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates report generation from log data.
 */
public class ReportGenerator {

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ErrorClusterer clusterer;

    public ReportGenerator() {
        this.clusterer = new ErrorClusterer();
    }

    public ReportGenerator(ErrorClusterer clusterer) {
        this.clusterer = clusterer;
    }

    /**
     * Generate report data from log entries.
     */
    public ReportData generateReportData(List<LogEntry> allLogs, Instant periodStart, Instant periodEnd) {
        // Filter errors
        List<LogEntry> errors = allLogs.stream()
                .filter(LogEntry::isError)
                .toList();

        // Cluster errors
        List<ErrorCluster> clusters = clusterer.cluster(errors);

        // Calculate level distribution
        Map<LogLevel, Long> levelDistribution = new HashMap<>();
        for (LogEntry entry : allLogs) {
            LogLevel level = entry.getLevel();
            levelDistribution.merge(level, 1L, Long::sum);
        }

        return ReportData.builder()
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .totalLogs(allLogs.size())
                .errorLogs(errors.size())
                .levelDistribution(levelDistribution)
                .clusters(clusters)
                .build();
    }

    /**
     * Generate report data with analysis results.
     */
    public ReportData generateReportData(List<LogEntry> allLogs, Instant periodStart, Instant periodEnd,
                                         Map<String, AnalysisResult> analysisResults, String overallSummary) {
        ReportData data = generateReportData(allLogs, periodStart, periodEnd);
        data.setAnalysisResults(analysisResults);
        data.setOverallSummary(overallSummary);
        return data;
    }

    /**
     * Generate a report in the specified format.
     */
    public String generate(ReportData data, ReportFormat format) {
        ReportWriter writer = getWriter(format);
        return writer.generate(data);
    }

    /**
     * Write a report to a file.
     */
    public Path write(ReportData data, ReportFormat format, String outputDir) throws IOException {
        ReportWriter writer = getWriter(format);
        
        String fileName = generateFileName(format);
        Path outputPath = Paths.get(outputDir, fileName);
        
        writer.write(data, outputPath);
        return outputPath;
    }

    /**
     * Write a report to a specific path.
     */
    public void write(ReportData data, ReportFormat format, Path outputPath) throws IOException {
        ReportWriter writer = getWriter(format);
        writer.write(data, outputPath);
    }

    private ReportWriter getWriter(ReportFormat format) {
        return switch (format) {
            case HTML -> new HtmlReportWriter();
            case MARKDOWN -> new MarkdownReportWriter();
            case JSON -> new JsonReportWriter();
        };
    }

    private String generateFileName(ReportFormat format) {
        String timestamp = LocalDateTime.now(ZoneId.systemDefault()).format(FILE_DATE_FORMAT);
        String extension = switch (format) {
            case HTML -> "html";
            case MARKDOWN -> "md";
            case JSON -> "json";
        };
        return String.format("logai_report_%s.%s", timestamp, extension);
    }

    public enum ReportFormat {
        HTML,
        MARKDOWN,
        JSON
    }
}

