package com.logai.report.model;

import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogLevel;
import com.logai.llm.model.AnalysisResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data container for report generation.
 * Holds all the information needed to generate a comprehensive report.
 */
public class ReportData {

    private Instant generatedAt;
    private Instant periodStart;
    private Instant periodEnd;
    private int totalLogs;
    private int errorLogs;
    private Map<LogLevel, Long> levelDistribution;
    private List<ErrorCluster> clusters;
    private Map<String, AnalysisResult> analysisResults;
    private String overallSummary;

    public ReportData() {
        this.generatedAt = Instant.now();
        this.levelDistribution = new HashMap<>();
        this.analysisResults = new HashMap<>();
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(Instant periodStart) {
        this.periodStart = periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(Instant periodEnd) {
        this.periodEnd = periodEnd;
    }

    public int getTotalLogs() {
        return totalLogs;
    }

    public void setTotalLogs(int totalLogs) {
        this.totalLogs = totalLogs;
    }

    public int getErrorLogs() {
        return errorLogs;
    }

    public void setErrorLogs(int errorLogs) {
        this.errorLogs = errorLogs;
    }

    public Map<LogLevel, Long> getLevelDistribution() {
        return levelDistribution;
    }

    public void setLevelDistribution(Map<LogLevel, Long> levelDistribution) {
        this.levelDistribution = levelDistribution;
    }

    public List<ErrorCluster> getClusters() {
        return clusters;
    }

    public void setClusters(List<ErrorCluster> clusters) {
        this.clusters = clusters;
    }

    public Map<String, AnalysisResult> getAnalysisResults() {
        return analysisResults;
    }

    public void setAnalysisResults(Map<String, AnalysisResult> analysisResults) {
        this.analysisResults = analysisResults;
    }

    public String getOverallSummary() {
        return overallSummary;
    }

    public void setOverallSummary(String overallSummary) {
        this.overallSummary = overallSummary;
    }

    // Computed properties

    public int getClusterCount() {
        return clusters != null ? clusters.size() : 0;
    }

    public long getCriticalCount() {
        if (clusters == null) return 0;
        return clusters.stream()
                .filter(c -> c.getSeverity() == ErrorCluster.ClusterSeverity.CRITICAL)
                .count();
    }

    public long getHighCount() {
        if (clusters == null) return 0;
        return clusters.stream()
                .filter(c -> c.getSeverity() == ErrorCluster.ClusterSeverity.HIGH)
                .count();
    }

    public double getErrorRate() {
        if (totalLogs == 0) return 0;
        return (double) errorLogs / totalLogs * 100;
    }

    public boolean hasAnalysis() {
        return analysisResults != null && !analysisResults.isEmpty();
    }

    public AnalysisResult getAnalysisFor(String clusterId) {
        return analysisResults != null ? analysisResults.get(clusterId) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ReportData data = new ReportData();

        public Builder periodStart(Instant start) {
            data.setPeriodStart(start);
            return this;
        }

        public Builder periodEnd(Instant end) {
            data.setPeriodEnd(end);
            return this;
        }

        public Builder totalLogs(int count) {
            data.setTotalLogs(count);
            return this;
        }

        public Builder errorLogs(int count) {
            data.setErrorLogs(count);
            return this;
        }

        public Builder levelDistribution(Map<LogLevel, Long> distribution) {
            data.setLevelDistribution(distribution);
            return this;
        }

        public Builder clusters(List<ErrorCluster> clusters) {
            data.setClusters(clusters);
            return this;
        }

        public Builder analysisResults(Map<String, AnalysisResult> results) {
            data.setAnalysisResults(results);
            return this;
        }

        public Builder overallSummary(String summary) {
            data.setOverallSummary(summary);
            return this;
        }

        public ReportData build() {
            return data;
        }
    }
}

