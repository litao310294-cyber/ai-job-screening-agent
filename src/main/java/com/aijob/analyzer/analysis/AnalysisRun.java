package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;

public record AnalysisRun(AnalysisExecution execution, String reuseSource, Long canonicalArtifactId,
                          AnalysisDependencySnapshot dependencySnapshot, String errorCode) {}
