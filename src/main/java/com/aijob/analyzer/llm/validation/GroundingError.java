package com.aijob.analyzer.llm.validation;

public class GroundingError extends RuntimeException {
    public GroundingError(String message) {
        super(message);
    }
}
