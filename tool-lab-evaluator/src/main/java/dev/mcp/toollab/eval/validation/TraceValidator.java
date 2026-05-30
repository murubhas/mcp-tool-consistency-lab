package dev.mcp.toollab.eval.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.trace.TraceRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class TraceValidator {
    public static final String TRACE_EQUIVALENCE_VERSION = "trace-eq-v1";

    private final AcceptedTraceSet acceptedTraceSet;
    private final ToolSchemaRegistry registry;
    private final ObjectMapper mapper;

    public TraceValidator(AcceptedTraceSet acceptedTraceSet, ToolSchemaRegistry registry, ObjectMapper mapper) {
        this.acceptedTraceSet = Objects.requireNonNull(acceptedTraceSet, "acceptedTraceSet");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public JsonNode firstAcceptedTrace(EvalTask task) {
        return acceptedTraceSet.acceptedTraces(task.taskId()).get(0);
    }

    public boolean traceMatches(TraceRecord record, EvalTask task) {
        JsonNode observed = canonicalTrace(record.json().path("steps"));
        for (JsonNode accepted : acceptedTraceSet.acceptedTraces(task.taskId())) {
            JsonNode acceptedCanonical = canonicalTrace(accepted.path("steps"));
            if (CanonicalJson.writeCanonical(observed).equals(CanonicalJson.writeCanonical(acceptedCanonical))) {
                return true;
            }
        }
        return false;
    }

    public boolean toolSelectionMatches(TraceRecord record, EvalTask task) {
        JsonNode observed = canonicalToolNames(record.json().path("steps"));
        for (JsonNode accepted : acceptedTraceSet.acceptedTraces(task.taskId())) {
            JsonNode acceptedNames = canonicalToolNames(accepted.path("steps"));
            if (CanonicalJson.writeCanonical(observed).equals(CanonicalJson.writeCanonical(acceptedNames))) {
                return true;
            }
        }
        return false;
    }

    public boolean toolOutcomesMatch(TraceRecord record, EvalTask task) {
        JsonNode observed = canonicalToolOutcomes(record.json().path("steps"));
        for (JsonNode accepted : acceptedTraceSet.acceptedTraces(task.taskId())) {
            JsonNode acceptedOutcomes = canonicalToolOutcomes(accepted.path("steps"));
            if (CanonicalJson.writeCanonical(observed).equals(CanonicalJson.writeCanonical(acceptedOutcomes))) {
                return true;
            }
        }
        return false;
    }

    public boolean structuredResponseMatches(TraceRecord record, EvalTask task) {
        return task.expectedResponseType().equals(record.json().path("finalResponse").path("responseType").asText());
    }

    public JsonNode canonicalTrace(JsonNode steps) {
        ArrayNode canonicalSteps = mapper.createArrayNode();
        for (JsonNode step : steps) {
            List<JsonNode> calls = canonicalCalls(step.path("toolCalls"), CanonicalMode.FULL);
            ArrayNode callArray = mapper.createArrayNode();
            calls.forEach(callArray::add);
            ObjectNode stepNode = canonicalSteps.addObject();
            stepNode.set("toolCalls", callArray);
        }
        return canonicalSteps;
    }

    private JsonNode canonicalToolNames(JsonNode steps) {
        ArrayNode canonicalSteps = mapper.createArrayNode();
        for (JsonNode step : steps) {
            List<JsonNode> calls = canonicalCalls(step.path("toolCalls"), CanonicalMode.NAMES);
            ArrayNode callArray = mapper.createArrayNode();
            calls.forEach(callArray::add);
            ObjectNode stepNode = canonicalSteps.addObject();
            stepNode.set("toolCalls", callArray);
        }
        return canonicalSteps;
    }

    private JsonNode canonicalToolOutcomes(JsonNode steps) {
        ArrayNode canonicalSteps = mapper.createArrayNode();
        for (JsonNode step : steps) {
            List<JsonNode> calls = canonicalCalls(step.path("toolCalls"), CanonicalMode.OUTCOMES);
            ArrayNode callArray = mapper.createArrayNode();
            calls.forEach(callArray::add);
            ObjectNode stepNode = canonicalSteps.addObject();
            stepNode.set("toolCalls", callArray);
        }
        return canonicalSteps;
    }

    private List<JsonNode> canonicalCalls(JsonNode toolCalls, CanonicalMode mode) {
        List<JsonNode> calls = new ArrayList<>();
        boolean allReadOnly = true;
        for (JsonNode call : toolCalls) {
            if (!readOnly(call)) {
                allReadOnly = false;
            }
            ObjectNode node = mapper.createObjectNode();
            String toolName = call.path("name").asText().toLowerCase();
            node.put("name", toolName);
            if (mode == CanonicalMode.FULL) {
                JsonNode args = call.has("canonicalArguments") ? call.path("canonicalArguments") : call.path("arguments");
                node.set("arguments", canonicalArguments(toolName, args));
            }
            if (mode == CanonicalMode.FULL || mode == CanonicalMode.OUTCOMES) {
                String errorCode = errorCode(call);
                if (errorCode != null) {
                    node.put("errorCode", errorCode);
                }
            }
            calls.add(node);
        }
        if (allReadOnly) {
            calls.sort(Comparator.comparing(CanonicalJson::writeCanonical));
        }
        return calls;
    }

    private JsonNode canonicalArguments(String toolName, JsonNode arguments) {
        if (!arguments.isObject()) {
            return arguments.deepCopy();
        }
        ObjectNode normalized = arguments.deepCopy();
        registry.find(toolName)
                .map(definition -> definition.inputSchema().path("properties"))
                .filter(JsonNode::isObject)
                .ifPresent(properties -> properties.fields().forEachRemaining(field -> {
                    if (!normalized.has(field.getKey()) && field.getValue().has("default")) {
                        normalized.set(field.getKey(), field.getValue().path("default").deepCopy());
                    }
                }));
        return normalized;
    }

    private boolean readOnly(JsonNode call) {
        if (call.has("sideEffects")) {
            return "read_only".equals(call.path("sideEffects").asText());
        }
        return registry.find(call.path("name").asText()).map(definition -> definition.readOnly()).orElse(false);
    }

    private String errorCode(JsonNode call) {
        if (call.has("errorCode")) {
            return call.path("errorCode").asText();
        }
        if (call.has("expectedErrorCode")) {
            return call.path("expectedErrorCode").asText();
        }
        if (call.has("expectedSuccess") && !call.path("expectedSuccess").asBoolean()) {
            return "FAILED";
        }
        return null;
    }

    private enum CanonicalMode {
        FULL,
        NAMES,
        OUTCOMES
    }
}
