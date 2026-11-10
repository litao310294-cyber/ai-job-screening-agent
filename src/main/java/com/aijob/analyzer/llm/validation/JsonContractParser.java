package com.aijob.analyzer.llm.validation;

public interface JsonContractParser {
    <T> T parse(String raw, Class<T> type);
}
