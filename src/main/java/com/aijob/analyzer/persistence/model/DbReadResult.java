package com.aijob.analyzer.persistence.model;

import java.util.Objects;

public record DbReadResult<T>(
        DbReadOutcome outcome,
        T value,
        String errorCode) {
    public DbReadResult {
        Objects.requireNonNull(outcome);
        if (outcome == DbReadOutcome.FOUND) {
            if (value == null) {
                throw new IllegalArgumentException("value cannot be null when outcome is FOUND");
            }
            if (errorCode != null) {
                throw new IllegalArgumentException("errorCode must be null when outcome is FOUND");
            }
        } else if (outcome == DbReadOutcome.NOT_FOUND) {
            if (value != null) {
                throw new IllegalArgumentException("value must be null when outcome is NOT_FOUND");
            }
            if (errorCode != null) {
                throw new IllegalArgumentException("errorCode must be null when outcome is NOT_FOUND");
            }
        } else if (outcome == DbReadOutcome.READ_FAILED) {
            if (value != null) {
                throw new IllegalArgumentException("value must be null when outcome is READ_FAILED");
            }
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode cannot be blank when outcome is READ_FAILED");
            }
        }
    }
}
