package com.aijob.analyzer.support;

import java.util.function.Supplier;

public class CountingSupplier<T> implements Supplier<T> {
    private final Supplier<T> delegate;
    private int invocationCount = 0;

    public CountingSupplier(Supplier<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T get() {
        invocationCount++;
        return delegate.get();
    }

    public int getInvocationCount() {
        return invocationCount;
    }
}
