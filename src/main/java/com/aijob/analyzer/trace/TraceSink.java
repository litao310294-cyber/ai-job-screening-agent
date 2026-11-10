package com.aijob.analyzer.trace;

/** Minimal observation boundary. Implementations must not own business decisions. */
@FunctionalInterface
public interface TraceSink {
    void record(TraceRecord record);
}
