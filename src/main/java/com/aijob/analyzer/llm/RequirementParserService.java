package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.validation.JsonContractParser;
import com.aijob.analyzer.llm.validation.SafeJsonRepair;
import com.aijob.analyzer.requirement.RequirementIdAssigner;
import com.aijob.analyzer.requirement.RequirementNormalizer;
import com.aijob.analyzer.requirement.domain.*;
import com.aijob.analyzer.requirement.validation.JobRequirementDraftValidator;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RequirementParserService {

    private final ChatModelClient chatModelClient;
    private final JsonContractParser jsonParser;
    private final SafeJsonRepair jsonRepairer;
    private final JobRequirementDraftValidator validator;
    private final RequirementIdAssigner idAssigner;
    private final RequirementNormalizer normalizer;

    @Value("classpath:prompts/${app.versions.requirement-parser.prompt:requirement-parser-v1}.txt")
    private Resource requirementParserPromptResource;

    public RequirementParserService(
            ChatModelClient chatModelClient,
            JsonContractParser jsonParser,
            SafeJsonRepair jsonRepairer,
            JobRequirementDraftValidator validator,
            RequirementIdAssigner idAssigner,
            RequirementNormalizer normalizer) {
        this.chatModelClient = chatModelClient;
        this.jsonParser = jsonParser;
        this.jsonRepairer = jsonRepairer;
        this.validator = validator;
        this.idAssigner = idAssigner;
        this.normalizer = normalizer;
    }

    public ValidatedRequirementPayload parseAndValidate(
            JobContext job,
            RequirementIdentity identity,
            LlmExecutionContext executionContext,
            TraceContext trace,
            TraceRecorder recorder) {

        String systemPrompt;
        try {
            systemPrompt = StreamUtils.copyToString(requirementParserPromptResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load requirement parser prompt", e);
        }

        ChatModelRequest request = new ChatModelRequest(
                executionContext.semanticSnapshot(),
                systemPrompt,
                job.analysisText() // Or full job summary
        );

        ChatModelResponse response = chatModelClient.generate(request);

        String repairedJson = jsonRepairer.repair(response.rawContent())
                .map(repaired -> {
                    if (recorder != null && trace != null) {
                        recorder.event(trace, TraceEventType.REPAIR_APPLIED, Map.of(
                                "parser", "REQUIREMENT",
                                "originalLength", response.rawContent().length(),
                                "repairedLength", repaired.length()
                        ));
                    }
                    return repaired;
                })
                .orElse(response.rawContent());

        JobRequirementDraft draft = jsonParser.parse(repairedJson, JobRequirementDraft.class);

        JobRequirementDraft normalizedDraft = normalizer.normalize(draft);

        validator.validate(normalizedDraft, job);

        List<AtomicRequirement> retrievalRequirements = idAssigner.assignIds(normalizedDraft.retrievalRequirements());

        List<StructuredConstraint> structuredConstraints = normalizedDraft.structuredConstraints().stream()
                .map(d -> new StructuredConstraint(d.type(), d.operator(), d.value(), d.sourceText()))
                .collect(Collectors.toList());

        return new ValidatedRequirementPayload(
                identity,
                structuredConstraints,
                retrievalRequirements,
                normalizedDraft.ignoredContext()
        );
    }

    public ValidatedRequirementPayload parseAndValidate(JobContext job, RequirementIdentity identity,
            LlmExecutionContext executionContext) {
        return parseAndValidate(job, identity, executionContext, null, null);
    }
}
