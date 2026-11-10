package com.aijob.analyzer.workflow.domain;

import java.util.List;

public record RuleResult(
        RuleSignal signal,
        List<String> reasons, List<com.aijob.analyzer.rules.RuleModels.Item> items) {
    public RuleResult(RuleSignal signal, List<String> reasons) { this(signal,reasons,List.of()); }
    public RuleResult {
        java.util.Objects.requireNonNull(signal);
        if (reasons == null) {
            reasons = List.of();
        }
        reasons = List.copyOf(reasons);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
