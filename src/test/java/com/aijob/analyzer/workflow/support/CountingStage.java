package com.aijob.analyzer.workflow.support;

import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.StageType;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class CountingStage implements AnalysisStage {
    private final StageType type;
    private final AtomicInteger count = new AtomicInteger(0);
    private final Function<AnalysisContext, StageResult> behavior;

    public CountingStage(StageType type, Function<AnalysisContext, StageResult> behavior) {
        this.type = type;
        this.behavior = behavior;
    }

    @Override
    public StageType type() {
        return type;
    }

    @Override
    public StageResult execute(AnalysisContext context) {
        count.incrementAndGet();
        return behavior.apply(context);
    }

    public int count() {
        return count.get();
    }
}
