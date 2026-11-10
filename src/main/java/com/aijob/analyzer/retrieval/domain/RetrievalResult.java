package com.aijob.analyzer.retrieval.domain;

import java.util.List;
import java.util.Map;

public record RetrievalResult(
        Map<String, List<Evidence>> evidenceByRequirement) {
}
