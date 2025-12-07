package com.logai.cli.command;

import com.logai.cli.config.LogAIConfig;
import com.logai.core.analysis.ErrorClusterer;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.sdk.LogStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * List and inspect error clusters.
 */
@Command(
        name = "clusters",
        description = "List and inspect error clusters"
)
public class ClustersCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Cluster ID to inspect (optional)", arity = "0..1")
    private String clusterId;

    @Option(names = {"--limit", "-n"}, 
            description = "Maximum number of clusters to show",
            defaultValue = "10")
    private int limit;

    @Option(names = {"--last", "-l"}, 
            description = "Time range (e.g., 1h, 30m, 7d)",
            defaultValue = "24h")
    private String lastDuration;

    @Option(names = {"--db", "-d"}, 
            description = "Database path")
    private String dbPath;

    @Option(names = {"--verbose", "-v"}, 
            description = "Show verbose output including sample stack traces")
    private boolean verbose;

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhd])");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
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
            List<LogEntry> errors = store.queryErrors(start, end);
            
            if (errors.isEmpty()) {
                System.out.println("No errors found in the specified time range.");
                return 0;
            }

            ErrorClusterer clusterer = new ErrorClusterer();
            List<ErrorCluster> clusters = clusterer.cluster(errors);

            if (clusterId != null) {
                // Show details for specific cluster
                return showClusterDetails(clusters, clusterId);
            } else {
                // List all clusters
                return listClusters(clusters, limit);
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private Integer listClusters(List<ErrorCluster> clusters, int limit) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              Error Clusters                                   ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  %-12s │ %-6s │ %-8s │ %-42s ║%n", 
                "ID", "Count", "Severity", "Exception");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");

        int shown = 0;
        for (ErrorCluster cluster : clusters) {
            if (shown >= limit) break;
            shown++;

            String severity = formatSeverity(cluster.getSeverity());
            String exception = truncate(cluster.getExceptionClass(), 42);
            
            System.out.printf("║  %-12s │ %6d │ %-8s │ %-42s ║%n",
                    cluster.getId(),
                    cluster.getOccurrenceCount(),
                    severity,
                    exception
            );
        }

        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Total: %d clusters (%d shown)%46s║%n", 
                clusters.size(), shown, "");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Use 'logai clusters <cluster-id>' to see details for a specific cluster.");
        System.out.println();

        return 0;
    }

    private Integer showClusterDetails(List<ErrorCluster> clusters, String id) {
        ErrorCluster cluster = clusters.stream()
                .filter(c -> c.getId().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);

        if (cluster == null) {
            System.err.println("Cluster not found: " + id);
            System.err.println("Use 'logai clusters' to see available clusters.");
            return 1;
        }

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  Cluster: %-67s ║%n", cluster.getId());
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.printf("  Exception:    %s%n", cluster.getExceptionClass());
        System.out.printf("  Severity:     %s%n", formatSeverity(cluster.getSeverity()));
        System.out.printf("  Occurrences:  %d%n", cluster.getOccurrenceCount());
        System.out.printf("  First Seen:   %s%n", formatTime(cluster.getFirstSeen()));
        System.out.printf("  Last Seen:    %s%n", formatTime(cluster.getLastSeen()));
        System.out.println();

        if (cluster.getFullLocation() != null && !cluster.getFullLocation().isEmpty()) {
            System.out.println("  Location:");
            System.out.printf("    Class:      %s%n", cluster.getPrimaryClass());
            System.out.printf("    Method:     %s%n", cluster.getPrimaryMethod());
            System.out.printf("    Line:       %d%n", cluster.getPrimaryLine());
            System.out.printf("    File:       %s%n", cluster.getPrimaryFile());
            System.out.println();
        }

        if (cluster.getMessagePattern() != null) {
            System.out.println("  Message Pattern:");
            System.out.printf("    %s%n", cluster.getMessagePattern());
            System.out.println();
        }

        // Show sample entries
        List<LogEntry> samples = cluster.getSampleEntries(3);
        if (!samples.isEmpty()) {
            System.out.println("  Sample Entries:");
            System.out.println("  ──────────────");
            for (int i = 0; i < samples.size(); i++) {
                LogEntry entry = samples.get(i);
                System.out.printf("  [%d] %s - %s%n", 
                        i + 1, 
                        formatTime(entry.getTimestamp()),
                        truncate(entry.getMessage(), 60));
                
                if (verbose && entry.hasStackTrace()) {
                    System.out.println();
                    String[] lines = entry.getStackTrace().split("\n");
                    for (int j = 0; j < Math.min(lines.length, 10); j++) {
                        System.out.printf("      %s%n", lines[j]);
                    }
                    if (lines.length > 10) {
                        System.out.printf("      ... (%d more lines)%n", lines.length - 10);
                    }
                    System.out.println();
                }
            }
        }

        System.out.println();
        System.out.println("  Actions:");
        System.out.printf("    - Analyze:  logai scan --analyze --limit 1 -l %s%n", lastDuration);
        System.out.printf("    - Fix:      logai fix --generate %s%n", cluster.getId());
        System.out.println();

        return 0;
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

    private String formatSeverity(ErrorCluster.ClusterSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "CRITICAL";
            case HIGH -> "HIGH";
            case MEDIUM -> "MEDIUM";
            case LOW -> "LOW";
            default -> "UNKNOWN";
        };
    }

    private String formatTime(Instant instant) {
        if (instant == null) return "N/A";
        return TIME_FORMAT.format(instant);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "N/A";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}

