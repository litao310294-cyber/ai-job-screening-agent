package com.aijob.analyzer.llm.error;

public class ProviderTransportException extends RuntimeException {
    public ProviderTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
