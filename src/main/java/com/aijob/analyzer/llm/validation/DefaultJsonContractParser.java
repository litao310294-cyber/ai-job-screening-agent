package com.aijob.analyzer.llm.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DefaultJsonContractParser implements JsonContractParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public DefaultJsonContractParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Override
    public <T> T parse(String raw, Class<T> type) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(raw);
            ModelOutputContract.validate(root, type);
            T artifact = objectMapper.treeToValue(root, type);
            validate(artifact);
            return artifact;
        } catch (JsonMappingException e) {
            throw new SchemaValidationError("Schema mapping failed: " + e.getMessage());
        } catch (JsonProcessingException e) {
            throw new JsonParseException("Failed to parse JSON syntax: " + e.getMessage(), e);
        }
    }

    private <T> void validate(T artifact) {
        Set<ConstraintViolation<T>> violations = validator.validate(artifact);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new SchemaValidationError("Schema validation failed: " + message);
        }
    }
}
