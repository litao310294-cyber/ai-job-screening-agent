package com.aijob.analyzer.demo.profile;

import com.aijob.analyzer.demo.DemoException;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aijob.analyzer.demo.DemoModels.USER_ID;
import static com.aijob.analyzer.demo.profile.ImportModels.*;

@Repository @Profile("!test")
public class ResumeImportRepository {
    private final JdbcTemplate jdbc;
    private final CandidateProfileRepository profiles;
    private final TransactionTemplate transaction;
    public ResumeImportRepository(JdbcTemplate jdbc, CandidateProfileRepository profiles, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.transaction = new TransactionTemplate(manager);
    }
    public record Created(View view, boolean fresh) {}
    public Created create(String name, String hash, String semantics, String kind, String parent, String mode, byte[] bytes, String text) {
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO demo_resume_import(id,user_id,file_name,content_sha256,semantic_fingerprint,source_kind,original_import_id,provider_mode,status,file_bytes,raw_text) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id, USER_ID, name, hash, semantics, kind, parent, mode, text == null ? "EXTRACTING" : "TEXT_READY", bytes, text);
            return new Created(get(id), true);
        } catch (DuplicateKeyException e) {
            String existing = jdbc.queryForObject("SELECT id FROM demo_resume_import WHERE user_id=? AND content_sha256=? AND semantic_fingerprint=? AND source_kind=?",
                    String.class, USER_ID, hash, semantics, kind);
            return new Created(get(existing), false);
        }
    }
    public View get(String id) {
        var rows = jdbc.query("SELECT i.id,i.file_name,i.content_sha256,i.semantic_fingerprint,i.source_kind,i.original_import_id,i.provider_mode,i.status,i.revision,i.profile_artifact_id,i.page_count,CHAR_LENGTH(i.raw_text) AS text_length,i.error_code,i.created_at,i.confirmed_at,EXISTS(SELECT 1 FROM candidate_profile_current c WHERE c.user_id=i.user_id AND c.profile_artifact_id=i.profile_artifact_id) AS is_current FROM demo_resume_import i WHERE i.id=? AND i.user_id=?",
                (rs,n) -> new View(rs.getString("id"), rs.getString("file_name"), rs.getString("content_sha256"), rs.getString("semantic_fingerprint"),
                        rs.getString("source_kind"), rs.getString("original_import_id"), rs.getString("provider_mode"), rs.getString("status"),
                        rs.getLong("revision"), rs.getObject("profile_artifact_id", Long.class), rs.getObject("page_count", Integer.class),
                        rs.getInt("text_length"), rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(), rs.getBoolean("is_current")), id, USER_ID);
        if (rows.isEmpty()) throw new DemoException(404, "IMPORT_NOT_FOUND", "导入记录不存在");
        return rows.getFirst();
    }
    public String text(String id) { get(id); return jdbc.queryForObject("SELECT raw_text FROM demo_resume_import WHERE id=?", String.class, id); }
    public byte[] bytes(String id) { get(id); return jdbc.queryForObject("SELECT file_bytes FROM demo_resume_import WHERE id=?", byte[].class, id); }
    public void extracted(String id, PdfResumeExtractor.Extracted extracted) {
        jdbc.update("UPDATE demo_resume_import SET raw_text=?,page_count=?,status='TEXT_READY',error_code=NULL WHERE id=? AND status='EXTRACTING'",
                extracted.text(), extracted.pages(), id);
    }
    public boolean claim(String id, String from, String to) {
        return jdbc.update("UPDATE demo_resume_import SET status=?,error_code=NULL WHERE id=? AND status=?", to, id, from) == 1;
    }
    public void failed(String id, String from, String failure, String code) {
        jdbc.update("UPDATE demo_resume_import SET status=?,error_code=? WHERE id=? AND status=?", failure, code, id, from);
    }
    public CandidateProfile allocate(View view) {
        return transaction.execute(tx -> {
            lockUser();
            var latest = get(view.id());
            if (latest.profileArtifactId() == null) {
                var ref = profiles.createBuilding(USER_ID, latest.contentSha256(), latest.semanticFingerprint());
                jdbc.update("UPDATE demo_resume_import SET profile_artifact_id=? WHERE id=?", ref.artifactId(), latest.id());
                return profile(get(latest.id()));
            }
            return profile(latest);
        });
    }
    public CandidateProfile profile(View view) {
        if (view.profileArtifactId() == null) return null;
        var result = profiles.find(new ProfileArtifactIdentity(USER_ID, view.profileArtifactId()));
        if (result.outcome() != DbReadOutcome.FOUND) throw new DemoException(503, "PROFILE_READ_FAILED", "画像读取失败");
        return result.value();
    }
    public void structured(String id, CandidateProfile profile) {
        transaction.executeWithoutResult(tx -> {
            lockImport(id);
            if (!get(id).status().equals("STRUCTURING")) throw new DemoException(409, "IMPORT_STATE_CHANGED", "导入状态已变化");
            profiles.saveValidatedProfile(profile);
            jdbc.update("UPDATE demo_resume_import SET status='AWAITING_CONFIRMATION',revision=revision+1,error_code=NULL WHERE id=?", id);
        });
    }
    public void edit(String id, long revision, CandidateProfile profile) {
        transaction.executeWithoutResult(tx -> {
            lockImport(id);
            var current = get(id);
            if (current.revision() != revision) throw new DemoException(409, "FACT_REVISION_CHANGED", "事实版本已变化");
            if (!Set.of("AWAITING_CONFIRMATION", "FAILED_INDEX").contains(current.status())) throw new DemoException(409, "IMPORT_NOT_EDITABLE", "当前阶段不能修改事实");
            profiles.saveValidatedProfile(profile);
            jdbc.update("UPDATE demo_resume_import SET status='AWAITING_CONFIRMATION',revision=revision+1,confirmed_revision=NULL,confirmed_at=NULL,error_code=NULL WHERE id=?", id);
        });
    }
    public boolean confirm(String id, long revision) {
        return jdbc.update("UPDATE demo_resume_import SET status='INDEXING',confirmed_revision=revision,confirmed_at=CURRENT_TIMESTAMP(6),error_code=NULL WHERE id=? AND revision=? AND status IN ('AWAITING_CONFIRMATION','FAILED_INDEX')",
                id, revision) == 1;
    }
    public void publish(String id, CandidateProfile profile) {
        transaction.executeWithoutResult(tx -> {
            lockUser();
            lockImport(id);
            if (!get(id).status().equals("INDEXING")) throw new DemoException(409, "IMPORT_STATE_CHANGED", "导入状态已变化");
            profiles.markReadyAndPublishCurrent(profile.identity());
            jdbc.update("UPDATE demo_resume_import SET status='READY',error_code=NULL WHERE id=? AND confirmed_revision=revision", id);
        });
    }
    public View forProfile(long artifact) {
        var ids = jdbc.query("SELECT id FROM demo_resume_import WHERE user_id=? AND profile_artifact_id=? AND status='READY'", (rs,n) -> rs.getString(1), USER_ID, artifact);
        return ids.isEmpty() ? null : get(ids.getFirst());
    }
    public void recover() {
        jdbc.update("UPDATE demo_resume_import SET error_code='PROCESS_INTERRUPTED',status=CASE status WHEN 'EXTRACTING' THEN 'FAILED_EXTRACTION' WHEN 'STRUCTURING' THEN 'FAILED_STRUCTURE' WHEN 'INDEXING' THEN 'FAILED_INDEX' END WHERE status IN ('EXTRACTING','STRUCTURING','INDEXING')");
    }
    public void diagnose(String id, Exception failure) {
        String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        jdbc.update("UPDATE demo_resume_import SET private_error_detail=? WHERE id=?", detail.substring(0, Math.min(8000, detail.length())), id);
    }
    private void lockUser() { jdbc.queryForList("SELECT user_id FROM demo_profile_guard WHERE user_id=? FOR UPDATE", USER_ID); }
    private void lockImport(String id) { jdbc.queryForList("SELECT id FROM demo_resume_import WHERE id=? AND user_id=? FOR UPDATE", id, USER_ID); }
}
