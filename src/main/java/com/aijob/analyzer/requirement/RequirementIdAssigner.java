package com.aijob.analyzer.requirement;

import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RetrievalRequirementDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RequirementIdAssigner {

    public List<AtomicRequirement> assignIds(List<RetrievalRequirementDraft> drafts) {
        List<AtomicRequirement> requirements = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            RetrievalRequirementDraft draft = drafts.get(i);
            String requirementId = String.format("R%04d", i + 1);
            requirements.add(new AtomicRequirement(
                    requirementId,
                    draft.requirementType(),
                    draft.normalizedText(),
                    draft.priority(),
                    draft.sourceText()
            ));
        }
        return requirements;
    }
}
