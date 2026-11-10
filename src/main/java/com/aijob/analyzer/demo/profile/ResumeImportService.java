package com.aijob.analyzer.demo.profile;

import com.aijob.analyzer.demo.*;
import com.aijob.analyzer.llm.*;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.retrieval.*;
import com.aijob.analyzer.trace.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;
import static com.aijob.analyzer.demo.DemoModels.USER_ID;
import static com.aijob.analyzer.demo.profile.ImportModels.*;

@Service @Profile("!test")
public class ResumeImportService {
    private final ResumeImportRepository imports;
    private final PdfResumeExtractor extractor;
    private final ResumeParserService parser;
    private final ImportFactMapper facts;
    private final ProfileProjectionService projections;
    private final TraceRecorder recorder;
    private final String providerMode;
    private final String prompt;
    private final String semantics;
    private final LlmSemanticSnapshot parserSnapshot;
    private final boolean migrationsEnabled;

    public ResumeImportService(ResumeImportRepository imports, PdfResumeExtractor extractor, ResumeParserService parser,
                               ImportFactMapper facts, ProfileProjectionService projections, TraceRecorder recorder,
                               DemoJson json, LlmSemanticVersions versions, RetrievalSemanticSnapshot retrieval,
                               @Value("${app.demo.provider-mode:REAL}") String mode, org.springframework.core.env.Environment environment) throws java.io.IOException {
        this.imports = imports;
        this.extractor = extractor;
        this.parser = parser;
        this.facts = facts;
        this.projections = projections;
        this.recorder = recorder;
        this.providerMode = mode;
        this.migrationsEnabled = environment.getProperty("spring.flyway.enabled", Boolean.class, true);
        if (!Set.of("REAL", "TEST").contains(mode)) throw new IllegalArgumentException("Unsupported provider mode");
        if (mode.equals("TEST") && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("phase2a-test"))) {
            throw new IllegalArgumentException("TEST imports require the explicit phase2a-test profile");
        }
        this.prompt = new ClassPathResource("prompts/resume-import-v2.txt").getContentAsString(StandardCharsets.UTF_8);
        this.parserSnapshot = new LlmSemanticSnapshot("resume-import-v2", versions.getResumeParserSnapshot().modelIdentity(),
                "candidate-profile-v1", new LlmGenerationConfigSnapshot(BigDecimal.ZERO, BigDecimal.ONE, 6000, "json_object"));
        this.semantics = json.fingerprint(Map.of("chatWireVersion", "openai-wire-v2", "pdf", "pdfbox-3.0.8", "promptSha256", sha256(prompt.getBytes(StandardCharsets.UTF_8)),
                "parser", parserSnapshot, "retrieval", retrieval, "index", "profile-projection-v2-quote-v1", "providerMode", mode));
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { if (migrationsEnabled) imports.recover(); }

    public View upload(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw new DemoException(413, "PDF_SIZE_INVALID", "PDF 文件需非空且不超过 10 MiB");
        String name = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 255 || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new DemoException(415, "PDF_TYPE_INVALID", "请选择 PDF 文件");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (java.io.IOException e) { throw new DemoException(422, "UPLOAD_READ_FAILED", "无法读取上传文件"); }
        var created = imports.create(name, sha256(bytes), semantics, "PDF", null, providerMode, bytes, null);
        if (created.fresh()) extract(created.view().id());
        return imports.get(created.view().id());
    }
    public View text(TextInput input) {
        if (input.originalImportId() != null) imports.get(input.originalImportId());
        String text = input.text().replace("\r\n", "\n").replace('\r', '\n').strip();
        return imports.create(input.fileName(), sha256(text.getBytes(StandardCharsets.UTF_8)), semantics, "USER_TEXT_REMEDIATION",
                input.originalImportId(), providerMode, null, text).view();
    }
    private void extract(String id) {
        try { imports.extracted(id, extractor.extract(imports.bytes(id))); }
        catch (DemoException e) { imports.failed(id, "EXTRACTING", "FAILED_EXTRACTION", e.code()); }
    }
    public View structure(String id) {
        View view = compatible(id);
        if (!Set.of("TEXT_READY", "FAILED_STRUCTURE").contains(view.status())) return view;
        if (!imports.claim(id, view.status(), "STRUCTURING")) return imports.get(id);
        try {
            CandidateProfile original = imports.allocate(view);
            var trace = new TraceContext(UUID.randomUUID().toString(), id);
            CandidateProfile parsed = parser.parseWithPrompt(new RawResume(USER_ID, id, view.fileName(), imports.text(id), view.contentSha256()),
                    original.identity(), new LlmExecutionContext(parserSnapshot, 1, 0), trace, recorder, prompt);
            imports.structured(id, facts.build(original, facts.draft(parsed), imports.text(id), id, view.fileName()));
        } catch (Exception e) {
            imports.diagnose(id, e);
            String code = e instanceof DemoException d ? d.code() : e instanceof com.aijob.analyzer.llm.validation.SchemaValidationError
                    ? "STRUCTURE_SCHEMA_INVALID" : e instanceof com.aijob.analyzer.llm.validation.GroundingError
                    ? "FACT_SOURCE_INVALID" : "STRUCTURE_FAILED";
            imports.failed(id, "STRUCTURING", "FAILED_STRUCTURE", code);
        }
        return imports.get(id);
    }
    public FactsView getFacts(String id) {
        var view = imports.get(id);
        return new FactsView(view, imports.text(id), facts.draft(imports.profile(view)));
    }
    public View edit(String id, FactsInput input) {
        var view = compatible(id);
        CandidateProfile original = imports.profile(view);
        if (original == null) throw new DemoException(409, "FACTS_NOT_READY", "请先完成结构化");
        var corrected = facts.build(original, input.facts(), imports.text(id), id, view.fileName());
        imports.edit(id, input.expectedRevision(), corrected);
        return imports.get(id);
    }
    public View confirm(String id, ConfirmInput input) {
        var view = compatible(id);
        if (view.revision() != input.expectedRevision()) throw new DemoException(409, "FACT_REVISION_CHANGED", "事实版本已变化");
        if (Set.of("READY", "INDEXING").contains(view.status())) return view;
        if (!Set.of("AWAITING_CONFIRMATION", "FAILED_INDEX").contains(view.status())) throw new DemoException(409, "FACTS_NOT_READY", "请先完成事实结构化与检查");
        if (!imports.confirm(id, input.expectedRevision())) return imports.get(id);
        try {
            CandidateProfile profile = imports.profile(view);
            if (projections.buildProjections(profile) != com.aijob.analyzer.retrieval.domain.ProjectionBuildResult.SUCCESS) {
                imports.failed(id, "INDEXING", "FAILED_INDEX", "INDEX_BUILD_FAILED");
            } else imports.publish(id, profile);
        } catch (RuntimeException e) { imports.failed(id, "INDEXING", "FAILED_INDEX", "INDEX_OR_PUBLISH_FAILED"); }
        return imports.get(id);
    }
    public View retry(String id) {
        var view = compatible(id);
        if (view.status().equals("FAILED_EXTRACTION")) {
            if (imports.claim(id, "FAILED_EXTRACTION", "EXTRACTING")) extract(id);
            return imports.get(id);
        }
        if (view.status().equals("FAILED_STRUCTURE")) return structure(id);
        if (view.status().equals("FAILED_INDEX")) return confirm(id, new ConfirmInput(view.revision(), true));
        return view;
    }
    private View compatible(String id) {
        var view = imports.get(id);
        if (!semantics.equals(view.semanticFingerprint())) throw new DemoException(409, "IMPORT_SEMANTICS_CHANGED", "处理版本已变化，请重新上传文件");
        return view;
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
