package com.aijob.analyzer.cache.model;

import java.util.Objects;

public record CacheReadResult<T>(
        CacheAccessStatus accessStatus,
        CacheLookupOutcome lookupOutcome,
        T value) {
    public CacheReadResult {
        Objects.requireNonNull(accessStatus);
        if (accessStatus == CacheAccessStatus.SUCCESS) {
            if (lookupOutcome == null) {
                throw new IllegalArgumentException("lookupOutcome cannot be null when accessStatus is SUCCESS");
            }
        } else {
            if (lookupOutcome != null) {
                throw new IllegalArgumentException("lookupOutcome must be null when accessStatus is not SUCCESS");
            }
        }
    }
}
