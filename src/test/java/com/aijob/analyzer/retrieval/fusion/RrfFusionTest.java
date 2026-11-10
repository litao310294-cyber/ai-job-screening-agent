package com.aijob.analyzer.retrieval.fusion;

import com.aijob.analyzer.retrieval.domain.FusedHit;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RrfFusionTest {

    @Test
    void should_calculate_rrf_score_correctly() {
        // Given
        RrfConfig config = new RrfConfig(60);
        RrfFusion fusion = new RrfFusion(config);

        // Chunk A: keyword rank 1, vector rank 2
        RankedHit kwA = new RankedHit("A", "F1", 10.0, 1);
        RankedHit vecA = new RankedHit("A", "F1", 0.9, 2);

        // When
        List<FusedHit> fused = fusion.fuse(List.of(kwA), List.of(vecA));

        // Then
        assertThat(fused).hasSize(1);
        double expectedScore = (1.0 / (60 + 1)) + (1.0 / (60 + 2));
        assertThat(fused.get(0).rrfScore()).isCloseTo(expectedScore, within(0.0001));
    }

    @Test
    void should_be_independent_of_raw_scores() {
        // Given
        RrfFusion fusion = new RrfFusion(RrfConfig.DEFAULT);

        RankedHit kw1 = new RankedHit("A", "F1", 100.0, 1);
        RankedHit kw2 = new RankedHit("A", "F1", 0.1, 1);

        // When
        List<FusedHit> fused1 = fusion.fuse(List.of(kw1), List.of());
        List<FusedHit> fused2 = fusion.fuse(List.of(kw2), List.of());

        // Then
        assertThat(fused1.get(0).rrfScore()).isEqualTo(fused2.get(0).rrfScore());
    }

    @Test
    void should_rank_correctly_after_fusion() {
        // Given
        RrfFusion fusion = new RrfFusion(RrfConfig.DEFAULT);

        // A is rank 1 in both
        RankedHit kwA = new RankedHit("A", "F1", 10.0, 1);
        RankedHit vecA = new RankedHit("A", "F1", 0.9, 1);

        // B is rank 2 in both
        RankedHit kwB = new RankedHit("B", "F2", 5.0, 2);
        RankedHit vecB = new RankedHit("B", "F2", 0.8, 2);

        // When
        List<FusedHit> fused = fusion.fuse(List.of(kwA, kwB), List.of(vecA, vecB));

        // Then
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).chunkId()).isEqualTo("A");
        assertThat(fused.get(0).rank()).isEqualTo(1);
        assertThat(fused.get(1).chunkId()).isEqualTo("B");
        assertThat(fused.get(1).rank()).isEqualTo(2);
    }
}
