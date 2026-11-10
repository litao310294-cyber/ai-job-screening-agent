package com.aijob.analyzer.chat;

import com.aijob.analyzer.demo.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static com.aijob.analyzer.chat.ChatModels.*;
import static com.aijob.analyzer.demo.DemoModels.USER_ID;

@Repository @Profile("!test")
public class ChatRepository {
    private final JdbcTemplate jdbc;
    private final DemoJson json;
    private final TransactionTemplate tx;
    record State(long generation, long nextSequence, String activeId) {}
    record Stored(String id, String clientId, long generation, long sequence, String status, int attempts,
                  String fingerprint, Input input, Frozen context, Reply answer, String error, Instant createdAt) {}
    record Claim(Stored stored, boolean execute) {}
    public ChatRepository(JdbcTemplate jdbc, DemoJson json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; tx = new TransactionTemplate(manager);
    }
    private State lock() {
        return jdbc.queryForObject("SELECT * FROM demo_chat_state WHERE user_id=? FOR UPDATE",
                (rs,n) -> new State(rs.getLong("generation"), rs.getLong("next_sequence"), rs.getString("active_request_id")), USER_ID);
    }
    Stored stored(String id) {
        var rows = jdbc.query("SELECT * FROM demo_chat_operation WHERE id=? AND user_id=?", (rs,n) -> new Stored(
                rs.getString("id"), rs.getString("client_request_id"), rs.getLong("generation"), rs.getLong("sequence_number"),
                rs.getString("status"), rs.getInt("attempt_count"), rs.getString("request_fingerprint"),
                read(rs.getString("request_json"), Input.class), read(rs.getString("context_json"), Frozen.class),
                read(rs.getString("answer_json"), Reply.class), rs.getString("error_code"), rs.getTimestamp("created_at").toInstant()), id, USER_ID);
        if (rows.isEmpty()) throw new DemoException(404, "CHAT_NOT_FOUND", "聊天操作不存在，请刷新历史");
        return rows.getFirst();
    }
    private <T> T read(String value, Class<T> type) { return value == null ? null : json.read(value, type); }
    Claim claim(Input input, String scope, Supplier<Frozen> prepare) {
        return tx.execute(status -> {
            var state = lock();
            var ids = jdbc.queryForList("SELECT id FROM demo_chat_operation WHERE user_id=? AND client_request_id=?", String.class, USER_ID, input.clientRequestId());
            String fingerprint = json.fingerprint(input);
            Stored existing = ids.isEmpty() ? null : stored(ids.getFirst());
            if (existing != null) {
                if (!fingerprint.equals(existing.fingerprint())) throw new DemoException(409, "IDEMPOTENCY_CONFLICT", "请求标识已用于不同内容");
                if (!Set.of("FAILED", "INTERRUPTED").contains(existing.status())) return new Claim(existing, false);
            }
            if (state.generation() != input.expectedGeneration()) throw new DemoException(409, "CHAT_GENERATION_CHANGED", "聊天已清空，请刷新后使用新请求标识发送");
            if (state.activeId() != null) throw new DemoException(409, "CHAT_BUSY", "同一用户正在生成回复，请等待完成后重试");
            var frozen = prepare.get();
            String id = existing == null ? UUID.randomUUID().toString() : existing.id();
            if (existing == null) {
                jdbc.update("INSERT INTO demo_chat_operation(id,user_id,generation,sequence_number,client_request_id,request_fingerprint,job_snapshot_id,analysis_id,scope_fingerprint,status,request_json,context_json) VALUES (?,?,?,?,?,?,?,?,?,'RUNNING',?,?)",
                        id, USER_ID, state.generation(), state.nextSequence(), input.clientRequestId(), fingerprint, input.jobSnapshotId(), input.analysisId(), scope, json.write(input), json.write(frozen));
                jdbc.update("UPDATE demo_chat_state SET next_sequence=next_sequence+2 WHERE user_id=?", USER_ID);
            } else {
                // Retry the same frozen context, sequence and operation. Newer messages never alter it.
                jdbc.update("UPDATE demo_chat_operation SET status='RUNNING',attempt_count=attempt_count+1,error_code=NULL,answer_json=NULL,completed_at=NULL WHERE id=?", id);
            }
            jdbc.update("UPDATE demo_chat_state SET active_request_id=? WHERE user_id=?", id, USER_ID);
            return new Claim(stored(id), true);
        });
    }
    Turn finish(Stored operation, Reply reply, String error) {
        return tx.execute(status -> {
            var state = lock();
            if (state.generation() == operation.generation() && operation.id().equals(state.activeId())) {
                jdbc.update("UPDATE demo_chat_operation SET status=?,answer_json=?,error_code=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RUNNING'",
                        error == null ? "SUCCEEDED" : "FAILED", reply == null ? null : json.write(reply), error, operation.id());
                jdbc.update("UPDATE demo_chat_state SET active_request_id=NULL WHERE user_id=? AND active_request_id=?", USER_ID, operation.id());
            }
            return view(stored(operation.id()));
        });
    }
    public Turn get(String id) { return view(stored(id)); }
    Turn view(Stored row) {
        String action = switch (row.status()) {
            case "FAILED", "INTERRUPTED" -> "RETRY_SAME_REQUEST";
            case "RUNNING" -> "WAIT_OR_QUERY";
            case "CLEARED" -> "REFRESH_GENERATION";
            default -> "NONE";
        };
        if (row.input() == null) return new Turn(row.id(), row.clientId(), row.generation(), row.status(), row.attempts(), null, null, row.error(), action, null, row.createdAt());
        var bindings = row.context().bindings();
        var question = new Message(row.id() + ":question", "USER", row.sequence(), null, row.input().question(), row.input().jobSnapshotId(), row.input().analysisId(), bindings, List.of());
        var answer = row.answer() == null ? null : new Message(row.id() + ":reply", "ASSISTANT", row.sequence()+1, question.id(), row.answer().answer(), row.input().jobSnapshotId(), row.input().analysisId(), bindings, row.answer().evidenceRefs());
        return new Turn(row.id(), row.clientId(), row.generation(), row.status(), row.attempts(), question, answer, row.error(), action, row.context().policy(), row.createdAt());
    }
    List<Stored> recent(long generation, String scope) {
        return jdbc.queryForList("SELECT id FROM demo_chat_operation WHERE user_id=? AND generation=? AND scope_fingerprint=? AND status='SUCCEEDED' ORDER BY sequence_number DESC LIMIT 7", String.class,
                USER_ID, generation, scope).stream().map(this::stored).toList();
    }
    int recentCount(long generation, String scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demo_chat_operation WHERE user_id=? AND generation=? AND scope_fingerprint=? AND status='SUCCEEDED'", Integer.class, USER_ID, generation, scope);
    }
    public History history(long after, int limit, String job, Long expectedGeneration) {
        return tx.execute(status -> {
            var state = lock();
            if (expectedGeneration != null && expectedGeneration != state.generation()) throw new DemoException(409, "CHAT_GENERATION_CHANGED", "聊天代次已变化，请从首页重新读取");
            var ids = jdbc.queryForList("SELECT id FROM demo_chat_operation WHERE user_id=? AND generation=? AND sequence_number>? AND (? IS NULL OR job_snapshot_id=?) ORDER BY sequence_number LIMIT ?",
                    String.class, USER_ID, state.generation(), after, job, job, limit+1);
            boolean more = ids.size() > limit;
            var turns = ids.stream().limit(limit).map(this::get).toList();
            Long next = turns.isEmpty() ? null : stored(turns.getLast().id()).sequence()+1;
            return new History(state.generation(), turns, next, more);
        });
    }
    public History clear(long expectedGeneration) {
        return tx.execute(status -> {
            var state = lock();
            if (state.generation() != expectedGeneration) throw new DemoException(409, "CHAT_GENERATION_CHANGED", "聊天已被清空，请刷新代次");
            jdbc.update("UPDATE demo_chat_operation SET status='CLEARED',request_json=NULL,context_json=NULL,answer_json=NULL,error_code='CHAT_CLEARED' WHERE user_id=? AND generation=?",
                    USER_ID, state.generation());
            jdbc.update("UPDATE demo_chat_state SET generation=generation+1,next_sequence=1,active_request_id=NULL WHERE user_id=?", USER_ID);
            return new History(state.generation()+1, List.of(), null, false);
        });
    }
    public void recover() {
        tx.executeWithoutResult(status -> {
            lock();
            jdbc.update("UPDATE demo_chat_operation SET status='INTERRUPTED',error_code='CHAT_PROCESS_INTERRUPTED',completed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND status='RUNNING'", USER_ID);
            jdbc.update("UPDATE demo_chat_state SET active_request_id=NULL WHERE user_id=?", USER_ID);
        });
    }
}
