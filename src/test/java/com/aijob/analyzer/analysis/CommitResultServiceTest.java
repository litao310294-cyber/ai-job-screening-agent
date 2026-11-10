package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CommitResultServiceTest {
    @Test
    void uncertainFoundReturnsDurableArtifactWithoutAnyRecompute() {
        AnalysisResultRepository repository = mock(AnalysisResultRepository.class);
        AnalysisIdentity identity = mock(AnalysisIdentity.class);
        AnalysisDependencySnapshot snapshot = mock(AnalysisDependencySnapshot.class);
        JobAnalysisResult result = mock(JobAnalysisResult.class);
        AnalysisArtifact artifact = mock(AnalysisArtifact.class);
        when(repository.commitCanonical(identity, result, snapshot, null, null)).thenReturn(new CanonicalCommitResult.Uncertain("TIMEOUT"));
        when(repository.readBack(identity)).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));

        assertSame(artifact, new CommitResultService(repository).commit(identity, result, snapshot, null, null));
        verify(repository).commitCanonical(identity, result, snapshot, null, null);
        verify(repository).readBack(identity);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void uncertainReadBackFailedRemainsUncertain() {
        AnalysisResultRepository repository = mock(AnalysisResultRepository.class);
        AnalysisIdentity identity = mock(AnalysisIdentity.class);
        AnalysisDependencySnapshot snapshot = mock(AnalysisDependencySnapshot.class);
        JobAnalysisResult result = mock(JobAnalysisResult.class);
        when(repository.commitCanonical(identity, result, snapshot, null, null)).thenReturn(new CanonicalCommitResult.Uncertain("TIMEOUT"));
        when(repository.readBack(identity)).thenReturn(new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "READ_FAILED"));

        assertThrows(CommitResultService.CommitUncertainException.class,
                () -> new CommitResultService(repository).commit(identity, result, snapshot, null, null));
    }
}
