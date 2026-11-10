package com.aijob.analyzer.demo;

import com.aijob.analyzer.analysis.*;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.workflow.domain.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.aijob.analyzer.demo.DemoModels.*;

@Service @Profile("!test")
public class AnalysisHttpService {
    private final AnalysisApplicationService core;
    private final JobSnapshotService jobs;
    private final AnalysisOperationRepository operations;
    private final DemoJson json;
    private final boolean migrationsEnabled;
    public AnalysisHttpService(AnalysisApplicationService core, JobSnapshotService jobs, AnalysisOperationRepository operations,
                               DemoJson json, @Value("${spring.flyway.enabled:true}") boolean migrationsEnabled) {
        this.core = core; this.jobs = jobs; this.operations = operations; this.json = json;
        this.migrationsEnabled = migrationsEnabled;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { if (migrationsEnabled) operations.recoverInterrupted(); }

    public Operation submit(AnalysisInput input) {
        String fingerprint = input.expectedRuleVersion()==null
                ? json.fingerprint(new LegacyRequest(input.clientRequestId(),input.jobSnapshotId(),input.expectedProfileVersion()))
                : json.fingerprint(input);
        var existing = operations.findRequest(input.clientRequestId());
        if (existing != null) return replay(existing, fingerprint);
        var job = jobs.get(input.jobSnapshotId());
        PreparedAnalysis prepared;
        try { prepared = core.prepare(USER_ID, jobs.context(job)); }
        catch (AnalysisApplicationService.ProfileNotReadyException e) {
            throw new DemoException(409, "PROFILE_NOT_READY", "请先准备并确认可用画像");
        }
        if (input.expectedProfileVersion() != null && input.expectedProfileVersion() != prepared.profile().identity().profileVersion()) {
            throw new DemoException(409, "PROFILE_VERSION_CHANGED", "当前画像版本已变化，请刷新后重新提交");
        }
        if(input.expectedRuleVersion()!=null && (prepared.rules()==null || input.expectedRuleVersion()!=prepared.rules().version())) {
            throw new DemoException(409,"RULE_VERSION_CHANGED","当前规则版本已变化，请刷新后重新提交");
        }
        String id = UUID.randomUUID().toString();
        var trace = new TraceContext(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try { operations.insert(id, input, fingerprint, trace.taskId(), trace.traceId(), prepared); }
        catch (DuplicateKeyException e) { return replay(operations.findRequest(input.clientRequestId()), fingerprint); }
        AnalysisRun run;
        try {
            var result = core.execute(prepared, trace);
            // Provider text and exception messages do not enter the public error surface.
            var execution = result.execution();
            run = new AnalysisRun(new AnalysisExecution(execution.status(), execution.resultOrigin(), execution.result(), result.errorCode()),
                    result.reuseSource(), result.canonicalArtifactId(), result.dependencySnapshot(), result.errorCode());
        } catch (RuntimeException e) {
            run = new AnalysisRun(new AnalysisExecution(WorkflowStatus.FAILED, null, null, "ANALYSIS_EXECUTION_FAILED"),
                    "UNRESOLVED", null, prepared.snapshot(), "ANALYSIS_EXECUTION_FAILED");
        }
        operations.finish(id, run);
        return operations.get(id);
    }
    private Operation replay(Operation operation, String fingerprint) {
        if (operation == null) throw new IllegalStateException("Request conflict without persisted operation");
        if (!fingerprint.equals(operations.requestFingerprint(operation.id()))) {
            throw new DemoException(409, "IDEMPOTENCY_CONFLICT", "同一请求标识已用于不同内容");
        }
        return operation;
    }
    private record LegacyRequest(String clientRequestId,String jobSnapshotId,Long expectedProfileVersion) {}
}
