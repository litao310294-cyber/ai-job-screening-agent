package com.aijob.analyzer.llm.error;

public class ProviderError extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    public ProviderError(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
