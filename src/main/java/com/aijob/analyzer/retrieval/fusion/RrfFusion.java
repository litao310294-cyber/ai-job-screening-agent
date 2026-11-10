package com.aijob.analyzer.retrieval.fusion;

import com.aijob.analyzer.retrieval.domain.FusedHit;
import com.aijob.analyzer.retrieval.domain.RankedHit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RrfFusion implements RankFusion {

    private final RrfConfig config;

    public RrfFusion(RrfConfig config) {
        this.config = config;
    }

    @Override
    public List<FusedHit> fuse(List<RankedHit> keywordHits, List<RankedHit> vectorHits) {
        Map<String, RrfHitBuilder> builders = new HashMap<>();

        for (RankedHit hit : keywordHits) {
            builders.computeIfAbsent(hit.chunkId(), id -> new RrfHitBuilder(id, hit.factId()))
                    .addRank(hit.rank());
        }

        for (RankedHit hit : vectorHits) {
            builders.computeIfAbsent(hit.chunkId(), id -> new RrfHitBuilder(id, hit.factId()))
                    .addRank(hit.rank());
        }

        return builders.values().stream()
                .map(builder -> {
                    double score = 0;
                    for (int rank : builder.ranks) {
                        score += 1.0 / (config.k() + rank);
                    }
                    return new FusedHit(builder.chunkId, builder.factId, score, 0);
                })
                .sorted(Comparator.comparingDouble(FusedHit::rrfScore).reversed())
                .collect(Collectors.collectingAndThen(Collectors.toList(), list -> {
                    for (int i = 0; i < list.size(); i++) {
                        var old = list.get(i);
                        list.set(i, new FusedHit(old.chunkId(), old.factId(), old.rrfScore(), i + 1));
                    }
                    return list;
                }));
    }

    private static class RrfHitBuilder {
        private final String chunkId;
        private final String factId;
        private final List<Integer> ranks = new java.util.ArrayList<>();

        RrfHitBuilder(String chunkId, String factId) {
            this.chunkId = chunkId;
            this.factId = factId;
        }

        void addRank(int rank) {
            ranks.add(rank);
        }
    }
}
