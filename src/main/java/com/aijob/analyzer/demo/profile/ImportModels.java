package com.aijob.analyzer.demo.profile;

import com.aijob.analyzer.profile.domain.CandidateProfileDraft;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class ImportModels {
    private ImportModels() {}
    public record View(String id, String fileName, String contentSha256, String semanticFingerprint,
                       String sourceKind, String originalImportId, String providerMode, String status,
                       long revision, Long profileArtifactId, Integer pageCount, int textLength,
                       String errorCode, Instant createdAt, Instant confirmedAt, boolean current) {}
    public record TextInput(@NotBlank @Size(max=255) String fileName,
                            @NotBlank @Size(max=100000) String text, @Size(max=36) String originalImportId) {}
    public record FactsInput(@PositiveOrZero long expectedRevision, @NotNull CandidateProfileDraft facts) {}
    public record ConfirmInput(@PositiveOrZero long expectedRevision, @AssertTrue boolean confirmed) {}
    public record FactsView(View operation, String extractedText, CandidateProfileDraft facts) {}
}
