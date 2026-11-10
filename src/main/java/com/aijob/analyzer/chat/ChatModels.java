package com.aijob.analyzer.chat;

import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ChatModels {
    private ChatModels() {}
    public record Input(@NotBlank @Size(max=128) String clientRequestId,
                        @NotBlank @Size(max=36) String jobSnapshotId,
                        @NotBlank @Size(max=36) String analysisId,
                        @NotBlank @Size(max=2000) String question,
                        @NotNull @PositiveOrZero Long expectedGeneration,
                        @Size(max=1) List<@NotBlank @Size(max=36) String> comparisonAnalysisIds) {
        public Input { comparisonAnalysisIds = comparisonAnalysisIds == null ? List.of() : List.copyOf(comparisonAnalysisIds); }
    }
    public record Clear(@NotNull @PositiveOrZero Long expectedGeneration) {}
    public record Reply(@NotBlank @Size(max=4000) String answer, @NotNull @Size(max=12) List<@NotBlank String> evidenceRefs) {}
    public record Binding(String analysisId, String jobSnapshotId, AnalysisDependencySnapshot dependencies, String resultOrigin) {}
    public record Policy(int inputCharacters, int historyTurns, int omittedHistoryTurns, int truncatedFields,
                         int maxInputCharacters, int maxRecentTurns, String strategy) {}
    public record Frozen(String systemPrompt, String userPrompt, LlmSemanticSnapshot semantics,
                         List<Binding> bindings, List<String> allowedEvidenceRefs, Policy policy) {}
    public record Message(String id, String role, long sequence, String replyToMessageId, String content,
                          String jobSnapshotId, String analysisId, List<Binding> bindings, List<String> evidenceRefs) {}
    public record Turn(String id, String clientRequestId, long generation, String status, int attemptCount,
                       Message question, Message reply, String errorCode, String nextAction, Policy contextPolicy, Instant createdAt) {}
    public record History(long generation, List<Turn> turns, Long nextAfterSequence, boolean hasMore) {}
}
