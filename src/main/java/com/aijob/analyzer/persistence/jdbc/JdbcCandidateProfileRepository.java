package com.aijob.analyzer.persistence.jdbc;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!test")
public class JdbcCandidateProfileRepository implements CandidateProfileRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public JdbcCandidateProfileRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override @Transactional
    public ProfileArtifactRef createBuilding(String userId, String resumeFingerprint, String parserDependencyFingerprint) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> { PreparedStatement ps = connection.prepareStatement("INSERT INTO candidate_profile_artifact(user_id,profile_version,status,resume_fingerprint,parser_dependency_fingerprint,structured_facts_json,context_only_json) VALUES (?,0,'BUILDING',?,?, '[]','[]')", Statement.RETURN_GENERATED_KEYS); ps.setString(1,userId); ps.setString(2,resumeFingerprint); ps.setString(3,parserDependencyFingerprint); return ps; }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("UPDATE candidate_profile_artifact SET profile_version=? WHERE id=?", id, id);
        return new ProfileArtifactRef(id, new ProfileArtifactIdentity(userId, id));
    }
    @Override @Transactional
    public void saveValidatedProfile(CandidateProfile profile) {
        jdbc.update("UPDATE candidate_profile_artifact SET structured_facts_json=?,context_only_json=? WHERE id=?", write(profile.structuredFacts()), write(profile.contextOnly()), profile.artifactId());
        jdbc.update("DELETE FROM profile_fact WHERE profile_artifact_id=?", profile.artifactId());
        for (ProfileFact fact : profile.retrievalFacts()) jdbc.update("INSERT INTO profile_fact(profile_artifact_id,fact_id,fact_type,normalized_text,source_id,source_title,source_section,source_text) VALUES (?,?,?,?,?,?,?,?)", profile.artifactId(),fact.factId(),fact.factType(),fact.normalizedText(),fact.source().sourceId(),fact.source().sourceTitle(),fact.source().sourceSection(),fact.source().sourceText());
    }
    @Override public DbReadResult<CandidateProfile> find(ProfileArtifactIdentity identity) {
        try {
            List<CandidateProfile> rows=jdbc.query("SELECT * FROM candidate_profile_artifact WHERE user_id=? AND profile_version=?", (rs,row)->hydrate(rs.getLong("id"),identity,rs.getString("resume_fingerprint"),rs.getString("parser_dependency_fingerprint"),rs.getString("status"),rs.getString("structured_facts_json"),rs.getString("context_only_json")),identity.userId(),identity.profileVersion());
            return rows.isEmpty()?new DbReadResult<>(DbReadOutcome.NOT_FOUND,null,null):new DbReadResult<>(DbReadOutcome.FOUND,rows.getFirst(),null);
        } catch(RuntimeException failure){return new DbReadResult<>(DbReadOutcome.READ_FAILED,null,"PROFILE_READ_FAILED");}
    }
    @Override public Optional<ProfileArtifactIdentity> findCurrentReady(String userId) {
        List<Long> versions=jdbc.query("SELECT profile_version FROM candidate_profile_current WHERE user_id=?",(rs,row)->rs.getLong(1),userId); return versions.isEmpty()?Optional.empty():Optional.of(new ProfileArtifactIdentity(userId,versions.getFirst()));
    }
    @Override @Transactional
    public ProfilePublishOutcome markReadyAndPublishCurrent(ProfileArtifactIdentity identity) {
        jdbc.update("UPDATE candidate_profile_artifact SET status='READY',ready_at=CURRENT_TIMESTAMP WHERE user_id=? AND profile_version=?",identity.userId(),identity.profileVersion());
        List<Long> versions=jdbc.query("SELECT profile_version FROM candidate_profile_current WHERE user_id=? FOR UPDATE",(rs,row)->rs.getLong(1),identity.userId());
        if(versions.isEmpty()){jdbc.update("INSERT INTO candidate_profile_current(user_id,profile_artifact_id,profile_version) SELECT user_id,id,profile_version FROM candidate_profile_artifact WHERE user_id=? AND profile_version=?",identity.userId(),identity.profileVersion()); return ProfilePublishOutcome.PUBLISHED_CURRENT;}
        if(identity.profileVersion()>versions.getFirst()){jdbc.update("UPDATE candidate_profile_current SET profile_artifact_id=(SELECT id FROM candidate_profile_artifact WHERE user_id=? AND profile_version=?),profile_version=? WHERE user_id=?",identity.userId(),identity.profileVersion(),identity.profileVersion(),identity.userId()); return ProfilePublishOutcome.PUBLISHED_CURRENT;}
        return ProfilePublishOutcome.READY_NOT_CURRENT;
    }
    @Override public void markFailed(ProfileArtifactIdentity identity,String reason){jdbc.update("UPDATE candidate_profile_artifact SET status='FAILED' WHERE user_id=? AND profile_version=?",identity.userId(),identity.profileVersion());}
    private CandidateProfile hydrate(long id,ProfileArtifactIdentity identity,String resume,String parser,String status,String structured,String context){List<ProfileFact> facts=jdbc.query("SELECT * FROM profile_fact WHERE profile_artifact_id=? ORDER BY fact_id",(rs,row)->new ProfileFact(rs.getString("fact_id"),id,rs.getString("fact_type"),rs.getString("normalized_text"),new SourceReference(rs.getString("source_id"),rs.getString("source_title"),rs.getString("source_section"),rs.getString("source_text"))),id);return new CandidateProfile(id,identity,resume,parser,ProfileStatus.valueOf(status),read(structured,new TypeReference<List<StructuredFact>>(){}),facts,read(context,new TypeReference<List<ContextOnlyFact>>(){}));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T>T read(String value,TypeReference<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
}
