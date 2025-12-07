package com.logai.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents the result of LLM analysis of an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisResult {

    private String clusterId;
    private String explanation;
    private String rootCause;
    private String recommendation;
    private String patch;
    private String patchFileName;
    private Confidence confidence;
    private String rawResponse;

    public AnalysisResult() {
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public String getPatch() {
        return patch;
    }

    public void setPatch(String patch) {
        this.patch = patch;
    }

    public String getPatchFileName() {
        return patchFileName;
    }

    public void setPatchFileName(String patchFileName) {
        this.patchFileName = patchFileName;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public void setConfidence(Confidence confidence) {
        this.confidence = confidence;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    /**
     * Check if a patch was generated.
     */
    public boolean hasPatch() {
        return patch != null && !patch.isEmpty();
    }

    @Override
    public String toString() {
        return "AnalysisResult{" +
                "clusterId='" + clusterId + '\'' +
                ", explanation='" + (explanation != null && explanation.length() > 50 
                        ? explanation.substring(0, 50) + "..." : explanation) + '\'' +
                ", hasPatch=" + hasPatch() +
                ", confidence=" + confidence +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN
    }

    public static class Builder {
        private final AnalysisResult result = new AnalysisResult();

        public Builder clusterId(String clusterId) {
            result.setClusterId(clusterId);
            return this;
        }

        public Builder explanation(String explanation) {
            result.setExplanation(explanation);
            return this;
        }

        public Builder rootCause(String rootCause) {
            result.setRootCause(rootCause);
            return this;
        }

        public Builder recommendation(String recommendation) {
            result.setRecommendation(recommendation);
            return this;
        }

        public Builder patch(String patch) {
            result.setPatch(patch);
            return this;
        }

        public Builder patchFileName(String patchFileName) {
            result.setPatchFileName(patchFileName);
            return this;
        }

        public Builder confidence(Confidence confidence) {
            result.setConfidence(confidence);
            return this;
        }

        public Builder rawResponse(String rawResponse) {
            result.setRawResponse(rawResponse);
            return this;
        }

        public AnalysisResult build() {
            return result;
        }
    }
}

