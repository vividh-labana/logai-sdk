package com.logai.cli.command;

import com.logai.cli.config.LogAIConfig;
import com.logai.core.analysis.CodeContextExtractor;
import com.logai.core.analysis.ErrorClusterer;
import com.logai.core.model.CodeContext;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.llm.InsightGenerator;
import com.logai.llm.OpenAIClient;
import com.logai.llm.PatchGenerator;
import com.logai.llm.model.AnalysisResult;
import com.logai.sdk.LogStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generate and apply code fixes.
 */
@Command(
        name = "fix",
        description = "Generate and apply code fixes for errors"
)
public class FixCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Cluster ID or patch file", arity = "0..1")
    private String target;

    @Option(names = {"--generate", "-g"}, 
            description = "Generate a fix for the specified cluster")
    private boolean generate;

    @Option(names = {"--apply", "-a"}, 
            description = "Apply a patch file")
    private boolean apply;

    @Option(names = {"--dry-run"}, 
            description = "Show what would be changed without applying",
            defaultValue = "false")
    private boolean dryRun;

    @Option(names = {"--list", "-l"}, 
            description = "List available patches")
    private boolean list;

    @Option(names = {"--db", "-d"}, 
            description = "Database path")
    private String dbPath;

    @Option(names = {"--output", "-o"}, 
            description = "Output directory for patches",
            defaultValue = "logai-fixes")
    private String outputDir;

    @Override
    public Integer call() {
        LogAIConfig config = LogAIConfig.load();

        if (list) {
            return listPatches();
        }

        if (generate && target != null) {
            return generateFix(config, target);
        }

        if (apply && target != null) {
            return applyPatch(target);
        }

        // If no specific action, show help
        System.out.println("Usage:");
        System.out.println("  logai fix --generate <cluster-id>   Generate a fix for an error cluster");
        System.out.println("  logai fix --apply <patch-file>      Apply a patch file");
        System.out.println("  logai fix --list                    List available patches");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  logai fix --generate ERR-12345678");
        System.out.println("  logai fix --apply logai-fixes/MyClass_ERR-12345678.diff");
        System.out.println("  logai fix --apply MyClass_fix.diff --dry-run");

        return 0;
    }

    private Integer generateFix(LogAIConfig config, String clusterId) {
        if (!config.isOpenAIConfigured()) {
            System.err.println("❌ OpenAI API key not configured.");
            System.err.println("   Set OPENAI_API_KEY environment variable or run:");
            System.err.println("   logai config --set openai.api_key=<your-key>");
            return 1;
        }

        String database = dbPath != null ? dbPath : config.getDatabasePath();

        System.out.printf("🔧 Generating fix for cluster: %s%n%n", clusterId);

        try (LogStore store = new LogStore(database)) {
            // Query recent errors
            Instant start = Instant.now().minus(Duration.ofDays(7));
            List<LogEntry> errors = store.queryErrors(start, Instant.now());

            if (errors.isEmpty()) {
                System.err.println("No errors found in the database.");
                return 1;
            }

            // Cluster and find target
            ErrorClusterer clusterer = new ErrorClusterer();
            List<ErrorCluster> clusters = clusterer.cluster(errors);

            ErrorCluster cluster = clusters.stream()
                    .filter(c -> c.getId().equalsIgnoreCase(clusterId))
                    .findFirst()
                    .orElse(null);

            if (cluster == null) {
                System.err.println("❌ Cluster not found: " + clusterId);
                System.err.println("   Use 'logai clusters' to see available clusters.");
                return 1;
            }

            // Extract code context
            CodeContextExtractor extractor = new CodeContextExtractor(config.getSourcePaths());
            Optional<CodeContext> codeContext = Optional.empty();
            
            if (cluster.getPrimaryClass() != null && cluster.getPrimaryLine() != null) {
                codeContext = extractor.extractContext(cluster.getPrimaryClass(), cluster.getPrimaryLine());
            }

            if (codeContext.isEmpty()) {
                System.err.println("⚠️  Could not extract code context.");
                System.err.println("   Make sure source paths are configured correctly.");
                System.err.println("   Current paths: " + config.getSourcePaths());
            }

            // Generate analysis
            OpenAIClient client = new OpenAIClient(config.getOpenaiApiKey(), config.getOpenaiModel());
            InsightGenerator generator = new InsightGenerator(client);

            System.out.println("🤖 Analyzing error and generating fix...\n");

            Optional<AnalysisResult> analysisOpt = codeContext.isPresent()
                    ? generator.analyze(cluster, codeContext.get())
                    : generator.analyzeSimple(cluster);

            if (analysisOpt.isEmpty()) {
                System.err.println("❌ Failed to generate analysis.");
                return 1;
            }

            AnalysisResult analysis = analysisOpt.get();

            // Display analysis
            System.out.println("📝 EXPLANATION:");
            System.out.println("   " + wrapText(analysis.getExplanation(), 70));
            System.out.println();

            if (analysis.getRootCause() != null) {
                System.out.println("🔍 ROOT CAUSE:");
                System.out.println("   " + wrapText(analysis.getRootCause(), 70));
                System.out.println();
            }

            if (analysis.getRecommendation() != null) {
                System.out.println("💡 RECOMMENDATION:");
                System.out.println("   " + wrapText(analysis.getRecommendation(), 70));
                System.out.println();
            }

            // Handle patch
            if (analysis.hasPatch()) {
                System.out.println("🔧 GENERATED PATCH:");
                System.out.println("─".repeat(60));
                System.out.println(analysis.getPatch());
                System.out.println("─".repeat(60));
                System.out.println();

                // Save patch
                PatchGenerator patchGen = new PatchGenerator(client);
                String fileName = cluster.getPrimaryFile() != null ? cluster.getPrimaryFile() : "unknown";
                Path patchPath = patchGen.savePatch(analysis.getPatch(), clusterId, fileName);

                System.out.printf("✅ Patch saved to: %s%n", patchPath);
                System.out.println();
                System.out.println("To apply this patch:");
                System.out.printf("   logai fix --apply %s%n", patchPath);
                System.out.printf("   logai fix --apply %s --dry-run  (preview changes)%n", patchPath);

            } else {
                System.out.println("ℹ️  No code patch generated.");
                System.out.println("   The issue may be configuration or data-related rather than a code bug.");
            }

            return 0;

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private Integer applyPatch(String patchFile) {
        Path patchPath = Paths.get(patchFile);
        if (!Files.exists(patchPath)) {
            // Try in default patch directory
            patchPath = Paths.get(outputDir, patchFile);
        }

        if (!Files.exists(patchPath)) {
            System.err.println("❌ Patch file not found: " + patchFile);
            return 1;
        }

        try {
            String patch = Files.readString(patchPath);
            
            // Extract target file from patch header
            String targetFile = extractTargetFile(patch);
            if (targetFile == null) {
                System.err.println("❌ Could not determine target file from patch.");
                System.err.println("   Patch should have a +++ b/path/to/file.java header.");
                return 1;
            }

            Path targetPath = Paths.get(targetFile);
            if (!Files.exists(targetPath)) {
                System.err.println("❌ Target file not found: " + targetFile);
                return 1;
            }

            System.out.printf("📄 Patch file: %s%n", patchPath);
            System.out.printf("📁 Target file: %s%n", targetPath);
            System.out.println();

            if (dryRun) {
                System.out.println("🔍 DRY RUN - showing what would change:");
                System.out.println();
            }

            // Apply patch
            LogAIConfig config = LogAIConfig.load();
            OpenAIClient client = new OpenAIClient(config.getOpenaiApiKey());
            PatchGenerator patchGen = new PatchGenerator(client);

            PatchGenerator.PatchResult result = patchGen.applyPatch(patch, targetPath, dryRun);

            if (result.isSuccess()) {
                System.out.println("✅ " + result.getMessage());
                
                if (dryRun && result.getPatchedContent() != null) {
                    System.out.println();
                    System.out.println("Preview of patched file:");
                    System.out.println("─".repeat(60));
                    // Show first 50 lines
                    String[] lines = result.getPatchedContent().split("\n");
                    for (int i = 0; i < Math.min(lines.length, 50); i++) {
                        System.out.printf("%4d | %s%n", i + 1, lines[i]);
                    }
                    if (lines.length > 50) {
                        System.out.printf("     ... (%d more lines)%n", lines.length - 50);
                    }
                    System.out.println("─".repeat(60));
                }
                
                return 0;
            } else {
                System.err.println("❌ " + result.getMessage());
                return 1;
            }

        } catch (IOException e) {
            System.err.println("❌ Error reading patch file: " + e.getMessage());
            return 1;
        }
    }

    private Integer listPatches() {
        Path patchDir = Paths.get(outputDir);
        
        if (!Files.exists(patchDir)) {
            System.out.println("No patches found. Directory does not exist: " + outputDir);
            return 0;
        }

        try {
            List<Path> patches;
            try (var stream = Files.list(patchDir)) {
                patches = stream
                        .filter(p -> p.toString().endsWith(".diff"))
                        .toList();
            }

            if (patches.isEmpty()) {
                System.out.println("No patches found in: " + outputDir);
                return 0;
            }

            System.out.println();
            System.out.println("Available Patches:");
            System.out.println("─".repeat(60));
            
            for (Path patch : patches) {
                System.out.printf("  %s%n", patch.getFileName());
            }
            
            System.out.println("─".repeat(60));
            System.out.printf("Total: %d patches%n", patches.size());
            System.out.println();
            System.out.println("To apply a patch:");
            System.out.println("  logai fix --apply <patch-file>");
            System.out.println("  logai fix --apply <patch-file> --dry-run  (preview)");

            return 0;

        } catch (IOException e) {
            System.err.println("Error listing patches: " + e.getMessage());
            return 1;
        }
    }

    private String extractTargetFile(String patch) {
        for (String line : patch.split("\n")) {
            if (line.startsWith("+++ b/") || line.startsWith("+++ ")) {
                String path = line.substring(line.indexOf(" ") + 1);
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                return path.trim();
            }
        }
        return null;
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
        
        return result.toString().trim();
    }
}

