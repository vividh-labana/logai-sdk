package com.logai.llm;

import com.logai.core.model.CodeContext;
import com.logai.llm.model.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates and manages code patches from LLM analysis.
 */
public class PatchGenerator {

    private static final Logger logger = LoggerFactory.getLogger(PatchGenerator.class);
    
    private static final String PATCH_DIR = "logai-fixes";
    private static final Pattern DIFF_HEADER_PATTERN = Pattern.compile(
            "^(---\\s+.+|\\+\\+\\+\\s+.+|@@.+@@)",
            Pattern.MULTILINE
    );

    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final Path patchDirectory;

    public PatchGenerator(OpenAIClient openAIClient) {
        this(openAIClient, new PromptBuilder(), Paths.get(PATCH_DIR));
    }

    public PatchGenerator(OpenAIClient openAIClient, PromptBuilder promptBuilder, Path patchDirectory) {
        this.openAIClient = openAIClient;
        this.promptBuilder = promptBuilder;
        this.patchDirectory = patchDirectory;
    }

    /**
     * Generate a patch from an analysis result that doesn't already have one.
     */
    public Optional<String> generatePatch(AnalysisResult analysis, CodeContext codeContext) {
        if (analysis.hasPatch()) {
            return Optional.of(analysis.getPatch());
        }

        if (!openAIClient.isConfigured()) {
            logger.warn("OpenAI client not configured, cannot generate patch");
            return Optional.empty();
        }

        if (codeContext == null || codeContext.getMethodBody() == null) {
            logger.warn("No code context available for patch generation");
            return Optional.empty();
        }

        String prompt = promptBuilder.buildPatchPrompt(
                analysis.getExplanation(),
                analysis.getRootCause(),
                codeContext
        );

        logger.info("Generating patch for cluster: {}", analysis.getClusterId());
        Optional<String> response = openAIClient.chat(promptBuilder.getSystemPrompt(), prompt);

        return response.flatMap(this::extractPatchFromResponse);
    }

    /**
     * Save a patch to a file.
     */
    public Path savePatch(String patch, String clusterId, String fileName) throws IOException {
        // Ensure patch directory exists
        Files.createDirectories(patchDirectory);

        // Generate patch file name
        String patchFileName = generatePatchFileName(clusterId, fileName);
        Path patchPath = patchDirectory.resolve(patchFileName);

        // Write patch with proper header
        String fullPatch = formatPatch(patch, fileName);
        Files.writeString(patchPath, fullPatch);

        logger.info("Saved patch to: {}", patchPath);
        return patchPath;
    }

    /**
     * Load a patch from a file.
     */
    public Optional<String> loadPatch(String patchFileName) {
        Path patchPath = patchDirectory.resolve(patchFileName);
        if (!Files.exists(patchPath)) {
            logger.warn("Patch file not found: {}", patchPath);
            return Optional.empty();
        }

        try {
            return Optional.of(Files.readString(patchPath));
        } catch (IOException e) {
            logger.error("Failed to read patch file: {}", patchPath, e);
            return Optional.empty();
        }
    }

    /**
     * Apply a patch to a file (dry run by default).
     */
    public PatchResult applyPatch(String patch, Path targetFile, boolean dryRun) {
        if (!Files.exists(targetFile)) {
            return new PatchResult(false, "Target file does not exist: " + targetFile);
        }

        try {
            String originalContent = Files.readString(targetFile);
            Optional<String> patchedContent = applyUnifiedDiff(originalContent, patch);

            if (patchedContent.isEmpty()) {
                return new PatchResult(false, "Failed to apply patch - diff format may be incorrect");
            }

            if (dryRun) {
                return new PatchResult(true, "Patch would apply successfully (dry run)", patchedContent.get());
            }

            // Backup original file
            Path backupPath = Paths.get(targetFile.toString() + ".bak");
            Files.writeString(backupPath, originalContent);

            // Write patched content
            Files.writeString(targetFile, patchedContent.get());

            return new PatchResult(true, "Patch applied successfully", patchedContent.get());

        } catch (IOException e) {
            logger.error("Error applying patch", e);
            return new PatchResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Apply a unified diff to source content.
     * This is a simplified implementation that handles basic unified diffs.
     */
    private Optional<String> applyUnifiedDiff(String original, String patch) {
        try {
            String[] lines = original.split("\n", -1);
            String[] patchLines = patch.split("\n");
            
            StringBuilder result = new StringBuilder();
            int currentLine = 0;
            int patchIndex = 0;

            // Skip header lines (---, +++, etc.)
            while (patchIndex < patchLines.length && 
                   (patchLines[patchIndex].startsWith("---") || 
                    patchLines[patchIndex].startsWith("+++"))) {
                patchIndex++;
            }

            while (patchIndex < patchLines.length) {
                String patchLine = patchLines[patchIndex];

                if (patchLine.startsWith("@@")) {
                    // Parse hunk header: @@ -start,count +start,count @@
                    Pattern hunkPattern = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
                    Matcher hunkMatcher = hunkPattern.matcher(patchLine);
                    
                    if (hunkMatcher.find()) {
                        int oldStart = Integer.parseInt(hunkMatcher.group(1)) - 1;
                        
                        // Copy lines before this hunk
                        while (currentLine < oldStart && currentLine < lines.length) {
                            result.append(lines[currentLine]).append("\n");
                            currentLine++;
                        }
                    }
                    patchIndex++;
                    continue;
                }

                if (patchLine.startsWith("-")) {
                    // Line to remove - skip the original line
                    currentLine++;
                } else if (patchLine.startsWith("+")) {
                    // Line to add
                    result.append(patchLine.substring(1)).append("\n");
                } else if (patchLine.startsWith(" ") || patchLine.isEmpty()) {
                    // Context line - copy from original
                    if (currentLine < lines.length) {
                        result.append(lines[currentLine]).append("\n");
                        currentLine++;
                    }
                }
                patchIndex++;
            }

            // Copy remaining lines
            while (currentLine < lines.length) {
                result.append(lines[currentLine]);
                if (currentLine < lines.length - 1) {
                    result.append("\n");
                }
                currentLine++;
            }

            return Optional.of(result.toString());

        } catch (Exception e) {
            logger.error("Failed to apply unified diff", e);
            return Optional.empty();
        }
    }

    /**
     * Extract patch content from LLM response.
     */
    private Optional<String> extractPatchFromResponse(String response) {
        Pattern patchPattern = Pattern.compile(
                "```(?:diff)?\\s*(.+?)```",
                Pattern.DOTALL
        );
        Matcher matcher = patchPattern.matcher(response);
        
        if (matcher.find()) {
            String patch = matcher.group(1).trim();
            if (!patch.toLowerCase().contains("no code changes") &&
                !patch.toLowerCase().contains("not applicable") &&
                patch.length() > 10) {
                return Optional.of(patch);
            }
        }
        
        return Optional.empty();
    }

    /**
     * Format patch with proper unified diff header.
     */
    private String formatPatch(String patch, String fileName) {
        // Check if patch already has proper headers
        if (patch.contains("---") && patch.contains("+++")) {
            return patch;
        }

        StringBuilder formatted = new StringBuilder();
        formatted.append("--- a/").append(fileName).append("\n");
        formatted.append("+++ b/").append(fileName).append("\n");
        formatted.append(patch);
        return formatted.toString();
    }

    /**
     * Generate a filename for a patch.
     */
    private String generatePatchFileName(String clusterId, String sourceFileName) {
        String baseName = sourceFileName != null 
                ? sourceFileName.replace(".java", "") 
                : "unknown";
        return String.format("%s_%s.diff", baseName, clusterId);
    }

    /**
     * List all saved patches.
     */
    public java.util.List<Path> listPatches() throws IOException {
        if (!Files.exists(patchDirectory)) {
            return java.util.Collections.emptyList();
        }
        
        try (var stream = Files.list(patchDirectory)) {
            return stream
                    .filter(p -> p.toString().endsWith(".diff"))
                    .toList();
        }
    }

    /**
     * Result of a patch application.
     */
    public static class PatchResult {
        private final boolean success;
        private final String message;
        private final String patchedContent;

        public PatchResult(boolean success, String message) {
            this(success, message, null);
        }

        public PatchResult(boolean success, String message, String patchedContent) {
            this.success = success;
            this.message = message;
            this.patchedContent = patchedContent;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getPatchedContent() {
            return patchedContent;
        }
    }
}

