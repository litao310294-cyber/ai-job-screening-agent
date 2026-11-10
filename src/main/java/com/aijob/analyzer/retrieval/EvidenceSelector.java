package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.FusedHit;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;

import java.util.*;

public class EvidenceSelector {

    public List<Evidence> select(
            AtomicRequirement requirement,
            List<FusedHit> fusedHits,
            Map<String, ProfileChunk> chunkById,
            int topK) {

        List<Evidence> selected = new ArrayList<>();
        Set<String> seenFactIds = new HashSet<>();

        for (FusedHit hit : fusedHits) {
            if (seenFactIds.contains(hit.factId())) {
                continue;
            }

            ProfileChunk chunk = chunkById.get(hit.chunkId());
            if (chunk == null) {
                continue;
            }

            selected.add(new Evidence(
                    hit.factId(), // evidenceRef = factId in V1
                    requirement.requirementId(),
                    hit.factId(),
                    chunk.content(),
                    chunk.source()
            ));

            seenFactIds.add(hit.factId());

            if (selected.size() >= topK) {
                break;
            }
        }

        return selected;
    }
}
