package com.logai.llm;

import com.logai.core.model.CodeContext;
import com.logai.core.model.ErrorCluster;
import com.logai.llm.model.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates human-readable insights from error clusters using LLM.
 */
public class InsightGenerator {

    private static final Logger logger = LoggerFactory.getLogger(InsightGenerator.class);

    // Patterns to extract sections from LLM response
    private static final Pattern EXPLANATION_PATTERN = Pattern.compile(
            "EXPLANATION:\\s*(.+?)(?=ROOT_CAUSE:|RECOMMENDATION:|PATCH:|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ROOT_CAUSE_PATTERN = Pattern.compile(
            "ROOT_CAUSE:\\s*(.+?)(?=EXPLANATION:|RECOMMENDATION:|PATCH:|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RECOMMENDATION_PATTERN = Pattern.compile(
            "RECOMMENDATION:\\s*(.+?)(?=EXPLANATION:|ROOT_CAUSE:|PATCH:|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATCH_PATTERN = Pattern.compile(
            "PATCH:\\s*```(?:diff)?\\s*(.+?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;

    public InsightGenerator(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
    }

    public InsightGenerator(OpenAIClient openAIClient, PromptBuilder promptBuilder) {
        this.openAIClient = openAIClient;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Generate insights for an error cluster with code context.
     */
    public Optional<AnalysisResult> analyze(ErrorCluster cluster, CodeContext codeContext) {
        if (!openAIClient.isConfigured()) {
            logger.warn("OpenAI client not configured, skipping analysis");
            return Optional.empty();
        }

        String prompt = promptBuilder.buildAnalysisPrompt(cluster, codeContext);
        String systemPrompt = promptBuilder.getSystemPrompt();

        logger.info("Analyzing error cluster: {}", cluster.getId());
        Optional<String> response = openAIClient.chat(systemPrompt, prompt);

        return response.map(r -> parseResponse(r, cluster.getId()));
    }

    /**
     * Generate insights for an error cluster without code context.
     */
    public Optional<AnalysisResult> analyzeSimple(ErrorCluster cluster) {
        if (!openAIClient.isConfigured()) {
            logger.warn("OpenAI client not configured, skipping analysis");
            return Optional.empty();
        }

        String prompt = promptBuilder.buildSimplePrompt(cluster);
        String systemPrompt = promptBuilder.getSystemPrompt();

        logger.info("Analyzing error cluster (simple): {}", cluster.getId());
        Optional<String> response = openAIClient.chat(systemPrompt, prompt);

        return response.map(r -> parseResponse(r, cluster.getId()));
    }

    /**
     * Parse the LLM response into structured analysis result.
     */
    private AnalysisResult parseResponse(String response, String clusterId) {
        AnalysisResult.Builder builder = AnalysisResult.builder()
                .clusterId(clusterId)
                .rawResponse(response);

        // Extract explanation
        Matcher explanationMatcher = EXPLANATION_PATTERN.matcher(response);
        if (explanationMatcher.find()) {
            builder.explanation(explanationMatcher.group(1).trim());
        }

        // Extract root cause
        Matcher rootCauseMatcher = ROOT_CAUSE_PATTERN.matcher(response);
        if (rootCauseMatcher.find()) {
            builder.rootCause(rootCauseMatcher.group(1).trim());
        }

        // Extract recommendation
        Matcher recommendationMatcher = RECOMMENDATION_PATTERN.matcher(response);
        if (recommendationMatcher.find()) {
            builder.recommendation(recommendationMatcher.group(1).trim());
        }

        // Extract patch
        Matcher patchMatcher = PATCH_PATTERN.matcher(response);
        if (patchMatcher.find()) {
            String patch = patchMatcher.group(1).trim();
            if (!patch.toLowerCase().contains("no code changes needed") && 
                !patch.toLowerCase().contains("not applicable")) {
                builder.patch(patch);
            }
        }

        // Determine confidence based on completeness of response
        AnalysisResult.Confidence confidence = determineConfidence(response);
        builder.confidence(confidence);

        return builder.build();
    }

    /**
     * Determine confidence level based on response quality.
     */
    private AnalysisResult.Confidence determineConfidence(String response) {
        if (response == null || response.isEmpty()) {
            return AnalysisResult.Confidence.UNKNOWN;
        }

        int score = 0;

        // Check for presence of key sections
        if (response.contains("EXPLANATION:")) score++;
        if (response.contains("ROOT_CAUSE:")) score++;
        if (response.contains("RECOMMENDATION:")) score++;
        if (response.contains("PATCH:")) score++;

        // Check response length (longer = more detailed)
        if (response.length() > 500) score++;
        if (response.length() > 1000) score++;

        // Check for code snippets
        if (response.contains("```")) score++;

        if (score >= 6) {
            return AnalysisResult.Confidence.HIGH;
        } else if (score >= 4) {
            return AnalysisResult.Confidence.MEDIUM;
        } else if (score >= 2) {
            return AnalysisResult.Confidence.LOW;
        } else {
            return AnalysisResult.Confidence.UNKNOWN;
        }
    }

    /**
     * Generate a batch summary for multiple error clusters.
     */
    public Optional<String> generateSummary(int totalErrors, int clusterCount, String topErrorsSummary) {
        if (!openAIClient.isConfigured()) {
            logger.warn("OpenAI client not configured, skipping summary generation");
            return Optional.empty();
        }

        String prompt = promptBuilder.buildBatchSummaryPrompt(totalErrors, clusterCount, topErrorsSummary);
        String systemPrompt = promptBuilder.getSystemPrompt();

        return openAIClient.chat(systemPrompt, prompt);
    }
}

