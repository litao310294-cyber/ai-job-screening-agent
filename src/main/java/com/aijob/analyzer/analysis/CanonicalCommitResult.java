package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import java.util.Objects;

public sealed interface CanonicalCommitResult {

    record Inserted(AnalysisArtifact artifact) implements CanonicalCommitResult {
        public Inserted {
            Objects.requireNonNull(artifact);
        }
    }

    record Reused(AnalysisArtifact artifact) implements CanonicalCommitResult {
        public Reused {
            Objects.requireNonNull(artifact);
        }
    }

    record Uncertain(String errorCode) implements CanonicalCommitResult {
        public Uncertain {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode cannot be blank");
            }
        }
    }
}
