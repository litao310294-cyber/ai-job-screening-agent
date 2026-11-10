package com.aijob.analyzer.llm.validation;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class DefaultSafeJsonRepair implements SafeJsonRepair {

    private static final Pattern OUTER_MARKDOWN_FENCE = Pattern.compile(
            "\\A```(?:json)?[ \\t]*(?:\\r?\\n)?(.*?)(?:\\r?\\n)?```\\z",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PARTIAL_STATUS_ALIAS = Pattern.compile(
            "(\\\"status\\\"\\s*:\\s*)\\\"PARTIAL\\\""
    );

    @Override
    public Optional<String> repair(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        String repaired = trimOuterWhitespaceAndBom(raw);

        // 2. remove one outer Markdown code fence, optionally labeled json
        var matcher = OUTER_MARKDOWN_FENCE.matcher(repaired);
        if (matcher.matches()) {
            repaired = trimOuterWhitespaceAndBom(matcher.group(1));
        }

        // 3. exact enum alias: PARTIAL -> PARTIALLY_SUPPORTED
        repaired = PARTIAL_STATUS_ALIAS.matcher(repaired)
                .replaceAll("$1\\\"PARTIALLY_SUPPORTED\\\"");

        return Optional.of(repaired);
    }

    private String trimOuterWhitespaceAndBom(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("\uFEFF")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.endsWith("\uFEFF")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }
}
