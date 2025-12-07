package com.logai.cli.command;

import com.logai.cli.config.LogAIConfig;
import com.logai.core.analysis.CodeContextExtractor;
import com.logai.core.analysis.ErrorClusterer;
import com.logai.core.model.CodeContext;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.llm.InsightGenerator;
import com.logai.llm.OpenAIClient;
import com.logai.llm.model.AnalysisResult;
import com.logai.sdk.LogStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scan logs and analyze errors.
 */
@Command(
        name = "scan",
        description = "Scan and analyze recent logs for errors"
)
public class ScanCommand implements Callable<Integer> {

    @Option(names = {"--last", "-l"}, 
            description = "Time range to scan (e.g., 1h, 30m, 7d). Default: 1h",
            defaultValue = "1h")
    private String lastDuration;

    @Option(names = {"--db", "-d"}, 
            description = "Database path")
    private String dbPath;

    @Option(names = {"--analyze", "-a"}, 
            description = "Analyze errors with LLM",
            defaultValue = "false")
    private boolean analyze;

    @Option(names = {"--limit", "-n"}, 
            description = "Limit number of clusters to analyze",
            defaultValue = "5")
    private int limit;

    @Option(names = {"--verbose", "-v"}, 
            description = "Verbose output")
    private boolean verbose;

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhd])");

    @Override
    public Integer call() {
        LogAIConfig config = LogAIConfig.load();
        String database = dbPath != null ? dbPath : config.getDatabasePath();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    LogAI Error Scanner                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Parse duration
        Duration duration = parseDuration(lastDuration);
        Instant start = Instant.now().minus(duration);
        Instant end = Instant.now();

        System.out.printf("📊 Scanning logs from %s to %s%n", start, end);
        System.out.printf("📁 Database: %s%n%n", database);

        try (LogStore store = new LogStore(database)) {
            // Query errors
            List<LogEntry> errors = store.queryErrors(start, end);
            
            if (errors.isEmpty()) {
                System.out.println("✅ No errors found in the specified time range.");
                return 0;
            }

            System.out.printf("🔍 Found %d error entries%n%n", errors.size());

            // Cluster errors
            ErrorClusterer clusterer = new ErrorClusterer();
            List<ErrorCluster> clusters = clusterer.cluster(errors);

            System.out.printf("📦 Grouped into %d unique error clusters%n%n", clusters.size());
            
            // Display clusters
            printClusterSummary(clusters, limit);

            // Analyze with LLM if requested
            if (analyze && config.isOpenAIConfigured()) {
                System.out.println("\n🤖 Analyzing errors with AI...\n");
                analyzeWithLLM(clusters.subList(0, Math.min(limit, clusters.size())), config);
            } else if (analyze && !config.isOpenAIConfigured()) {
                System.out.println("\n⚠️  OpenAI API key not configured. Set OPENAI_API_KEY environment variable.");
                System.out.println("   Run 'logai config --set openai.api_key=<your-key>' to configure.");
            }

            return 0;

        } catch (Exception e) {
            System.err.println("❌ Error scanning logs: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private void printClusterSummary(List<ErrorCluster> clusters, int limit) {
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│ Top Error Clusters                                           │");
        System.out.println("├──────────────────────────────────────────────────────────────┤");

        int count = 0;
        for (ErrorCluster cluster : clusters) {
            if (count >= limit) break;
            count++;

            String severity = getSeverityEmoji(cluster.getSeverity());
            String exception = cluster.getExceptionClass() != null 
                    ? truncate(cluster.getExceptionClass(), 40) 
                    : "Unknown";
            
            System.out.printf("│ %s %-8s │ %3d occurrences │ %-24s │%n",
                    severity,
                    cluster.getId(),
                    cluster.getOccurrenceCount(),
                    exception
            );

            if (cluster.getFullLocation() != null && !cluster.getFullLocation().isEmpty()) {
                System.out.printf("│   └─ Location: %-47s │%n", 
                        truncate(cluster.getFullLocation(), 47));
            }

            if (cluster.getMessagePattern() != null) {
                System.out.printf("│   └─ Message: %-48s │%n", 
                        truncate(cluster.getMessagePattern(), 48));
            }

            System.out.println("├──────────────────────────────────────────────────────────────┤");
        }

        if (clusters.size() > limit) {
            System.out.printf("│ ... and %d more clusters (use --limit to show more)         │%n",
                    clusters.size() - limit);
        }

        System.out.println("└──────────────────────────────────────────────────────────────┘");
    }

    private void analyzeWithLLM(List<ErrorCluster> clusters, LogAIConfig config) {
        OpenAIClient client = new OpenAIClient(config.getOpenaiApiKey(), config.getOpenaiModel());
        InsightGenerator generator = new InsightGenerator(client);
        CodeContextExtractor extractor = new CodeContextExtractor(config.getSourcePaths());

        for (ErrorCluster cluster : clusters) {
            System.out.printf("─── Analyzing %s ─────────────────────────────────────────%n", cluster.getId());
            
            // Try to extract code context
            Optional<CodeContext> codeContext = Optional.empty();
            if (cluster.getPrimaryClass() != null && cluster.getPrimaryLine() != null) {
                codeContext = extractor.extractContext(cluster.getPrimaryClass(), cluster.getPrimaryLine());
            }

            // Generate analysis
            Optional<AnalysisResult> result = codeContext.isPresent()
                    ? generator.analyze(cluster, codeContext.get())
                    : generator.analyzeSimple(cluster);

            if (result.isPresent()) {
                AnalysisResult analysis = result.get();
                
                System.out.println("\n📝 EXPLANATION:");
                System.out.println(wrapText(analysis.getExplanation(), 60));
                
                if (analysis.getRootCause() != null) {
                    System.out.println("\n🔍 ROOT CAUSE:");
                    System.out.println(wrapText(analysis.getRootCause(), 60));
                }
                
                if (analysis.getRecommendation() != null) {
                    System.out.println("\n💡 RECOMMENDATION:");
                    System.out.println(wrapText(analysis.getRecommendation(), 60));
                }
                
                if (analysis.hasPatch()) {
                    System.out.println("\n🔧 PATCH AVAILABLE:");
                    System.out.println("   Run 'logai fix --generate " + cluster.getId() + "' to save the patch.");
                }
                
                System.out.printf("%n   Confidence: %s%n", analysis.getConfidence());
            } else {
                System.out.println("   ⚠️ Could not generate analysis for this error.");
            }
            
            System.out.println();
        }
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
                default -> Duration.ofHours(1);
            };
        }
        return Duration.ofHours(1);
    }

    private String getSeverityEmoji(ErrorCluster.ClusterSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "🔴";
            case HIGH -> "🟠";
            case MEDIUM -> "🟡";
            case LOW -> "🟢";
            default -> "⚪";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    private String wrapText(String text, int width) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        String[] words = text.split("\\s+");
        int lineLength = 0;
        
        for (String word : words) {
            if (lineLength + word.length() > width) {
                result.append("\n   ");
                lineLength = 3;
            }
            result.append(word).append(" ");
            lineLength += word.length() + 1;
        }
        
        return "   " + result.toString().trim();
    }
}

