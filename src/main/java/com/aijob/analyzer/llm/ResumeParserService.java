package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.validation.JsonContractParser;
import com.aijob.analyzer.llm.validation.SafeJsonRepair;
import com.aijob.analyzer.profile.ProfileFactIdAssigner;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.validation.CandidateProfileDraftValidator;
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
public class ResumeParserService {

    private final ChatModelClient chatModelClient;
    private final JsonContractParser jsonParser;
    private final SafeJsonRepair jsonRepairer;
    private final CandidateProfileDraftValidator validator;
    private final ProfileFactIdAssigner factIdAssigner;

    @Value("classpath:prompts/resume-parser-v1.txt")
    private Resource resumeParserPromptResource;

    public ResumeParserService(
            ChatModelClient chatModelClient,
            JsonContractParser jsonParser,
            SafeJsonRepair jsonRepairer,
            CandidateProfileDraftValidator validator,
            ProfileFactIdAssigner factIdAssigner) {
        this.chatModelClient = chatModelClient;
        this.jsonParser = jsonParser;
        this.jsonRepairer = jsonRepairer;
        this.validator = validator;
        this.factIdAssigner = factIdAssigner;
    }

    public CandidateProfile parse(
            RawResume rawResume,
            ProfileArtifactIdentity identity,
            LlmExecutionContext executionContext,
            TraceContext trace,
            TraceRecorder recorder) {

        String systemPrompt;
        try {
            systemPrompt = StreamUtils.copyToString(resumeParserPromptResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resume parser prompt", e);
        }

        return parseWithPrompt(rawResume, identity, executionContext, trace, recorder, systemPrompt);
    }

    public CandidateProfile parseWithPrompt(RawResume rawResume, ProfileArtifactIdentity identity,
            LlmExecutionContext executionContext, TraceContext trace, TraceRecorder recorder, String systemPrompt) {
        ChatModelRequest request = new ChatModelRequest(
                executionContext.semanticSnapshot(),
                systemPrompt,
                rawResume.rawText()
        );

        ChatModelResponse response = chatModelClient.generate(request);

        String repairedJson = jsonRepairer.repair(response.rawContent())
                .map(repaired -> {
                    if (recorder != null && trace != null) {
                        recorder.event(trace, TraceEventType.REPAIR_APPLIED, Map.of(
                                "parser", "RESUME",
                                "originalLength", response.rawContent().length(),
                                "repairedLength", repaired.length()
                        ));
                    }
                    return repaired;
                })
                .orElse(response.rawContent());

        CandidateProfileDraft draft = jsonParser.parse(repairedJson, CandidateProfileDraft.class);
        draft = new CandidateProfileDraft(
                draft.structuredFacts().stream().map(f -> new StructuredFactDraft(f.factType(), f.normalizedValue(), restoreQuote(f.source(), rawResume.rawText()))).toList(),
                draft.retrievalFacts().stream().map(f -> new RetrievalFactDraft(f.factType(), f.normalizedText(), restoreQuote(f.source(), rawResume.rawText()))).toList(),
                draft.contextOnly().stream().map(f -> new ContextOnlyDraft(f.category(), f.text(), restoreQuote(f.source(), rawResume.rawText()))).toList());

        validator.validate(draft, rawResume);

        List<ProfileFact> retrievalFacts = factIdAssigner.assignIds(draft.retrievalFacts(), identity.profileVersion());

        List<StructuredFact> structuredFacts = draft.structuredFacts().stream()
                .map(d -> new StructuredFact(d.factType(), d.normalizedValue(), d.source()))
                .collect(Collectors.toList());

        List<ContextOnlyFact> contextOnlyFacts = draft.contextOnly().stream()
                .map(d -> new ContextOnlyFact(d.category(), d.text(), d.source()))
                .collect(Collectors.toList());

        return new CandidateProfile(
                identity.profileVersion(),
                identity,
                rawResume.resumeFingerprint(),
                executionContext.semanticSnapshot() != null ? executionContext.semanticSnapshot().modelIdentity() : "unknown",
                ProfileStatus.READY,
                structuredFacts,
                retrievalFacts,
                contextOnlyFacts
        );
    }

    public CandidateProfile parse(RawResume rawResume, ProfileArtifactIdentity identity,
            LlmExecutionContext executionContext) {
        return parse(rawResume, identity, executionContext, null, null);
    }

    private SourceReference restoreQuote(SourceReference source, String original) {
        if (source == null || source.sourceText() == null || original.contains(source.sourceText())) return source;
        String restored = source.sourceText().replace("\\r\\n", "\r\n").replace("\\n", "\n").replace("\\r", "\r");
        if (!original.contains(restored)) {
            String windowsLines = restored.replace("\r\n", "\n").replace("\n", "\r\n");
            if (original.contains(windowsLines)) restored = windowsLines;
        }
        if (!original.contains(restored)) {
            StringBuilder compact = new StringBuilder();
            java.util.List<Integer> positions = new java.util.ArrayList<>();
            for (int i = 0; i < original.length(); i++) {
                if (!Character.isWhitespace(original.charAt(i))) { compact.append(original.charAt(i)); positions.add(i); }
            }
            String needle = restored.chars().filter(c -> !Character.isWhitespace(c)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            int start = needle.isEmpty() ? -1 : compact.indexOf(needle);
            if (start >= 0 && compact.indexOf(needle, start + 1) < 0) {
                restored = original.substring(positions.get(start), positions.get(start + needle.length() - 1) + 1);
            }
        }
        return original.contains(restored) ? new SourceReference(source.sourceId(), source.sourceTitle(), source.sourceSection(), restored) : source;
    }
}
