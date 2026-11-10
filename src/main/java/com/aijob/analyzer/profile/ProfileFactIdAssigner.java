package com.aijob.analyzer.profile;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.ProfileFact;
import com.aijob.analyzer.profile.domain.RetrievalFactDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ProfileFactIdAssigner {

    public List<ProfileFact> assignIds(List<RetrievalFactDraft> drafts, long artifactId) {
        List<ProfileFact> facts = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            RetrievalFactDraft draft = drafts.get(i);
            String factId = String.format("F%04d", i + 1);
            facts.add(new ProfileFact(
                    factId,
                    artifactId,
                    draft.factType(),
                    draft.normalizedText(),
                    draft.source()
            ));
        }
        return facts;
    }
}
