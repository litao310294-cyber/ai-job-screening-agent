package com.aijob.analyzer.llm.validation;

public class SchemaValidationError extends RuntimeException {
    public SchemaValidationError(String message) {
        super(message);
    }
}
