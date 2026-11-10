package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;

import java.util.List;

public interface ProfileChunker {
    List<ProfileChunk> chunk(CandidateProfile profile);
}
