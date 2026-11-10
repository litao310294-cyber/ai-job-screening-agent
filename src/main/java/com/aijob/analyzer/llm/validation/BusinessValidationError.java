package com.aijob.analyzer.llm.validation;

public class BusinessValidationError extends RuntimeException {
    public BusinessValidationError(String message) {
        super(message);
    }
}
