package com.aijob.analyzer.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Records usage metadata only. Audit storage failure never changes a provider response. */
@Component @org.springframework.context.annotation.Profile("!test")
public class ProviderCallLog {
    private final JdbcTemplate jdbc;
    public ProviderCallLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public String start(String role, String model, int characters) {
        String id = UUID.randomUUID().toString();
        try { jdbc.update("INSERT INTO demo_model_call(id,role,model_id,input_characters,status) VALUES (?,?,?,?,'STARTED')", id, role, model, characters); }
        catch (RuntimeException ignored) { return null; }
        return id;
    }
    public void finish(String id, String status, Integer input, Integer output, String finish) {
        if (id == null) return;
        try { jdbc.update("UPDATE demo_model_call SET status=?,input_tokens=?,output_tokens=?,finish_reason=? WHERE id=?", status, input, output, finish, id); }
        catch (RuntimeException ignored) { /* No prompts, response text or credentials in audit logs. */ }
    }
}
