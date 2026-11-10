package com.aijob.analyzer.support.time;

public interface TimeSource {
    long nanoTime();

    TimeSource SYSTEM = System::nanoTime;
}
