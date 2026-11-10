package com.aijob.analyzer.cache;

import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Enforces the frozen Redis -> durable store -> compute decision order. */
public final class CacheAsideResolver<T> {

    public T resolve(
            TraceContext trace,
            TraceRecorder recorder,
            Supplier<CacheReadResult<T>> cacheRead,
            Supplier<DbReadResult<T>> dbRead,
            Supplier<T> compute,
            Consumer<T> cachePopulate) {
        CacheReadResult<T> cache = Objects.requireNonNull(cacheRead.get(), "cacheRead result");

        if (recorder != null && trace != null) {
            recorder.event(trace, TraceEventType.CANONICAL_REUSE, Map.of(
                    "source", "REDIS",
                    "status", cache.accessStatus().name(),
                    "outcome", String.valueOf(cache.lookupOutcome())
            ));
        }

        if (cache.lookupOutcome() == CacheLookupOutcome.HIT) {
            return cache.value();
        }

        DbReadResult<T> database = Objects.requireNonNull(dbRead.get(), "dbRead result");

        if (recorder != null && trace != null) {
            recorder.event(trace, TraceEventType.CANONICAL_REUSE, Map.of(
                    "source", "DATABASE",
                    "outcome", database.outcome().name()
            ));
        }

        if (database.outcome() == DbReadOutcome.FOUND) {
            bestEffortPopulate(cachePopulate, database.value());
            return database.value();
        }
        if (database.outcome() == DbReadOutcome.NOT_FOUND) {
            T computed = compute.get();
            bestEffortPopulate(cachePopulate, computed);
            return computed;
        }
        throw new DurableReadFailedException(database.errorCode());
    }

    private void bestEffortPopulate(Consumer<T> cachePopulate, T value) {
        try {
            cachePopulate.accept(value);
        } catch (RuntimeException ignored) {
            // Redis is an acceleration copy; durable success remains successful.
        }
    }

    public static final class DurableReadFailedException extends RuntimeException {
        public DurableReadFailedException(String errorCode) {
            super(errorCode);
        }
    }
}
