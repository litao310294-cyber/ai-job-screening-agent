package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;

public class RetrievalQueryBuilder {

    public RetrievalQuery from(AtomicRequirement requirement) {
        String text = requirement.normalizedText();
        if (text != null) {
            text = text.trim().replaceAll("\\s+", " ");
        }
        return new RetrievalQuery(requirement.requirementId(), text);
    }
}
