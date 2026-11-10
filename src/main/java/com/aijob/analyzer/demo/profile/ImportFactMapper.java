package com.aijob.analyzer.demo.profile;

import com.aijob.analyzer.demo.DemoException;
import com.aijob.analyzer.profile.ProfileFactIdAssigner;
import com.aijob.analyzer.profile.domain.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ImportFactMapper {
    private final ProfileFactIdAssigner assigner;
    public ImportFactMapper(ProfileFactIdAssigner assigner) { this.assigner = assigner; }
    public CandidateProfile build(CandidateProfile original, CandidateProfileDraft draft, String rawText, String importId, String title) {
        if (draft == null || draft.structuredFacts() == null || draft.retrievalFacts() == null || draft.contextOnly() == null
                || draft.retrievalFacts().isEmpty() || draft.retrievalFacts().size() > 64
                || draft.structuredFacts().size() > 64 || draft.contextOnly().size() > 64) {
            throw new DemoException(422, "FACTS_INVALID", "事实列表缺失、为空或超出允许数量");
        }
        var structured = draft.structuredFacts().stream().map(f -> {
            requireText(f.factType()); requireText(f.normalizedValue());
            return new StructuredFact(f.factType(), f.normalizedValue(), source(f.source(), rawText, importId, title));
        }).toList();
        var retrieval = draft.retrievalFacts().stream().map(f -> {
            requireText(f.factType()); requireText(f.normalizedText());
            return new RetrievalFactDraft(f.factType(), f.normalizedText(), source(f.source(), rawText, importId, title));
        }).toList();
        var context = draft.contextOnly().stream().map(f -> {
            requireText(f.category()); requireText(f.text());
            return new ContextOnlyFact(f.category(), f.text(), source(f.source(), rawText, importId, title));
        }).toList();
        return new CandidateProfile(original.artifactId(), original.identity(), original.resumeFingerprint(),
                original.parserDependencyFingerprint(), ProfileStatus.BUILDING, structured,
                assigner.assignIds(retrieval, original.artifactId()), context);
    }
    private SourceReference source(SourceReference source, String rawText, String id, String title) {
        if (source == null || source.sourceText() == null || source.sourceText().isBlank()
                || !rawText.contains(source.sourceText())) throw new DemoException(422, "FACT_SOURCE_INVALID", "每条事实都需要能在提取文本中核对的原文引用");
        return new SourceReference(id, title, source.sourceSection() == null ? "" : source.sourceSection(), source.sourceText());
    }
    private void requireText(String text) {
        if (text == null || text.isBlank() || text.length() > 8000) throw new DemoException(422, "FACTS_INVALID", "事实字段为空或过长");
    }
    public CandidateProfileDraft draft(CandidateProfile profile) {
        if (profile == null) return new CandidateProfileDraft(List.of(), List.of(), List.of());
        return new CandidateProfileDraft(
                profile.structuredFacts().stream().map(f -> new StructuredFactDraft(f.factType(), f.normalizedValue(), f.source())).toList(),
                profile.retrievalFacts().stream().map(f -> new RetrievalFactDraft(f.factType(), f.normalizedText(), f.source())).toList(),
                profile.contextOnly().stream().map(f -> new ContextOnlyDraft(f.category(), f.text(), f.source())).toList());
    }
}
