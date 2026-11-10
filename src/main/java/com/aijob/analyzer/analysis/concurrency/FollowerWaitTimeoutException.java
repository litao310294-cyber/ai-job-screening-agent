package com.aijob.analyzer.analysis.concurrency;

public final class FollowerWaitTimeoutException extends RuntimeException {
    public FollowerWaitTimeoutException() {
        super("follower wait timeout");
    }
}
