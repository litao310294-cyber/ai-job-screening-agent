package com.aijob.analyzer.demo;

import com.aijob.analyzer.analysis.AnalysisRun;
import com.aijob.analyzer.analysis.PreparedAnalysis;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.trace.TraceRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import static com.aijob.analyzer.demo.DemoModels.*;

@Repository @Profile("!test")
public class AnalysisOperationRepository {
    private final JdbcTemplate jdbc;
    private final DemoJson json;
    private final com.aijob.analyzer.rules.RuleConfigService rules;
    public AnalysisOperationRepository(JdbcTemplate jdbc, DemoJson json,com.aijob.analyzer.rules.RuleConfigService rules) { this.jdbc = jdbc; this.json = json; this.rules=rules; }
    public Operation findRequest(String requestId) {
        var ids = jdbc.query("SELECT id FROM demo_analysis_operation WHERE user_id=? AND client_request_id=?",
                (rs,n) -> rs.getString(1), USER_ID, requestId);
        return ids.isEmpty() ? null : get(ids.getFirst());
    }
    public String requestFingerprint(String id) {
        return jdbc.queryForObject("SELECT request_fingerprint FROM demo_analysis_operation WHERE id=?", String.class, id);
    }
    public void insert(String id, AnalysisInput input, String fingerprint, String taskId, String traceId, PreparedAnalysis prepared) {
        jdbc.update("INSERT INTO demo_analysis_operation(id,user_id,client_request_id,request_fingerprint,job_snapshot_id,task_id,trace_id,status,dependency_json,rules_json,rule_result_json) VALUES (?,?,?,?,?,?,?,'RUNNING',?,?,?)",
                id, USER_ID, input.clientRequestId(), fingerprint, input.jobSnapshotId(), taskId, traceId, json.write(prepared.snapshot()),
                json.write(prepared.rules()),json.write(prepared.ruleResult()));
    }
    public void finish(String id, AnalysisRun run) {
        int changed = jdbc.update("UPDATE demo_analysis_operation SET status=?,outcome_json=?,dependency_json=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RUNNING'",
                run.execution().status().name(), json.write(run), json.write(run.dependencySnapshot()), id);
        if (changed != 1) throw new IllegalStateException("Operation is no longer RUNNING");
    }
    public Operation get(String id) {
        String currentRule=rules.current().identity();
        var profiles=jdbc.query("SELECT profile_version FROM candidate_profile_current WHERE user_id=?",(rs,n)->rs.getLong(1),USER_ID);
        var rows = jdbc.query("SELECT * FROM demo_analysis_operation WHERE id=? AND user_id=?", (rs,n) -> {
            String outcome = rs.getString("outcome_json");
            AnalysisRun run = outcome == null ? null : json.read(outcome, AnalysisRun.class);
            var completed = rs.getTimestamp("completed_at");
            var dependencies=json.read(rs.getString("dependency_json"), AnalysisDependencySnapshot.class);
            return new Operation(rs.getString("id"), rs.getString("client_request_id"), rs.getString("job_snapshot_id"),
                    rs.getString("task_id"), rs.getString("trace_id"), rs.getString("status"),
                    dependencies,
                    run == null ? null : run.reuseSource(), run == null ? null : run.canonicalArtifactId(),
                    run == null ? null : run.execution(), run == null ? ("FAILED".equals(rs.getString("status")) ? "PROCESS_INTERRUPTED" : null) : run.errorCode(),
                    rs.getTimestamp("created_at").toInstant(), completed == null ? null : completed.toInstant(),
                    rs.getString("rules_json")==null?null:json.read(rs.getString("rules_json"),com.aijob.analyzer.rules.RuleModels.Snapshot.class),
                    rs.getString("rule_result_json")==null?null:json.read(rs.getString("rule_result_json"),com.aijob.analyzer.workflow.domain.RuleResult.class),
                    !currentRule.equals(dependencies.ruleVersion()) || profiles.isEmpty() || profiles.getFirst()!=dependencies.profileArtifactIdentity().profileVersion());
        }, id, USER_ID);
        if (rows.isEmpty()) throw new DemoException(404, "ANALYSIS_NOT_FOUND", "分析记录不存在");
        return rows.getFirst();
    }
    public List<Operation> recent() {
        return jdbc.query("SELECT id FROM demo_analysis_operation WHERE user_id=? ORDER BY created_at DESC,id LIMIT 50",
                (rs,n) -> rs.getString(1), USER_ID).stream().map(this::get).toList();
    }
    public List<TraceRecord> trace(String id) {
        var operation = get(id);
        return jdbc.query("SELECT record_json FROM demo_trace_record WHERE trace_id=? AND task_id=? ORDER BY sequence_number",
                (rs,n) -> json.read(rs.getString(1), TraceRecord.class), operation.traceId(), operation.taskId());
    }
    public void recoverInterrupted() {
        jdbc.update("UPDATE demo_analysis_operation SET status='FAILED',completed_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING'");
    }
}
