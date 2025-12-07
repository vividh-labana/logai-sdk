package com.logai.cli.command;

import com.logai.cli.config.LogAIConfig;
import com.logai.core.analysis.ErrorClusterer;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;
import com.logai.sdk.LogStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generate reports from analyzed logs.
 */
@Command(
        name = "report",
        description = "Generate health reports from log analysis"
)
public class ReportCommand implements Callable<Integer> {

    @Option(names = {"--format", "-f"}, 
            description = "Report format: html, json, md",
            defaultValue = "md")
    private String format;

    @Option(names = {"--output", "-o"}, 
            description = "Output file path")
    private String outputPath;

    @Option(names = {"--last", "-l"}, 
            description = "Time range (e.g., 1h, 24h, 7d)",
            defaultValue = "24h")
    private String lastDuration;

    @Option(names = {"--db", "-d"}, 
            description = "Database path")
    private String dbPath;

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhd])");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Override
    public Integer call() {
        LogAIConfig config = LogAIConfig.load();
        String database = dbPath != null ? dbPath : config.getDatabasePath();

        Duration duration = parseDuration(lastDuration);
        Instant start = Instant.now().minus(duration);
        Instant end = Instant.now();

        try (LogStore store = new LogStore(database)) {
            // Gather data
            List<LogEntry> allLogs = store.queryByTimeRange(start, end);
            List<LogEntry> errors = store.queryErrors(start, end);
            Map<LogLevel, Long> countByLevel = store.getCountByLevel();
            
            ErrorClusterer clusterer = new ErrorClusterer();
            List<ErrorCluster> clusters = clusterer.cluster(errors);

            // Generate report
            String report = switch (format.toLowerCase()) {
                case "html" -> generateHtmlReport(allLogs, errors, clusters, countByLevel, start, end);
                case "json" -> generateJsonReport(allLogs, errors, clusters, countByLevel, start, end);
                default -> generateMarkdownReport(allLogs, errors, clusters, countByLevel, start, end);
            };

            // Output
            if (outputPath != null) {
                Path path = Paths.get(outputPath);
                Files.writeString(path, report);
                System.out.println("Report saved to: " + path);
            } else {
                System.out.println(report);
            }

            return 0;

        } catch (Exception e) {
            System.err.println("Error generating report: " + e.getMessage());
            return 1;
        }
    }

    private String generateMarkdownReport(List<LogEntry> allLogs, List<LogEntry> errors, 
            List<ErrorCluster> clusters, Map<LogLevel, Long> countByLevel, 
            Instant start, Instant end) {
        
        StringBuilder sb = new StringBuilder();
        
        sb.append("# LogAI Health Report\n\n");
        sb.append("**Generated:** ").append(DATE_FORMAT.format(Instant.now())).append("\n");
        sb.append("**Period:** ").append(DATE_FORMAT.format(start))
          .append(" to ").append(DATE_FORMAT.format(end)).append("\n\n");

        // Executive Summary
        sb.append("## Executive Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Log Entries | ").append(allLogs.size()).append(" |\n");
        sb.append("| Error Entries | ").append(errors.size()).append(" |\n");
        sb.append("| Unique Error Types | ").append(clusters.size()).append(" |\n");
        
        long criticalCount = clusters.stream()
                .filter(c -> c.getSeverity() == ErrorCluster.ClusterSeverity.CRITICAL)
                .count();
        sb.append("| Critical Issues | ").append(criticalCount).append(" |\n\n");

        // Log Level Distribution
        sb.append("## Log Level Distribution\n\n");
        sb.append("| Level | Count |\n");
        sb.append("|-------|-------|\n");
        for (LogLevel level : LogLevel.values()) {
            long count = countByLevel.getOrDefault(level, 0L);
            if (count > 0) {
                sb.append("| ").append(level).append(" | ").append(count).append(" |\n");
            }
        }
        sb.append("\n");

        // Top Error Clusters
        sb.append("## Top Error Clusters\n\n");
        
        int shown = 0;
        for (ErrorCluster cluster : clusters) {
            if (shown >= 10) break;
            shown++;
            
            sb.append("### ").append(cluster.getId()).append("\n\n");
            sb.append("- **Exception:** ").append(cluster.getExceptionClass()).append("\n");
            sb.append("- **Occurrences:** ").append(cluster.getOccurrenceCount()).append("\n");
            sb.append("- **Severity:** ").append(cluster.getSeverity()).append("\n");
            sb.append("- **First Seen:** ").append(DATE_FORMAT.format(cluster.getFirstSeen())).append("\n");
            sb.append("- **Last Seen:** ").append(DATE_FORMAT.format(cluster.getLastSeen())).append("\n");
            
            if (cluster.getFullLocation() != null && !cluster.getFullLocation().isEmpty()) {
                sb.append("- **Location:** `").append(cluster.getFullLocation()).append("`\n");
            }
            
            if (cluster.getMessagePattern() != null) {
                sb.append("- **Message:** ").append(truncate(cluster.getMessagePattern(), 100)).append("\n");
            }
            sb.append("\n");
        }

        // Recommendations
        sb.append("## Recommendations\n\n");
        
        if (criticalCount > 0) {
            sb.append("⚠️ **").append(criticalCount).append(" critical issues require immediate attention.**\n\n");
        }
        
        sb.append("1. Review and address the top error clusters listed above\n");
        sb.append("2. Run `logai scan --analyze` for AI-powered root cause analysis\n");
        sb.append("3. Use `logai fix --generate <cluster-id>` to generate patches\n\n");

        sb.append("---\n");
        sb.append("*Report generated by LogAI v1.0.0*\n");

        return sb.toString();
    }

    private String generateHtmlReport(List<LogEntry> allLogs, List<LogEntry> errors, 
            List<ErrorCluster> clusters, Map<LogLevel, Long> countByLevel, 
            Instant start, Instant end) {
        
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>LogAI Health Report</title>
                <style>
                    :root {
                        --bg-primary: #0d1117;
                        --bg-secondary: #161b22;
                        --text-primary: #c9d1d9;
                        --text-secondary: #8b949e;
                        --accent: #58a6ff;
                        --error: #f85149;
                        --warning: #d29922;
                        --success: #3fb950;
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
                        background: var(--bg-primary);
                        color: var(--text-primary);
                        line-height: 1.6;
                        padding: 2rem;
                        max-width: 1200px;
                        margin: 0 auto;
                    }
                    h1 { color: var(--accent); border-bottom: 1px solid var(--bg-secondary); padding-bottom: 0.5rem; }
                    h2 { color: var(--text-primary); margin-top: 2rem; }
                    h3 { color: var(--text-secondary); }
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                        gap: 1rem;
                        margin: 1rem 0;
                    }
                    .summary-card {
                        background: var(--bg-secondary);
                        padding: 1rem;
                        border-radius: 8px;
                        text-align: center;
                    }
                    .summary-card h3 { margin: 0; font-size: 0.9rem; color: var(--text-secondary); }
                    .summary-card .value { font-size: 2rem; font-weight: bold; color: var(--accent); }
                    .summary-card.critical .value { color: var(--error); }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin: 1rem 0;
                    }
                    th, td {
                        padding: 0.75rem;
                        text-align: left;
                        border-bottom: 1px solid var(--bg-secondary);
                    }
                    th { background: var(--bg-secondary); }
                    .cluster-card {
                        background: var(--bg-secondary);
                        padding: 1rem;
                        margin: 1rem 0;
                        border-radius: 8px;
                        border-left: 4px solid var(--accent);
                    }
                    .cluster-card.critical { border-left-color: var(--error); }
                    .cluster-card.high { border-left-color: var(--warning); }
                    .severity { 
                        display: inline-block;
                        padding: 0.25rem 0.5rem;
                        border-radius: 4px;
                        font-size: 0.8rem;
                        font-weight: bold;
                    }
                    .severity.critical { background: var(--error); color: white; }
                    .severity.high { background: var(--warning); color: black; }
                    .severity.medium { background: #ffd33d; color: black; }
                    .severity.low { background: var(--success); color: white; }
                    code {
                        background: var(--bg-primary);
                        padding: 0.2rem 0.4rem;
                        border-radius: 4px;
                        font-family: 'JetBrains Mono', monospace;
                    }
                    .meta { color: var(--text-secondary); font-size: 0.9rem; }
                </style>
            </head>
            <body>
            """);
        
        sb.append("<h1>🔍 LogAI Health Report</h1>\n");
        sb.append("<p class=\"meta\">Generated: ").append(DATE_FORMAT.format(Instant.now()));
        sb.append(" | Period: ").append(DATE_FORMAT.format(start))
          .append(" to ").append(DATE_FORMAT.format(end)).append("</p>\n");

        // Summary Cards
        long criticalCount = clusters.stream()
                .filter(c -> c.getSeverity() == ErrorCluster.ClusterSeverity.CRITICAL)
                .count();

        sb.append("<div class=\"summary-grid\">\n");
        sb.append("<div class=\"summary-card\"><h3>Total Logs</h3><div class=\"value\">")
          .append(allLogs.size()).append("</div></div>\n");
        sb.append("<div class=\"summary-card\"><h3>Errors</h3><div class=\"value\">")
          .append(errors.size()).append("</div></div>\n");
        sb.append("<div class=\"summary-card\"><h3>Unique Issues</h3><div class=\"value\">")
          .append(clusters.size()).append("</div></div>\n");
        sb.append("<div class=\"summary-card critical\"><h3>Critical</h3><div class=\"value\">")
          .append(criticalCount).append("</div></div>\n");
        sb.append("</div>\n");

        // Log Level Table
        sb.append("<h2>📊 Log Level Distribution</h2>\n");
        sb.append("<table><tr><th>Level</th><th>Count</th></tr>\n");
        for (LogLevel level : LogLevel.values()) {
            long count = countByLevel.getOrDefault(level, 0L);
            if (count > 0) {
                sb.append("<tr><td>").append(level).append("</td><td>").append(count).append("</td></tr>\n");
            }
        }
        sb.append("</table>\n");

        // Error Clusters
        sb.append("<h2>🔴 Top Error Clusters</h2>\n");
        
        int shown = 0;
        for (ErrorCluster cluster : clusters) {
            if (shown >= 10) break;
            shown++;
            
            String severityClass = cluster.getSeverity().name().toLowerCase();
            sb.append("<div class=\"cluster-card ").append(severityClass).append("\">\n");
            sb.append("<h3>").append(cluster.getId()).append(" ");
            sb.append("<span class=\"severity ").append(severityClass).append("\">")
              .append(cluster.getSeverity()).append("</span></h3>\n");
            sb.append("<p><strong>").append(cluster.getExceptionClass()).append("</strong></p>\n");
            sb.append("<p>Occurrences: <strong>").append(cluster.getOccurrenceCount()).append("</strong> | ");
            sb.append("First: ").append(DATE_FORMAT.format(cluster.getFirstSeen())).append(" | ");
            sb.append("Last: ").append(DATE_FORMAT.format(cluster.getLastSeen())).append("</p>\n");
            
            if (cluster.getFullLocation() != null && !cluster.getFullLocation().isEmpty()) {
                sb.append("<p>Location: <code>").append(cluster.getFullLocation()).append("</code></p>\n");
            }
            sb.append("</div>\n");
        }

        sb.append("""
            <hr>
            <p class="meta">Report generated by LogAI v1.0.0</p>
            </body>
            </html>
            """);

        return sb.toString();
    }

    private String generateJsonReport(List<LogEntry> allLogs, List<LogEntry> errors, 
            List<ErrorCluster> clusters, Map<LogLevel, Long> countByLevel, 
            Instant start, Instant end) {
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generated\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"period\": {\n");
        sb.append("    \"start\": \"").append(start).append("\",\n");
        sb.append("    \"end\": \"").append(end).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"totalLogs\": ").append(allLogs.size()).append(",\n");
        sb.append("    \"errorLogs\": ").append(errors.size()).append(",\n");
        sb.append("    \"uniqueClusters\": ").append(clusters.size()).append("\n");
        sb.append("  },\n");
        
        sb.append("  \"levelDistribution\": {\n");
        boolean first = true;
        for (LogLevel level : LogLevel.values()) {
            long count = countByLevel.getOrDefault(level, 0L);
            if (count > 0) {
                if (!first) sb.append(",\n");
                sb.append("    \"").append(level).append("\": ").append(count);
                first = false;
            }
        }
        sb.append("\n  },\n");
        
        sb.append("  \"clusters\": [\n");
        int shown = 0;
        for (ErrorCluster cluster : clusters) {
            if (shown >= 10) break;
            if (shown > 0) sb.append(",\n");
            shown++;
            
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(cluster.getId()).append("\",\n");
            sb.append("      \"exceptionClass\": \"").append(escapeJson(cluster.getExceptionClass())).append("\",\n");
            sb.append("      \"occurrences\": ").append(cluster.getOccurrenceCount()).append(",\n");
            sb.append("      \"severity\": \"").append(cluster.getSeverity()).append("\",\n");
            sb.append("      \"firstSeen\": \"").append(cluster.getFirstSeen()).append("\",\n");
            sb.append("      \"lastSeen\": \"").append(cluster.getLastSeen()).append("\",\n");
            sb.append("      \"location\": \"").append(escapeJson(cluster.getFullLocation())).append("\"\n");
            sb.append("    }");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private Duration parseDuration(String duration) {
        Matcher matcher = DURATION_PATTERN.matcher(duration.toLowerCase());
        if (matcher.matches()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            return switch (unit) {
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                case "h" -> Duration.ofHours(value);
                case "d" -> Duration.ofDays(value);
                default -> Duration.ofHours(24);
            };
        }
        return Duration.ofHours(24);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}

