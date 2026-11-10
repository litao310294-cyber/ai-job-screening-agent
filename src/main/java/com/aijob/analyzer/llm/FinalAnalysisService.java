package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.llm.validation.JsonContractParser;
import com.aijob.analyzer.llm.validation.SafeJsonRepair;
import com.aijob.analyzer.profile.domain.StructuredFact;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.RuleResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class FinalAnalysisService {

    private final ChatModelClient chatModelClient;
    private final JsonContractParser jsonParser;
    private final SafeJsonRepair jsonRepairer;
    private final com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator validator;

    @Value("classpath:prompts/${app.versions.final-analysis.prompt:final-analysis-v1}.txt")
    private Resource finalAnalysisPromptResource;

    public FinalAnalysisService(
            ChatModelClient chatModelClient,
            JsonContractParser jsonParser,
            SafeJsonRepair jsonRepairer,
            com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator validator) {
        this.chatModelClient = chatModelClient;
        this.jsonParser = jsonParser;
        this.jsonRepairer = jsonRepairer;
        this.validator = validator;
    }

    public JobAnalysisResult generateStructured(
            JobContext job,
            RuleResult rule,
            RequirementSet requirementSet,
            List<StructuredFact> candidateStructuredFacts,
            RetrievalResult retrievalResult,
            LlmExecutionContext executionContext,
            TraceContext trace,
            TraceRecorder recorder) {

        String systemPrompt;
        try {
            systemPrompt = StreamUtils.copyToString(finalAnalysisPromptResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load final analysis prompt", e);
        }

        // Construct context: Job + Rule + Requirement + bound Evidence
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Job: ").append(job.analysisText()).append("\n");
        contextBuilder.append("Rule Results: ").append(rule.reasons()).append("\n");
        contextBuilder.append("Rule Assessments (user conditions and job requirements): ").append(rule.items()).append("\n");

        if (candidateStructuredFacts != null && !candidateStructuredFacts.isEmpty()) {
            contextBuilder.append("Candidate Structured Facts:\n");
            candidateStructuredFacts.forEach(fact ->
                contextBuilder.append("- ").append(fact.factType()).append(": ").append(fact.normalizedValue()).append("\n")
            );
        }

        contextBuilder.append("Requirements and Evidence:\n");

        requirementSet.retrievalRequirements().forEach(req -> {
            contextBuilder.append("- ").append(req.requirementId()).append(": ").append(req.normalizedText()).append("\n");
            var evidence = retrievalResult.evidenceByRequirement().get(req.requirementId());
            contextBuilder.append("  Allowed evidenceRefs for ").append(req.requirementId()).append(": ")
                    .append(evidence == null ? List.of() : evidence.stream().map(com.aijob.analyzer.retrieval.domain.Evidence::evidenceRef).toList()).append("\n");
            if (evidence != null) {
                evidence.forEach(ev -> {
                    contextBuilder.append("  * Evidence ").append(ev.evidenceRef()).append(": ").append(ev.content()).append("\n");
                });
            }
        });

        ChatModelRequest request = new ChatModelRequest(
                executionContext.semanticSnapshot(),
                systemPrompt,
                contextBuilder.toString()
        );

        ChatModelResponse response = chatModelClient.generate(request);

        String repairedJson = jsonRepairer.repair(response.rawContent())
                .map(repaired -> {
                    if (recorder != null && trace != null) {
                        recorder.event(trace, TraceEventType.REPAIR_APPLIED, Map.of(
                                "parser", "FINAL_ANALYSIS",
                                "originalLength", response.rawContent().length(),
                                "repairedLength", repaired.length()
                        ));
                    }
                    return repaired;
                })
                .orElse(response.rawContent());

        JobAnalysisResult result = jsonParser.parse(repairedJson, JobAnalysisResult.class);
        validator.validate(result, requirementSet, retrievalResult);

        if (recorder != null && trace != null) {
            recorder.event(trace, TraceEventType.TRANSITION, Map.of(
                    "stage", "LLM_ANALYSIS",
                    "decision", result.decision().name(),
                    "riskCount", result.risks().size(),
                    "strengthCount", result.strengths().size()
            ));
        }

        return result;
    }

    public JobAnalysisResult generateStructured(JobContext job, RuleResult rule,
            RequirementSet requirementSet, List<StructuredFact> candidateStructuredFacts,
            RetrievalResult retrievalResult, LlmExecutionContext executionContext) {
        return generateStructured(job, rule, requirementSet, candidateStructuredFacts,
                retrievalResult, executionContext, null, null);
    }
}
