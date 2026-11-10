package com.aijob.analyzer.support.retry;

@FunctionalInterface
public interface RetryableOperation<T> {
    T execute(int attempt);
}
