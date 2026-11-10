package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.FusedHit;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSelectorTest {

    @Test
    void should_deduplicate_by_fact_id_and_retain_highest_rank() {
        // Given
        EvidenceSelector selector = new EvidenceSelector();
        AtomicRequirement requirement = new AtomicRequirement("R1", "SKILL", "Java", RequirementPriority.REQUIRED, "Java");

        // Fact 1 has two chunks
        FusedHit hit1 = new FusedHit("C1-1", "F1", 0.5, 1);
        FusedHit hit2 = new FusedHit("C1-2", "F1", 0.4, 2);

        // Fact 2 has one chunk
        FusedHit hit3 = new FusedHit("C2-1", "F2", 0.3, 3);

        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("U1", 1);
        SourceReference source = new SourceReference("S1", "T", "Sec", "Text");
        Map<String, ProfileChunk> chunkById = Map.of(
                "C1-1", new ProfileChunk("C1-1", identity, "F1", "SKILL", "C1-1", source, Map.of()),
                "C1-2", new ProfileChunk("C1-2", identity, "F1", "SKILL", "C1-2", source, Map.of()),
                "C2-1", new ProfileChunk("C2-1", identity, "F2", "SKILL", "C2-1", source, Map.of())
        );

        // When
        List<Evidence> selected = selector.select(requirement, List.of(hit1, hit2, hit3), chunkById, 5);

        // Then
        assertThat(selected).hasSize(2);
        assertThat(selected.stream().map(Evidence::factId).collect(Collectors.toList())).containsExactly("F1", "F2");
    }

    @Test
    void should_limit_to_top_k_distinct_facts() {
        // Given
        EvidenceSelector selector = new EvidenceSelector();
        AtomicRequirement requirement = new AtomicRequirement("R1", "SKILL", "Java", RequirementPriority.REQUIRED, "Java");

        List<FusedHit> hits = List.of(
                new FusedHit("C1", "F1", 0.9, 1),
                new FusedHit("C2", "F2", 0.8, 2),
                new FusedHit("C3", "F3", 0.7, 3),
                new FusedHit("C4", "F4", 0.6, 4),
                new FusedHit("C5", "F5", 0.5, 5),
                new FusedHit("C6", "F6", 0.4, 6)
        );

        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("U1", 1);
        SourceReference source = new SourceReference("S1", "T", "Sec", "Text");
        Map<String, ProfileChunk> chunkById = hits.stream().collect(Collectors.toMap(
                FusedHit::chunkId,
                h -> new ProfileChunk(h.chunkId(), identity, h.factId(), "SKILL", h.chunkId(), source, Map.of())
        ));

        // When
        List<Evidence> selected = selector.select(requirement, hits, chunkById, 5);

        // Then
        assertThat(selected).hasSize(5);
        assertThat(selected.stream().map(Evidence::factId).collect(Collectors.toList())).containsExactly("F1", "F2", "F3", "F4", "F5");
    }
}
