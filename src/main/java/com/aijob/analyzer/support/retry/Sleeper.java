package com.aijob.analyzer.support.retry;

import java.time.Duration;

public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
}
