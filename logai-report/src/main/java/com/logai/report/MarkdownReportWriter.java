package com.logai.report;

import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogLevel;
import com.logai.llm.model.AnalysisResult;
import com.logai.report.model.ReportData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates Markdown format reports.
 */
public class MarkdownReportWriter implements ReportWriter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Override
    public String generate(ReportData data) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("# LogAI Health Report\n\n");
        sb.append("**Generated:** ").append(DATE_FORMAT.format(data.getGeneratedAt())).append("\n");
        sb.append("**Period:** ").append(DATE_FORMAT.format(data.getPeriodStart()))
          .append(" to ").append(DATE_FORMAT.format(data.getPeriodEnd())).append("\n\n");

        // Executive Summary
        sb.append("## Executive Summary\n\n");
        
        // Summary table
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Log Entries | ").append(formatNumber(data.getTotalLogs())).append(" |\n");
        sb.append("| Error Entries | ").append(formatNumber(data.getErrorLogs())).append(" |\n");
        sb.append("| Error Rate | ").append(String.format("%.2f%%", data.getErrorRate())).append(" |\n");
        sb.append("| Unique Error Types | ").append(data.getClusterCount()).append(" |\n");
        sb.append("| Critical Issues | ").append(data.getCriticalCount()).append(" |\n");
        sb.append("| High Priority Issues | ").append(data.getHighCount()).append(" |\n\n");

        // Health Status
        sb.append("### Health Status\n\n");
        if (data.getCriticalCount() > 0) {
            sb.append("🔴 **CRITICAL** - Immediate attention required!\n\n");
        } else if (data.getHighCount() > 0) {
            sb.append("🟠 **WARNING** - High priority issues detected\n\n");
        } else if (data.getErrorLogs() > 0) {
            sb.append("🟡 **ATTENTION** - Some errors detected\n\n");
        } else {
            sb.append("🟢 **HEALTHY** - No significant issues\n\n");
        }

        // Overall Summary (if available from LLM)
        if (data.getOverallSummary() != null && !data.getOverallSummary().isEmpty()) {
            sb.append("### AI Analysis Summary\n\n");
            sb.append(data.getOverallSummary()).append("\n\n");
        }

        // Log Level Distribution
        sb.append("## Log Level Distribution\n\n");
        sb.append("| Level | Count | Percentage |\n");
        sb.append("|-------|-------|------------|\n");
        
        long total = data.getLevelDistribution().values().stream().mapToLong(l -> l).sum();
        for (LogLevel level : LogLevel.values()) {
            long count = data.getLevelDistribution().getOrDefault(level, 0L);
            if (count > 0) {
                double pct = total > 0 ? (double) count / total * 100 : 0;
                String emoji = getLevelEmoji(level);
                sb.append("| ").append(emoji).append(" ").append(level)
                  .append(" | ").append(formatNumber(count))
                  .append(" | ").append(String.format("%.1f%%", pct)).append(" |\n");
            }
        }
        sb.append("\n");

        // Error Clusters
        if (data.getClusters() != null && !data.getClusters().isEmpty()) {
            sb.append("## Error Clusters\n\n");
            
            int shown = 0;
            for (ErrorCluster cluster : data.getClusters()) {
                if (shown >= 15) {
                    sb.append("*... and ").append(data.getClusters().size() - shown)
                      .append(" more clusters*\n\n");
                    break;
                }
                shown++;

                String severityBadge = getSeverityBadge(cluster.getSeverity());
                
                sb.append("### ").append(cluster.getId()).append(" ").append(severityBadge).append("\n\n");
                sb.append("**Exception:** `").append(cluster.getExceptionClass()).append("`\n\n");
                sb.append("- **Occurrences:** ").append(cluster.getOccurrenceCount()).append("\n");
                sb.append("- **First Seen:** ").append(DATE_FORMAT.format(cluster.getFirstSeen())).append("\n");
                sb.append("- **Last Seen:** ").append(DATE_FORMAT.format(cluster.getLastSeen())).append("\n");
                
                if (cluster.getFullLocation() != null && !cluster.getFullLocation().isEmpty()) {
                    sb.append("- **Location:** `").append(cluster.getFullLocation()).append("`\n");
                }
                
                if (cluster.getMessagePattern() != null) {
                    sb.append("- **Message:** ").append(truncate(cluster.getMessagePattern(), 100)).append("\n");
                }
                sb.append("\n");

                // Include analysis if available
                AnalysisResult analysis = data.getAnalysisFor(cluster.getId());
                if (analysis != null) {
                    sb.append("<details>\n<summary>🤖 AI Analysis</summary>\n\n");
                    
                    if (analysis.getExplanation() != null) {
                        sb.append("**Explanation:** ").append(analysis.getExplanation()).append("\n\n");
                    }
                    if (analysis.getRootCause() != null) {
                        sb.append("**Root Cause:** ").append(analysis.getRootCause()).append("\n\n");
                    }
                    if (analysis.getRecommendation() != null) {
                        sb.append("**Recommendation:** ").append(analysis.getRecommendation()).append("\n\n");
                    }
                    if (analysis.hasPatch()) {
                        sb.append("**Patch Available:** Yes - Run `logai fix --generate ")
                          .append(cluster.getId()).append("`\n\n");
                    }
                    
                    sb.append("</details>\n\n");
                }
            }
        }

        // Recommendations
        sb.append("## Recommendations\n\n");
        
        if (data.getCriticalCount() > 0) {
            sb.append("### Immediate Actions Required\n\n");
            sb.append("1. ⚠️ Address the **").append(data.getCriticalCount())
              .append(" critical issues** immediately\n");
            sb.append("2. Run `logai scan --analyze` for detailed AI analysis\n");
            sb.append("3. Use `logai fix --generate <cluster-id>` for auto-generated patches\n\n");
        }

        sb.append("### General Recommendations\n\n");
        sb.append("1. Review and address high-frequency error clusters\n");
        sb.append("2. Implement monitoring alerts for critical exceptions\n");
        sb.append("3. Add defensive coding for null-pointer related issues\n");
        sb.append("4. Consider adding circuit breakers for external service failures\n");
        sb.append("5. Review and update error handling in identified hotspots\n\n");

        // Footer
        sb.append("---\n\n");
        sb.append("*Report generated by [LogAI](https://github.com/logai/logai) v1.0.0*\n");
        sb.append("\n**Commands:**\n");
        sb.append("- Analyze: `logai scan --analyze`\n");
        sb.append("- Generate fix: `logai fix --generate <cluster-id>`\n");
        sb.append("- View cluster: `logai clusters <cluster-id>`\n");

        return sb.toString();
    }

    @Override
    public void write(ReportData data, Path outputPath) throws IOException {
        String content = generate(data);
        Files.writeString(outputPath, content);
    }

    @Override
    public String getFileExtension() {
        return "md";
    }

    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private String getLevelEmoji(LogLevel level) {
        return switch (level) {
            case TRACE -> "⚪";
            case DEBUG -> "🔵";
            case INFO -> "🟢";
            case WARN -> "🟡";
            case ERROR -> "🔴";
            case FATAL -> "💀";
        };
    }

    private String getSeverityBadge(ErrorCluster.ClusterSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "🔴 CRITICAL";
            case HIGH -> "🟠 HIGH";
            case MEDIUM -> "🟡 MEDIUM";
            case LOW -> "🟢 LOW";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}

