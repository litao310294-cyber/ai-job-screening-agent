package com.aijob.analyzer.demo;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.aijob.analyzer.demo.DemoModels.*;

@Service @Profile("!test")
public class JobSnapshotService {
    private final JdbcTemplate jdbc;
    private final DemoJson json;
    public JobSnapshotService(JdbcTemplate jdbc, DemoJson json) { this.jdbc = jdbc; this.json = json; }
    public JobSnapshot save(JobInput input) {
        var pay=input.compensation();
        if(pay!=null && (pay.min()==null || pay.max()==null || pay.min().signum()<0 || pay.min().compareTo(pay.max())>0
                || !java.util.Set.of("DAY","MONTH").contains(String.valueOf(pay.unit())))) {
            throw new DemoException(422,"INVALID_SALARY","结构化薪资需合法区间及 DAY/MONTH 单位");
        }
        var normalized = new JobInput(clean(input.sourcePlatform()), clean(input.sourceJobId()), clean(input.sourceUrl()),
                clean(input.title()), clean(input.company()), clean(input.city()), clean(input.location()),
                clean(input.salary()), clean(input.education()), clean(input.experience()),
                input.skills() == null ? List.of() : input.skills().stream().map(JobSnapshotService::clean).distinct().sorted().toList(),
                input.internshipDays(), clean(input.duration()), input.description(), input.simulated(),
                clean(input.captureMethod()), input.capturedAt() == null ? Instant.now() : input.capturedAt(),
                clean(input.direction()),input.internshipMonths(),input.compensation());
        // Capture metadata is stored, while semantic identity contains all normalized job fields.
        var semantic = new JobInput(normalized.sourcePlatform(), normalized.sourceJobId(), normalized.sourceUrl(),
                normalized.title(), normalized.company(), normalized.city(), normalized.location(), normalized.salary(),
                normalized.education(), normalized.experience(), normalized.skills(), normalized.internshipDays(),
                normalized.duration(), clean(normalized.description()), normalized.simulated(), null, null,
                normalized.direction(),normalized.internshipMonths(),normalized.compensation());
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO demo_job_snapshot(id,fingerprint,job_json) VALUES (?,?,?)", id,
                json.fingerprint(semantic), json.write(normalized));
        return get(id);
    }
    public JobSnapshot get(String id) {
        var rows = jdbc.query("SELECT * FROM demo_job_snapshot WHERE id=?", (rs,n) ->
                new JobSnapshot(rs.getString("id"), rs.getString("fingerprint"),
                        json.read(rs.getString("job_json"), JobInput.class), rs.getTimestamp("created_at").toInstant()), id);
        if (rows.isEmpty()) throw new DemoException(404, "JOB_NOT_FOUND", "岗位快照不存在");
        return rows.getFirst();
    }
    public List<JobSnapshot> recent() {
        return jdbc.query("SELECT id FROM demo_job_snapshot ORDER BY created_at DESC,id LIMIT 50", (rs,n) -> rs.getString(1))
                .stream().map(this::get).toList();
    }
    public JobContext context(JobSnapshot snapshot) {
        var job = snapshot.job();
        return new JobContext(job.sourcePlatform(), job.sourceJobId(), job.title(), job.city(), job.internshipDays(),
                job.duration(), job.salary(), job.description(), new JobFingerprint(json.fingerprint(java.util.Map.of("mappingVersion","job-context-2b-v1","snapshotFingerprint",snapshot.fingerprint()))),
                new com.aijob.analyzer.analysis.domain.JobDetails(job.company(),job.location(),job.education(),job.experience(),job.skills(),job.direction(),job.internshipMonths(),job.compensation()));
    }
    private static String clean(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace('\r', '\n').strip().replaceAll("[\\t ]+", " ");
    }
}
