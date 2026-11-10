package com.aijob.analyzer.chat;

import com.aijob.analyzer.demo.*;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aijob.analyzer.chat.ChatModels.*;

@Service @Profile("!test")
public class ChatContextBuilder {
    public static final int MAX_INPUT_CHARACTERS = 24000;
    private final AnalysisOperationRepository analyses;
    private final JobSnapshotService jobs;
    private final CandidateProfileRepository profiles;
    private final ChatRepository chats;
    private final LlmSemanticVersions versions;
    private final DemoJson json;
    public ChatContextBuilder(AnalysisOperationRepository analyses, JobSnapshotService jobs, CandidateProfileRepository profiles,
                              ChatRepository chats, LlmSemanticVersions versions, DemoJson json) {
        this.analyses = analyses; this.jobs = jobs; this.profiles = profiles; this.chats = chats; this.versions = versions; this.json = json;
    }
    String scope(Input input) {
        return json.fingerprint(Map.of("job", input.jobSnapshotId(), "analysis", input.analysisId(), "comparisons", input.comparisonAnalysisIds()));
    }
    Frozen freeze(Input input, String scope) {
        if (input.comparisonAnalysisIds().contains(input.analysisId())) throw new DemoException(422, "CHAT_COMPARISON_INVALID", "比较分析不能重复当前分析");
        var ids = new ArrayList<String>(); ids.add(input.analysisId()); ids.addAll(input.comparisonAnalysisIds());
        var bindings = new ArrayList<Binding>();
        var sources = new ArrayList<Object>();
        var allowedRefs = new ArrayList<String>();
        var truncated = new int[]{0};
        for (String id : ids) {
            var analysis = analyses.get(id);
            if (id.equals(input.analysisId()) && !input.jobSnapshotId().equals(analysis.jobSnapshotId())) throw new DemoException(409, "CHAT_ANALYSIS_JOB_MISMATCH", "岗位与分析不对应，请重新选择");
            if (!"SUCCESS".equals(analysis.status()) || analysis.execution() == null) throw new DemoException(409, "CHAT_ANALYSIS_NOT_READY", "分析尚未成功，请查询状态或重新分析");
            if (analysis.stale()) throw new DemoException(409, "CHAT_ANALYSIS_STALE", "分析使用的画像或规则已过期，请重新分析后提问");
            var profileRead = profiles.find(analysis.dependencies().profileArtifactIdentity());
            var profile = profileRead.value();
            if (profile == null || profile.artifactId() != analysis.dependencies().profileArtifactId() || profile.status() != com.aijob.analyzer.profile.domain.ProfileStatus.READY) throw new DemoException(409, "CHAT_PROFILE_UNAVAILABLE", "分析所用画像版本不可用，请重新分析");
            var binding = new Binding(id, analysis.jobSnapshotId(), analysis.dependencies(), analysis.execution().resultOrigin().name());
            bindings.add(binding);
            var source = new LinkedHashMap<String,Object>();
            source.put("analysisId", id); source.put("jobSnapshotId", analysis.jobSnapshotId());
            source.put("profileArtifactId", profile.artifactId()); source.put("profileVersion", profile.identity().profileVersion());
            source.put("ruleIdentity", analysis.dependencies().ruleVersion()); source.put("resultOrigin", binding.resultOrigin());
            source.put("retrievalVersion", analysis.dependencies().retrievalSemanticFingerprint());
            source.put("analysisModelVersion", analysis.dependencies().finalLlmSemanticSnapshot());
            source.put("pageTextUntrusted", excerpt(jobs.context(jobs.get(analysis.jobSnapshotId())).analysisText(), 1800, truncated));
            source.put("userRuleDataExcerpt", excerpt(json.write(analysis.ruleResult()), 1800, truncated));
            source.put("savedAnalysisOrRuleOutcomeExcerpt", excerpt(json.write(analysis.execution()), 2600, truncated));
            var cited = new LinkedHashSet<String>();
            if (analysis.execution().result() != null) analysis.execution().result().requirementAssessments().forEach(a -> cited.addAll(a.evidenceRefs()));
            var ordered = new ArrayList<>(profile.retrievalFacts());
            ordered.sort(Comparator.comparing(f -> !cited.contains(f.factId())));
            var evidence = new ArrayList<Object>();
            for (var fact : ordered.stream().limit(6).toList()) {
                String ref = id + "/" + fact.factId(); allowedRefs.add(ref);
                evidence.add(Map.of("evidenceRef", ref, "factId", fact.factId(), "type", fact.factType(),
                        "userConfirmedFact", excerpt(fact.normalizedText(), 240, truncated),
                        "sourceId", excerpt(fact.source().sourceId(), 120, truncated),
                        "sourceSection", excerpt(fact.source().sourceSection(), 120, truncated),
                        "sourceQuote", excerpt(fact.source().sourceText(), 200, truncated)));
            }
            source.put("confirmedResumeEvidence", evidence); source.put("omittedFactCount", Math.max(0, ordered.size()-6));
            if (ordered.size() > 6) truncated[0]++;
            source.put("confirmedStructuredFactsExcerpt", excerpt(json.write(profile.structuredFacts()), 800, truncated));
            sources.add(source);
        }
        String system;
        try { system = new ClassPathResource("prompts/" + versions.getChatSnapshot().promptVersion() + ".txt").getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("Chat prompt unavailable", e); }
        var data = new LinkedHashMap<String,Object>();
        data.put("question", input.question()); data.put("explicitComparison", !input.comparisonAnalysisIds().isEmpty());
        data.put("sourceData", sources);
        var history = new ArrayList<Object>(); data.put("historyNotCandidateFacts", history);
        if (system.length() + json.write(data).length() > MAX_INPUT_CHARACTERS) throw new DemoException(422, "CHAT_CONTEXT_TOO_LARGE", "关联内容超过上下文预算，请缩小比较范围");
        var recent = chats.recent(input.expectedGeneration(), scope);
        int used = 0;
        for (var turn : recent.stream().limit(6).toList()) {
            int beforeExcerpt = truncated[0];
            var previous = Map.of("operationId", turn.id(), "priorUserText", excerpt(turn.input().question(), 600, truncated),
                    "priorModelAnswerNotFact", excerpt(turn.answer().answer(), 1400, truncated));
            history.addFirst(previous);
            if (system.length() + json.write(data).length() > MAX_INPUT_CHARACTERS) { history.removeFirst(); truncated[0] = beforeExcerpt; break; }
            used++;
        }
        String context = json.write(data);
        var policy = new Policy(system.length()+context.length(), used, chats.recentCount(input.expectedGeneration(), scope)-used,
                truncated[0], MAX_INPUT_CHARACTERS, 6, "bounded recent completed turns; oldest omitted first; explicit source excerpts; no summaries");
        return new Frozen(system, context, versions.getChatSnapshot(), bindings, allowedRefs, policy);
    }
    private String excerpt(String value, int max, int[] truncated) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        truncated[0]++;
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end) + " [TRUNCATED]";
    }
}
