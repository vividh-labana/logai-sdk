// Supabase Edge Function: analyze
// Calls Intuit's LLM service to analyze an error cluster and generate insights

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { callIntuitLLMWithSystem } from "../_shared/intuit-auth.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface AnalyzeRequest {
  cluster_id: string;
  model?: string;
}

const SYSTEM_PROMPT = `You are an expert Java developer and debugging specialist. 
You analyze application errors, identify root causes, and generate precise code fixes.

When analyzing errors:
1. Focus on the actual bug, not symptoms
2. Consider null safety, exception handling, and edge cases
3. Provide practical, minimal fixes
4. Generate valid unified diff patches when possible

Always structure your response with clear sections:
- EXPLANATION: What went wrong in plain English
- ROOT_CAUSE: The underlying issue causing this error
- RECOMMENDATION: How to fix it
- PATCH: A unified diff if code changes are needed (or "No code changes needed" if not applicable)`;

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseKey);

    const { cluster_id, model = "gpt-5-2025-08-07" }: AnalyzeRequest = await req.json();

    if (!cluster_id) {
      return new Response(
        JSON.stringify({ error: "cluster_id is required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Fetch the cluster
    const { data: cluster, error: clusterError } = await supabase
      .from("error_clusters")
      .select("*")
      .eq("id", cluster_id)
      .single();

    if (clusterError || !cluster) {
      return new Response(
        JSON.stringify({ error: "Cluster not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Fetch a sample log entry for this cluster
    const { data: sampleLogs } = await supabase
      .from("log_entries")
      .select("*")
      .eq("app_id", cluster.app_id)
      .in("level", ["ERROR", "FATAL"])
      .ilike("message", `%${cluster.message_pattern?.substring(0, 50) || ""}%`)
      .order("timestamp", { ascending: false })
      .limit(3);

    const sampleLog = sampleLogs?.[0];

    // Build the prompt
    const userPrompt = buildAnalysisPrompt(cluster, sampleLog);

    // Call Intuit LLM
    console.log("🤖 Calling Intuit LLM for analysis...");
    const assistantMessage = await callIntuitLLMWithSystem(
      SYSTEM_PROMPT,
      userPrompt,
      model,
      0.2,
      4096
    );

    if (!assistantMessage) {
      throw new Error("No response from Intuit LLM");
    }

    // Parse the response
    const analysis = parseAnalysisResponse(assistantMessage);

    // Save to database
    const { data: savedAnalysis, error: saveError } = await supabase
      .from("analysis_results")
      .insert({
        cluster_id,
        explanation: analysis.explanation,
        root_cause: analysis.rootCause,
        recommendation: analysis.recommendation,
        patch: analysis.patch,
        confidence: analysis.confidence,
        model_used: model,
        raw_response: assistantMessage,
      })
      .select()
      .single();

    if (saveError) {
      console.error("Failed to save analysis:", saveError);
    }

    return new Response(
      JSON.stringify({
        success: true,
        analysis_id: savedAnalysis?.id,
        cluster_id,
        explanation: analysis.explanation,
        root_cause: analysis.rootCause,
        recommendation: analysis.recommendation,
        patch: analysis.patch,
        confidence: analysis.confidence,
        has_patch: !!analysis.patch && !analysis.patch.toLowerCase().includes("no code changes"),
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );

  } catch (error) {
    console.error("Analysis error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

function buildAnalysisPrompt(cluster: any, sampleLog: any): string {
  const stackTrace = sampleLog?.stack_trace || "No stack trace available";
  const message = sampleLog?.message || cluster.message_pattern || "N/A";

  return `Analyze this Java application error and provide a fix.

## ERROR SUMMARY
Exception: ${cluster.exception_class || "Unknown"}
Message Pattern: ${cluster.message_pattern || "N/A"}
Occurrences: ${cluster.occurrence_count}
Location: ${cluster.primary_class || ""}${cluster.primary_method ? "." + cluster.primary_method : ""}${cluster.primary_line ? ":" + cluster.primary_line : ""}

## SAMPLE ERROR MESSAGE
${message}

## STACK TRACE
\`\`\`
${stackTrace}
\`\`\`

## FILE LOCATION
File: ${cluster.primary_file || "Unknown"}
Line: ${cluster.primary_line || "Unknown"}

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
\`\`\`diff
[Unified diff if applicable, or "No code changes needed"]
\`\`\``;
}

function parseAnalysisResponse(response: string): {
  explanation: string | null;
  rootCause: string | null;
  recommendation: string | null;
  patch: string | null;
  confidence: string;
} {
  const explanationMatch = response.match(/EXPLANATION:\s*(.+?)(?=ROOT_CAUSE:|RECOMMENDATION:|PATCH:|$)/s);
  const rootCauseMatch = response.match(/ROOT_CAUSE:\s*(.+?)(?=EXPLANATION:|RECOMMENDATION:|PATCH:|$)/s);
  const recommendationMatch = response.match(/RECOMMENDATION:\s*(.+?)(?=EXPLANATION:|ROOT_CAUSE:|PATCH:|$)/s);
  const patchMatch = response.match(/PATCH:\s*```(?:diff)?\s*(.+?)```/s);

  // Determine confidence based on response quality
  let confidenceScore = 0;
  if (explanationMatch) confidenceScore++;
  if (rootCauseMatch) confidenceScore++;
  if (recommendationMatch) confidenceScore++;
  if (patchMatch) confidenceScore++;
  if (response.length > 500) confidenceScore++;
  if (response.length > 1000) confidenceScore++;

  let confidence: string;
  if (confidenceScore >= 5) confidence = "HIGH";
  else if (confidenceScore >= 3) confidence = "MEDIUM";
  else if (confidenceScore >= 1) confidence = "LOW";
  else confidence = "UNKNOWN";

  return {
    explanation: explanationMatch?.[1]?.trim() || null,
    rootCause: rootCauseMatch?.[1]?.trim() || null,
    recommendation: recommendationMatch?.[1]?.trim() || null,
    patch: patchMatch?.[1]?.trim() || null,
    confidence,
  };
}
