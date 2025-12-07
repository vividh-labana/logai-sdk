// Supabase Edge Function: generate-patch
// Generates a code fix patch for an error cluster using Intuit LLM

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { callIntuitLLMWithSystem } from "../_shared/intuit-auth.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface GeneratePatchRequest {
  cluster_id: string;
  analysis_id?: string;
  source_code?: string; // Optional: provide source code for better patches
  model?: string;
}

const PATCH_SYSTEM_PROMPT = `You are an expert Java developer specializing in bug fixes.
Your task is to generate precise unified diff patches to fix bugs.

Rules for generating patches:
1. Use valid unified diff format with proper headers
2. Include 3 lines of context before and after changes
3. Make minimal changes - only fix the specific bug
4. Include the file path in the header
5. Ensure the patch is syntactically correct

Example format:
--- a/src/main/java/com/example/MyClass.java
+++ b/src/main/java/com/example/MyClass.java
@@ -20,7 +20,9 @@ public class MyClass {
     public void myMethod() {
-        String value = obj.getValue();
+        if (obj == null) {
+            throw new IllegalArgumentException("obj cannot be null");
+        }
+        String value = obj.getValue();
     }
 }`;

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseKey);

    const { 
      cluster_id, 
      analysis_id, 
      source_code,
      model = "gpt-5-2025-08-07" 
    }: GeneratePatchRequest = await req.json();

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

    // Fetch existing analysis if available
    let analysis = null;
    if (analysis_id) {
      const { data } = await supabase
        .from("analysis_results")
        .select("*")
        .eq("id", analysis_id)
        .single();
      analysis = data;
    } else {
      // Get the latest analysis for this cluster
      const { data } = await supabase
        .from("analysis_results")
        .select("*")
        .eq("cluster_id", cluster_id)
        .order("created_at", { ascending: false })
        .limit(1)
        .single();
      analysis = data;
    }

    // Fetch a sample log entry
    const { data: sampleLogs } = await supabase
      .from("log_entries")
      .select("*")
      .eq("app_id", cluster.app_id)
      .in("level", ["ERROR", "FATAL"])
      .order("timestamp", { ascending: false })
      .limit(1);

    const sampleLog = sampleLogs?.[0];

    // Build the prompt
    const userPrompt = buildPatchPrompt(cluster, analysis, sampleLog, source_code);

    // Call Intuit LLM
    console.log("🤖 Calling Intuit LLM for patch generation...");
    const assistantMessage = await callIntuitLLMWithSystem(
      PATCH_SYSTEM_PROMPT,
      userPrompt,
      model,
      0.1, // Lower temperature for more precise code generation
      2048
    );

    if (!assistantMessage) {
      throw new Error("No response from Intuit LLM");
    }

    // Extract patch from response
    const patch = extractPatch(assistantMessage);

    if (!patch) {
      return new Response(
        JSON.stringify({
          success: false,
          error: "Could not generate a valid patch",
          raw_response: assistantMessage,
        }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Generate filename for the patch
    const fileName = cluster.primary_file?.replace(".java", "") || "unknown";
    const patchFileName = `${fileName}_${cluster.id.substring(0, 8)}.diff`;

    // Update analysis with patch if we have an analysis_id
    if (analysis?.id) {
      await supabase
        .from("analysis_results")
        .update({
          patch,
          patch_file_name: patchFileName,
        })
        .eq("id", analysis.id);
    } else {
      // Create new analysis result with just the patch
      await supabase
        .from("analysis_results")
        .insert({
          cluster_id,
          patch,
          patch_file_name: patchFileName,
          model_used: model,
          confidence: "MEDIUM",
        });
    }

    return new Response(
      JSON.stringify({
        success: true,
        cluster_id,
        patch,
        patch_file_name: patchFileName,
        can_apply: isValidDiff(patch),
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );

  } catch (error) {
    console.error("Generate patch error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

function buildPatchPrompt(
  cluster: any, 
  analysis: any, 
  sampleLog: any,
  sourceCode?: string
): string {
  let prompt = `Generate a unified diff patch to fix this Java bug.

## ERROR INFORMATION
Exception: ${cluster.exception_class || "Unknown"}
Location: ${cluster.primary_class || ""}${cluster.primary_method ? "." + cluster.primary_method : ""}${cluster.primary_line ? ":" + cluster.primary_line : ""}
File: ${cluster.primary_file || "Unknown.java"}
`;

  if (analysis?.root_cause) {
    prompt += `
## ROOT CAUSE
${analysis.root_cause}
`;
  }

  if (analysis?.recommendation) {
    prompt += `
## RECOMMENDED FIX
${analysis.recommendation}
`;
  }

  if (sampleLog?.stack_trace) {
    prompt += `
## STACK TRACE
\`\`\`
${sampleLog.stack_trace}
\`\`\`
`;
  }

  if (sourceCode) {
    prompt += `
## SOURCE CODE
\`\`\`java
${sourceCode}
\`\`\`
`;
  }

  prompt += `
## INSTRUCTIONS
Generate a valid unified diff patch that fixes this issue.
- Use proper unified diff format
- Include file path headers (--- a/... and +++ b/...)
- Include @@ line number markers
- Include 3 lines of context
- Only include the necessary changes

Provide ONLY the diff, no explanation:`;

  return prompt;
}

function extractPatch(response: string): string | null {
  // Try to extract from code block
  const codeBlockMatch = response.match(/```(?:diff)?\s*([\s\S]*?)```/);
  if (codeBlockMatch) {
    const patch = codeBlockMatch[1].trim();
    if (isValidDiff(patch)) {
      return formatPatch(patch);
    }
  }

  // Try to find diff directly in response
  const diffMatch = response.match(/((?:---[\s\S]*?\+\+\+[\s\S]*?@@[\s\S]+))/);
  if (diffMatch) {
    const patch = diffMatch[1].trim();
    if (isValidDiff(patch)) {
      return formatPatch(patch);
    }
  }

  // Return raw response if it looks like a diff
  if (response.includes("---") && response.includes("+++") && response.includes("@@")) {
    return formatPatch(response.trim());
  }

  return null;
}

function isValidDiff(patch: string): boolean {
  if (!patch || patch.length < 20) return false;
  
  // Check for basic diff markers
  const hasHeader = patch.includes("---") && patch.includes("+++");
  const hasHunk = patch.includes("@@");
  const hasChanges = patch.includes("\n+") || patch.includes("\n-");
  
  return hasHeader && hasHunk && hasChanges;
}

function formatPatch(patch: string): string {
  // Ensure proper line endings
  let formatted = patch.replace(/\r\n/g, "\n");
  
  // Ensure it ends with newline
  if (!formatted.endsWith("\n")) {
    formatted += "\n";
  }
  
  return formatted;
}
