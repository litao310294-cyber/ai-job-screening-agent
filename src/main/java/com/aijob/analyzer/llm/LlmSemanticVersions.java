package com.aijob.analyzer.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.versions")
public class LlmSemanticVersions {

    private NodeVersions resumeParser;
    private NodeVersions requirementParser;
    private NodeVersions finalAnalysis;
    private NodeVersions chat;
    public NodeVersions getChat() { return chat; }
    public void setChat(NodeVersions chat) { this.chat = chat; }
    public LlmSemanticSnapshot getChatSnapshot() { return chat.toSnapshot(); }

    public static class NodeVersions {
        private String prompt;
        private String model;
        private String schema;
        private java.math.BigDecimal temperature;
        private java.math.BigDecimal topP;
        private Integer maxOutputTokens;
        private String responseFormat;

        // Getters and Setters
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public java.math.BigDecimal getTemperature() { return temperature; }
        public void setTemperature(java.math.BigDecimal temperature) { this.temperature = temperature; }
        public java.math.BigDecimal getTopP() { return topP; }
        public void setTopP(java.math.BigDecimal topP) { this.topP = topP; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public String getResponseFormat() { return responseFormat; }
        public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }

        public LlmSemanticSnapshot toSnapshot() {
            return new LlmSemanticSnapshot(
                    prompt,
                    model,
                    schema,
                    new LlmGenerationConfigSnapshot(temperature, topP, maxOutputTokens, responseFormat)
            );
        }
    }

    public NodeVersions getResumeParser() { return resumeParser; }
    public void setResumeParser(NodeVersions resumeParser) { this.resumeParser = resumeParser; }
    public NodeVersions getRequirementParser() { return requirementParser; }
    public void setRequirementParser(NodeVersions requirementParser) { this.requirementParser = requirementParser; }
    public NodeVersions getFinalAnalysis() { return finalAnalysis; }
    public void setFinalAnalysis(NodeVersions finalAnalysis) { this.finalAnalysis = finalAnalysis; }

    public LlmSemanticSnapshot getResumeParserSnapshot() {
        return resumeParser.toSnapshot();
    }

    public LlmSemanticSnapshot getRequirementParserSnapshot() {
        return requirementParser.toSnapshot();
    }

    public LlmSemanticSnapshot getFinalAnalysisSnapshot() {
        return finalAnalysis.toSnapshot();
    }
}
