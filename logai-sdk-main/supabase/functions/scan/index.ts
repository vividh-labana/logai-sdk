// Supabase Edge Function: scan
// Clusters errors and triggers AI analysis for an application

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface ScanRequest {
  app_id: string;
  hours?: number; // How many hours back to scan (default: 24)
  analyze?: boolean; // Whether to run AI analysis
}

interface LogEntry {
  id: number;
  timestamp: string;
  level: string;
  logger: string;
  message: string;
  stack_trace: string | null;
  file_name: string | null;
  line_number: number | null;
  class_name: string | null;
  method_name: string | null;
}

interface ErrorCluster {
  fingerprint: string;
  exception_class: string | null;
  message_pattern: string;
  primary_file: string | null;
  primary_line: number | null;
  primary_class: string | null;
  primary_method: string | null;
  occurrence_count: number;
  first_seen: string;
  last_seen: string;
  entries: LogEntry[];
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseKey);

    const { app_id, hours = 24, analyze = false }: ScanRequest = await req.json();

    if (!app_id) {
      return new Response(
        JSON.stringify({ error: "app_id is required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Create scan history record
    const { data: scanRecord, error: scanError } = await supabase
      .from("scan_history")
      .insert({ app_id, status: "RUNNING" })
      .select()
      .single();

    if (scanError) {
      throw new Error(`Failed to create scan record: ${scanError.message}`);
    }

    const scanId = scanRecord.id;

    try {
      // Query error logs from the last N hours
      const startTime = new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
      
      const { data: errorLogs, error: queryError } = await supabase
        .from("log_entries")
        .select("*")
        .eq("app_id", app_id)
        .in("level", ["ERROR", "FATAL"])
        .gte("timestamp", startTime)
        .order("timestamp", { ascending: false })
        .limit(1000);

      if (queryError) {
        throw new Error(`Failed to query logs: ${queryError.message}`);
      }

      const logs = errorLogs || [];
      
      // Cluster the errors
      const clusters = clusterErrors(logs);
      
      // Upsert clusters to database
      let clustersCreated = 0;
      const clusterIds: string[] = [];
      
      for (const cluster of clusters) {
        const { data: upsertedCluster, error: upsertError } = await supabase
          .from("error_clusters")
          .upsert({
            app_id,
            fingerprint: cluster.fingerprint,
            exception_class: cluster.exception_class,
            message_pattern: cluster.message_pattern,
            primary_file: cluster.primary_file,
            primary_line: cluster.primary_line,
            primary_class: cluster.primary_class,
            primary_method: cluster.primary_method,
            occurrence_count: cluster.occurrence_count,
            severity: calculateSeverity(cluster.occurrence_count),
            first_seen: cluster.first_seen,
            last_seen: cluster.last_seen,
          }, { onConflict: "app_id,fingerprint" })
          .select()
          .single();

        if (!upsertError && upsertedCluster) {
          clustersCreated++;
          clusterIds.push(upsertedCluster.id);
        }
      }

      // Update scan history
      await supabase
        .from("scan_history")
        .update({
          status: "COMPLETED",
          completed_at: new Date().toISOString(),
          logs_scanned: logs.length,
          errors_found: logs.length,
          clusters_created: clustersCreated,
        })
        .eq("id", scanId);

      return new Response(
        JSON.stringify({
          success: true,
          scan_id: scanId,
          logs_scanned: logs.length,
          clusters_found: clusters.length,
          clusters_created: clustersCreated,
          cluster_ids: clusterIds,
        }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );

    } catch (error) {
      // Update scan history with error
      await supabase
        .from("scan_history")
        .update({
          status: "FAILED",
          completed_at: new Date().toISOString(),
          error_message: error.message,
        })
        .eq("id", scanId);

      throw error;
    }

  } catch (error) {
    console.error("Scan error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

/**
 * Cluster similar errors together based on stack trace fingerprinting
 */
function clusterErrors(logs: LogEntry[]): ErrorCluster[] {
  const clusterMap = new Map<string, ErrorCluster>();

  for (const log of logs) {
    const fingerprint = computeFingerprint(log);
    
    if (clusterMap.has(fingerprint)) {
      const cluster = clusterMap.get(fingerprint)!;
      cluster.occurrence_count++;
      cluster.entries.push(log);
      
      if (log.timestamp < cluster.first_seen) {
        cluster.first_seen = log.timestamp;
      }
      if (log.timestamp > cluster.last_seen) {
        cluster.last_seen = log.timestamp;
      }
    } else {
      clusterMap.set(fingerprint, {
        fingerprint,
        exception_class: extractExceptionClass(log.stack_trace),
        message_pattern: normalizeMessage(log.message),
        primary_file: log.file_name,
        primary_line: log.line_number,
        primary_class: log.class_name,
        primary_method: log.method_name,
        occurrence_count: 1,
        first_seen: log.timestamp,
        last_seen: log.timestamp,
        entries: [log],
      });
    }
  }

  // Sort by occurrence count descending
  return Array.from(clusterMap.values())
    .sort((a, b) => b.occurrence_count - a.occurrence_count);
}

/**
 * Compute a fingerprint for an error log
 */
function computeFingerprint(log: LogEntry): string {
  const parts: string[] = [];

  // Use stack trace if available
  if (log.stack_trace) {
    const frames = extractTopFrames(log.stack_trace, 5);
    parts.push(...frames);
  }

  // Fall back to location info
  if (parts.length === 0) {
    if (log.class_name) parts.push(log.class_name);
    if (log.method_name) parts.push(log.method_name);
    if (log.line_number) parts.push(String(log.line_number));
  }

  // If still empty, use normalized message
  if (parts.length === 0) {
    parts.push(normalizeMessage(log.message || "unknown"));
  }

  return parts.join("|");
}

/**
 * Extract top N non-framework stack frames
 */
function extractTopFrames(stackTrace: string, count: number): string[] {
  const lines = stackTrace.split("\n");
  const frames: string[] = [];
  
  const frameRegex = /at\s+([^\s]+)\(([^)]+)\)/;
  const frameworkPrefixes = [
    "java.", "javax.", "sun.", "com.sun.", "jdk.",
    "org.springframework.", "org.apache.", "org.hibernate.",
    "org.slf4j.", "ch.qos.logback."
  ];

  for (const line of lines) {
    if (frames.length >= count) break;
    
    const match = line.match(frameRegex);
    if (match) {
      const className = match[1];
      const isFramework = frameworkPrefixes.some(prefix => className.startsWith(prefix));
      
      if (!isFramework) {
        frames.push(`${match[1]}:${match[2]}`);
      }
    }
  }

  return frames;
}

/**
 * Extract exception class from stack trace
 */
function extractExceptionClass(stackTrace: string | null): string | null {
  if (!stackTrace) return null;
  
  const firstLine = stackTrace.split("\n")[0];
  const match = firstLine.match(/^([A-Za-z0-9_.]+(?:Exception|Error|Throwable))/);
  return match ? match[1] : firstLine.split(":")[0].trim();
}

/**
 * Normalize a message by replacing variable parts
 */
function normalizeMessage(message: string | null): string {
  if (!message) return "";
  
  return message
    // UUIDs
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, "<UUID>")
    // Long numbers (IDs)
    .replace(/\b\d{6,}\b/g, "<ID>")
    // Timestamps
    .replace(/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}/g, "<TIMESTAMP>")
    // IP addresses
    .replace(/\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/g, "<IP>")
    // Emails
    .replace(/[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}/g, "<EMAIL>")
    // Quoted strings
    .replace(/"[^"]+"/g, '"<STRING>"')
    .replace(/'[^']+'/g, "'<STRING>'")
    .trim();
}

/**
 * Calculate severity based on occurrence count
 */
function calculateSeverity(count: number): string {
  if (count >= 100) return "CRITICAL";
  if (count >= 50) return "HIGH";
  if (count >= 10) return "MEDIUM";
  return "LOW";
}

