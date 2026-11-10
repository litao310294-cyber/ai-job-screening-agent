package com.aijob.analyzer.llm.validation;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.requirement.domain.JobRequirementDraft;
import com.fasterxml.jackson.databind.JsonNode;

/** Validate wire types before record constructors or Jackson coercion can hide missing data. */
final class ModelOutputContract {
    private ModelOutputContract() {}

    static void validate(JsonNode root, Class<?> type) {
        if (type == com.aijob.analyzer.chat.ChatModels.Reply.class) {
            object(root);
            strings(root, "answer");
            stringArray(root, "evidenceRefs");
        }
        if (type == JobAnalysisResult.class) {
            object(root);
            strings(root, "decision", "overallAssessment", "recommendation", "summary");
            stringArray(root, "risks");
            stringArray(root, "strengths");
            for (var assessment : array(root, "requirementAssessments")) {
                object(assessment);
                strings(assessment, "requirementId", "status", "reasoning", "gap");
                stringArray(assessment, "evidenceRefs");
            }
        }
        if (type == JobRequirementDraft.class) {
            object(root);
            stringArray(root, "ignoredContext");
            for (var constraint : array(root, "structuredConstraints")) {
                object(constraint);
                strings(constraint, "type", "operator", "value", "sourceText");
            }
            for (var requirement : array(root, "retrievalRequirements")) {
                object(requirement);
                strings(requirement, "requirementType", "normalizedText", "priority", "sourceText");
            }
        }
    }
    private static void object(JsonNode value) {
        if (value == null || !value.isObject()) throw new SchemaValidationError("Expected JSON object");
    }
    private static JsonNode array(JsonNode object, String field) {
        var value = object.get(field);
        if (value == null || !value.isArray()) throw new SchemaValidationError("Expected required array: " + field);
        return value;
    }
    private static void stringArray(JsonNode object, String field) {
        for (var value : array(object, field)) {
            if (!value.isTextual()) throw new SchemaValidationError("Expected string array: " + field);
        }
    }
    private static void strings(JsonNode object, String... fields) {
        for (var field : fields) {
            var value = object.get(field);
            if (value == null || !value.isTextual()) throw new SchemaValidationError("Expected required string: " + field);
        }
    }
}
