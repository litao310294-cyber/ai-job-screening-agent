package com.aijob.analyzer.retrieval.fusion;

import com.aijob.analyzer.retrieval.domain.FusedHit;
import com.aijob.analyzer.retrieval.domain.RankedHit;

import java.util.List;

public interface RankFusion {
    List<FusedHit> fuse(
            List<RankedHit> keywordHits,
            List<RankedHit> vectorHits);
}
