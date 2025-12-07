package com.logai.llm;

import com.logai.core.model.CodeContext;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;

/**
 * Constructs prompts for LLM analysis of errors.
 * Creates structured prompts with error context for the LLM to analyze.
 */
public class PromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You are an expert Java developer and debugging specialist. 
            You analyze application errors, identify root causes, and generate precise code fixes.
            
            When analyzing errors:
            1. Focus on the actual bug, not symptoms
            2. Consider null safety, exception handling, and edge cases
            3. Provide practical, minimal fixes
            4. Generate valid unified diff patches when possible
            
            Always structure your response with clear sections for:
            - EXPLANATION: What went wrong
            - ROOT_CAUSE: The underlying issue
            - RECOMMENDATION: How to fix it
            - PATCH: A unified diff if code changes are needed
            """;

    private static final String ANALYSIS_TEMPLATE = """
            Analyze this Java application error and provide a fix.
            
            ## ERROR SUMMARY
            Exception: %s
            Message Pattern: %s
            Occurrences: %d
            Location: %s
            
            ## STACK TRACE
            ```
            %s
            ```
            
            ## SOURCE CODE CONTEXT
            File: %s
            Target Line: %d
            
            ```java
            %s
            ```
            
            %s
            
            ## INSTRUCTIONS
            1. Explain what went wrong in plain English
            2. Identify the root cause
            3. Recommend a fix
            4. If possible, provide a unified diff patch to fix the issue
            
            Format your response as:
            
            EXPLANATION:
            [Your explanation here]
            
            ROOT_CAUSE:
            [The underlying issue]
            
            RECOMMENDATION:
            [How to fix it]
            
            PATCH:
            ```diff
            [Unified diff if applicable, or "No code changes needed" if the issue is configuration/data related]
            ```
            """;

    private static final String SIMPLE_ANALYSIS_TEMPLATE = """
            Analyze this Java application error and explain what went wrong.
            
            ## ERROR
            Exception: %s
            Message: %s
            
            ## STACK TRACE
            ```
            %s
            ```
            
            Provide a brief explanation of what caused this error and how to fix it.
            
            Format your response as:
            
            EXPLANATION:
            [Your explanation here]
            
            ROOT_CAUSE:
            [The underlying issue]
            
            RECOMMENDATION:
            [How to fix it]
            """;

    /**
     * Get the system prompt for error analysis.
     */
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Build a full analysis prompt for an error cluster with code context.
     */
    public String buildAnalysisPrompt(ErrorCluster cluster, CodeContext codeContext) {
        LogEntry sample = cluster.getMostRecentEntry();
        if (sample == null && !cluster.getEntries().isEmpty()) {
            sample = cluster.getEntries().get(0);
        }

        String stackTrace = sample != null && sample.hasStackTrace() 
                ? sample.getStackTrace() 
                : "No stack trace available";

        String methodSection = "";
        if (codeContext != null && codeContext.getMethodBody() != null) {
            methodSection = String.format("""
                    
                    ## METHOD BODY
                    ```java
                    %s
                    ```
                    """, codeContext.getMethodBody());
        }

        String codeSnippet = codeContext != null 
                ? codeContext.getPlainContext() 
                : "Source code not available";

        String filePath = codeContext != null 
                ? codeContext.getFilePath() 
                : cluster.getPrimaryFile();

        int targetLine = codeContext != null 
                ? codeContext.getTargetLine() 
                : (cluster.getPrimaryLine() != null ? cluster.getPrimaryLine() : 0);

        return String.format(ANALYSIS_TEMPLATE,
                cluster.getExceptionClass() != null ? cluster.getExceptionClass() : "Unknown",
                cluster.getMessagePattern() != null ? cluster.getMessagePattern() : "N/A",
                cluster.getOccurrenceCount(),
                cluster.getFullLocation(),
                stackTrace,
                filePath != null ? filePath : "Unknown",
                targetLine,
                codeSnippet,
                methodSection
        );
    }

    /**
     * Build a simple analysis prompt without code context.
     */
    public String buildSimplePrompt(ErrorCluster cluster) {
        LogEntry sample = cluster.getMostRecentEntry();
        if (sample == null && !cluster.getEntries().isEmpty()) {
            sample = cluster.getEntries().get(0);
        }

        String stackTrace = sample != null && sample.hasStackTrace() 
                ? sample.getStackTrace() 
                : "No stack trace available";

        String message = sample != null 
                ? sample.getMessage() 
                : cluster.getMessagePattern();

        return String.format(SIMPLE_ANALYSIS_TEMPLATE,
                cluster.getExceptionClass() != null ? cluster.getExceptionClass() : "Unknown",
                message != null ? message : "N/A",
                stackTrace
        );
    }

    /**
     * Build a prompt for generating a patch from existing analysis.
     */
    public String buildPatchPrompt(String explanation, String rootCause, CodeContext codeContext) {
        return String.format("""
                Based on this analysis, generate a unified diff patch to fix the issue.
                
                ## ANALYSIS
                Explanation: %s
                Root Cause: %s
                
                ## CURRENT CODE
                File: %s
                
                ```java
                %s
                ```
                
                ## INSTRUCTIONS
                Generate a minimal unified diff patch that fixes the issue.
                The patch should:
                1. Be in valid unified diff format
                2. Include file path in the header
                3. Only change what's necessary
                4. Include context lines (3 lines before/after)
                
                PATCH:
                ```diff
                [Your unified diff here]
                ```
                """,
                explanation,
                rootCause,
                codeContext.getFilePath(),
                codeContext.getMethodBody() != null ? codeContext.getMethodBody() : codeContext.getPlainContext()
        );
    }

    /**
     * Build a prompt for batch analysis of multiple errors.
     */
    public String buildBatchSummaryPrompt(int totalErrors, int clusterCount, String topErrorsSummary) {
        return String.format("""
                Provide a summary analysis of these application errors.
                
                ## OVERVIEW
                Total Errors: %d
                Unique Error Types: %d
                
                ## TOP ERRORS
                %s
                
                ## INSTRUCTIONS
                Provide:
                1. Overall health assessment
                2. Most critical issues to address
                3. Common patterns or themes
                4. Prioritized recommendations
                
                Keep the response concise and actionable.
                """,
                totalErrors,
                clusterCount,
                topErrorsSummary
        );
    }
}

