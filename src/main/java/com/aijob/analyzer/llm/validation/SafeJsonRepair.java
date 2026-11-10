package com.aijob.analyzer.llm.validation;

import java.util.Optional;

public interface SafeJsonRepair {
    Optional<String> repair(String raw);
}
