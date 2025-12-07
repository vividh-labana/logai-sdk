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
 * Generates styled HTML format reports.
 */
public class HtmlReportWriter implements ReportWriter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Override
    public String generate(ReportData data) {
        StringBuilder sb = new StringBuilder();

        // HTML Head with CSS
        sb.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>LogAI Health Report</title>
                <style>
                    :root {
                        --bg-primary: #0f0f23;
                        --bg-secondary: #1a1a2e;
                        --bg-card: #16213e;
                        --text-primary: #e8e6e3;
                        --text-secondary: #9ca3af;
                        --accent-blue: #00d9ff;
                        --accent-purple: #a855f7;
                        --accent-green: #22c55e;
                        --accent-yellow: #eab308;
                        --accent-orange: #f97316;
                        --accent-red: #ef4444;
                        --border: #374151;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: linear-gradient(135deg, var(--bg-primary) 0%, var(--bg-secondary) 100%);
                        color: var(--text-primary);
                        min-height: 100vh;
                        padding: 2rem;
                    }
                    .container { max-width: 1400px; margin: 0 auto; }
                    
                    /* Header */
                    header {
                        text-align: center;
                        margin-bottom: 3rem;
                        padding: 2rem;
                        background: var(--bg-card);
                        border-radius: 16px;
                        border: 1px solid var(--border);
                    }
                    header h1 {
                        font-size: 2.5rem;
                        background: linear-gradient(90deg, var(--accent-blue), var(--accent-purple));
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        margin-bottom: 0.5rem;
                    }
                    .meta { color: var(--text-secondary); font-size: 0.9rem; }
                    
                    /* Summary Cards */
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                        gap: 1.5rem;
                        margin-bottom: 3rem;
                    }
                    .summary-card {
                        background: var(--bg-card);
                        border-radius: 12px;
                        padding: 1.5rem;
                        border: 1px solid var(--border);
                        text-align: center;
                        transition: transform 0.2s, box-shadow 0.2s;
                    }
                    .summary-card:hover {
                        transform: translateY(-4px);
                        box-shadow: 0 10px 40px rgba(0, 217, 255, 0.1);
                    }
                    .summary-card h3 {
                        font-size: 0.8rem;
                        text-transform: uppercase;
                        letter-spacing: 0.1em;
                        color: var(--text-secondary);
                        margin-bottom: 0.5rem;
                    }
                    .summary-card .value {
                        font-size: 2.5rem;
                        font-weight: 700;
                        color: var(--accent-blue);
                    }
                    .summary-card.critical .value { color: var(--accent-red); }
                    .summary-card.warning .value { color: var(--accent-orange); }
                    .summary-card.success .value { color: var(--accent-green); }
                    
                    /* Health Status */
                    .health-status {
                        padding: 1rem 2rem;
                        border-radius: 8px;
                        display: inline-block;
                        font-weight: 600;
                        margin-bottom: 2rem;
                    }
                    .health-status.critical { background: rgba(239, 68, 68, 0.2); color: var(--accent-red); }
                    .health-status.warning { background: rgba(249, 115, 22, 0.2); color: var(--accent-orange); }
                    .health-status.attention { background: rgba(234, 179, 8, 0.2); color: var(--accent-yellow); }
                    .health-status.healthy { background: rgba(34, 197, 94, 0.2); color: var(--accent-green); }
                    
                    /* Sections */
                    section {
                        background: var(--bg-card);
                        border-radius: 16px;
                        padding: 2rem;
                        margin-bottom: 2rem;
                        border: 1px solid var(--border);
                    }
                    section h2 {
                        font-size: 1.5rem;
                        margin-bottom: 1.5rem;
                        color: var(--accent-blue);
                        display: flex;
                        align-items: center;
                        gap: 0.5rem;
                    }
                    
                    /* Tables */
                    table { width: 100%; border-collapse: collapse; }
                    th, td {
                        padding: 1rem;
                        text-align: left;
                        border-bottom: 1px solid var(--border);
                    }
                    th {
                        font-size: 0.75rem;
                        text-transform: uppercase;
                        letter-spacing: 0.1em;
                        color: var(--text-secondary);
                    }
                    tr:hover { background: rgba(255, 255, 255, 0.02); }
                    
                    /* Cluster Cards */
                    .cluster-card {
                        background: var(--bg-secondary);
                        border-radius: 12px;
                        padding: 1.5rem;
                        margin-bottom: 1rem;
                        border-left: 4px solid var(--accent-blue);
                        transition: border-color 0.2s;
                    }
                    .cluster-card:hover { border-left-color: var(--accent-purple); }
                    .cluster-card.critical { border-left-color: var(--accent-red); }
                    .cluster-card.high { border-left-color: var(--accent-orange); }
                    .cluster-card.medium { border-left-color: var(--accent-yellow); }
                    .cluster-card.low { border-left-color: var(--accent-green); }
                    
                    .cluster-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 1rem;
                    }
                    .cluster-id {
                        font-family: 'JetBrains Mono', 'Fira Code', monospace;
                        font-size: 0.9rem;
                        color: var(--accent-purple);
                    }
                    .severity-badge {
                        padding: 0.25rem 0.75rem;
                        border-radius: 20px;
                        font-size: 0.75rem;
                        font-weight: 600;
                        text-transform: uppercase;
                    }
                    .severity-badge.critical { background: var(--accent-red); color: white; }
                    .severity-badge.high { background: var(--accent-orange); color: white; }
                    .severity-badge.medium { background: var(--accent-yellow); color: black; }
                    .severity-badge.low { background: var(--accent-green); color: white; }
                    
                    .exception-name {
                        font-family: 'JetBrains Mono', monospace;
                        font-size: 1rem;
                        color: var(--text-primary);
                        word-break: break-all;
                    }
                    .cluster-meta {
                        display: flex;
                        gap: 2rem;
                        color: var(--text-secondary);
                        font-size: 0.85rem;
                        margin-top: 1rem;
                    }
                    .cluster-meta span { display: flex; align-items: center; gap: 0.5rem; }
                    
                    /* Analysis Box */
                    .analysis-box {
                        margin-top: 1rem;
                        padding: 1rem;
                        background: rgba(0, 217, 255, 0.05);
                        border-radius: 8px;
                        border: 1px solid rgba(0, 217, 255, 0.2);
                    }
                    .analysis-box h4 {
                        color: var(--accent-blue);
                        font-size: 0.9rem;
                        margin-bottom: 0.5rem;
                    }
                    .analysis-box p {
                        color: var(--text-secondary);
                        font-size: 0.9rem;
                        line-height: 1.6;
                    }
                    
                    /* Footer */
                    footer {
                        text-align: center;
                        padding: 2rem;
                        color: var(--text-secondary);
                        font-size: 0.85rem;
                    }
                    footer a { color: var(--accent-blue); text-decoration: none; }
                    footer a:hover { text-decoration: underline; }
                    
                    /* Progress Bar */
                    .progress-bar {
                        height: 8px;
                        background: var(--bg-primary);
                        border-radius: 4px;
                        overflow: hidden;
                        margin-top: 0.5rem;
                    }
                    .progress-fill {
                        height: 100%;
                        border-radius: 4px;
                        transition: width 0.3s;
                    }
                </style>
            </head>
            <body>
            <div class="container">
            """);

        // Header
        sb.append("<header>\n");
        sb.append("<h1>🔍 LogAI Health Report</h1>\n");
        sb.append("<p class=\"meta\">Generated: ").append(DATE_FORMAT.format(data.getGeneratedAt()));
        sb.append(" | Period: ").append(DATE_FORMAT.format(data.getPeriodStart()));
        sb.append(" to ").append(DATE_FORMAT.format(data.getPeriodEnd())).append("</p>\n");
        
        // Health Status Badge
        String healthClass = getHealthClass(data);
        String healthText = getHealthText(data);
        sb.append("<div class=\"health-status ").append(healthClass).append("\">");
        sb.append(healthText).append("</div>\n");
        sb.append("</header>\n");

        // Summary Cards
        sb.append("<div class=\"summary-grid\">\n");
        sb.append(summaryCard("Total Logs", formatNumber(data.getTotalLogs()), ""));
        sb.append(summaryCard("Errors", formatNumber(data.getErrorLogs()), data.getErrorLogs() > 100 ? "warning" : ""));
        sb.append(summaryCard("Error Rate", String.format("%.1f%%", data.getErrorRate()), data.getErrorRate() > 5 ? "warning" : "success"));
        sb.append(summaryCard("Unique Issues", String.valueOf(data.getClusterCount()), ""));
        sb.append(summaryCard("Critical", String.valueOf(data.getCriticalCount()), data.getCriticalCount() > 0 ? "critical" : "success"));
        sb.append(summaryCard("High Priority", String.valueOf(data.getHighCount()), data.getHighCount() > 0 ? "warning" : "success"));
        sb.append("</div>\n");

        // Level Distribution
        sb.append("<section>\n");
        sb.append("<h2>📊 Log Level Distribution</h2>\n");
        sb.append("<table>\n");
        sb.append("<tr><th>Level</th><th>Count</th><th>Distribution</th></tr>\n");
        
        long total = data.getLevelDistribution().values().stream().mapToLong(l -> l).sum();
        for (LogLevel level : LogLevel.values()) {
            long count = data.getLevelDistribution().getOrDefault(level, 0L);
            if (count > 0) {
                double pct = total > 0 ? (double) count / total * 100 : 0;
                String color = getLevelColor(level);
                sb.append("<tr><td>").append(getLevelEmoji(level)).append(" ").append(level).append("</td>");
                sb.append("<td>").append(formatNumber(count)).append("</td>");
                sb.append("<td><div class=\"progress-bar\"><div class=\"progress-fill\" style=\"width: ")
                  .append(String.format("%.1f", pct)).append("%; background: ").append(color)
                  .append(";\"></div></div></td></tr>\n");
            }
        }
        sb.append("</table>\n");
        sb.append("</section>\n");

        // Error Clusters
        if (data.getClusters() != null && !data.getClusters().isEmpty()) {
            sb.append("<section>\n");
            sb.append("<h2>🔴 Error Clusters</h2>\n");
            
            int shown = 0;
            for (ErrorCluster cluster : data.getClusters()) {
                if (shown >= 15) break;
                shown++;
                
                String severityClass = cluster.getSeverity().name().toLowerCase();
                
                sb.append("<div class=\"cluster-card ").append(severityClass).append("\">\n");
                sb.append("<div class=\"cluster-header\">\n");
                sb.append("<span class=\"cluster-id\">").append(cluster.getId()).append("</span>\n");
                sb.append("<span class=\"severity-badge ").append(severityClass).append("\">")
                  .append(cluster.getSeverity()).append("</span>\n");
                sb.append("</div>\n");
                
                sb.append("<div class=\"exception-name\">").append(escapeHtml(cluster.getExceptionClass())).append("</div>\n");
                
                sb.append("<div class=\"cluster-meta\">\n");
                sb.append("<span>📈 ").append(cluster.getOccurrenceCount()).append(" occurrences</span>\n");
                sb.append("<span>🕐 First: ").append(DATE_FORMAT.format(cluster.getFirstSeen())).append("</span>\n");
                sb.append("<span>🕐 Last: ").append(DATE_FORMAT.format(cluster.getLastSeen())).append("</span>\n");
                sb.append("</div>\n");
                
                if (cluster.getFullLocation() != null && !cluster.getFullLocation().isEmpty()) {
                    sb.append("<div class=\"cluster-meta\"><span>📍 ")
                      .append(escapeHtml(cluster.getFullLocation())).append("</span></div>\n");
                }

                // Analysis if available
                AnalysisResult analysis = data.getAnalysisFor(cluster.getId());
                if (analysis != null) {
                    sb.append("<div class=\"analysis-box\">\n");
                    sb.append("<h4>🤖 AI Analysis</h4>\n");
                    if (analysis.getExplanation() != null) {
                        sb.append("<p><strong>Explanation:</strong> ")
                          .append(escapeHtml(analysis.getExplanation())).append("</p>\n");
                    }
                    if (analysis.getRecommendation() != null) {
                        sb.append("<p><strong>Recommendation:</strong> ")
                          .append(escapeHtml(analysis.getRecommendation())).append("</p>\n");
                    }
                    sb.append("</div>\n");
                }
                
                sb.append("</div>\n");
            }
            sb.append("</section>\n");
        }

        // Footer
        sb.append("""
            </div>
            <footer>
                <p>Report generated by <a href="https://github.com/logai/logai">LogAI</a> v1.0.0</p>
                <p>Run <code>logai scan --analyze</code> for AI-powered analysis</p>
            </footer>
            </body>
            </html>
            """);

        return sb.toString();
    }

    @Override
    public void write(ReportData data, Path outputPath) throws IOException {
        String content = generate(data);
        Files.writeString(outputPath, content);
    }

    @Override
    public String getFileExtension() {
        return "html";
    }

    private String summaryCard(String title, String value, String cssClass) {
        return String.format(
            "<div class=\"summary-card %s\"><h3>%s</h3><div class=\"value\">%s</div></div>\n",
            cssClass, title, value
        );
    }

    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private String getHealthClass(ReportData data) {
        if (data.getCriticalCount() > 0) return "critical";
        if (data.getHighCount() > 0) return "warning";
        if (data.getErrorLogs() > 0) return "attention";
        return "healthy";
    }

    private String getHealthText(ReportData data) {
        if (data.getCriticalCount() > 0) return "🔴 CRITICAL - Immediate attention required";
        if (data.getHighCount() > 0) return "🟠 WARNING - High priority issues detected";
        if (data.getErrorLogs() > 0) return "🟡 ATTENTION - Some errors detected";
        return "🟢 HEALTHY - No significant issues";
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

    private String getLevelColor(LogLevel level) {
        return switch (level) {
            case TRACE -> "#6b7280";
            case DEBUG -> "#3b82f6";
            case INFO -> "#22c55e";
            case WARN -> "#eab308";
            case ERROR -> "#ef4444";
            case FATAL -> "#7c3aed";
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}

