package dev.mcp.toollab.eval.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.ToolCallResult;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.model.FinalResponse;
import dev.mcp.toollab.eval.model.ModelClient;
import dev.mcp.toollab.eval.model.ModelOutput;
import dev.mcp.toollab.eval.model.ProviderResponseDecodeException;
import dev.mcp.toollab.eval.model.ToolCall;
import dev.mcp.toollab.eval.model.ToolResultMessage;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.trace.TraceRecord;
import dev.mcp.toollab.eval.validation.ConsistencyScorer;
import dev.mcp.toollab.eval.validation.TraceValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ToolCallingHarness {
    private final ObjectMapper mapper;
    private final ToolSchemaRegistry registry;
    private final TraceValidator traceValidator;
    private final ConsistencyScorer scorer;
    private final ToolExecutionClient toolExecutionClient;

    public ToolCallingHarness(
            ToolSchemaRegistry registry,
            TraceValidator traceValidator,
            ToolExecutionClient toolExecutionClient,
            ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.traceValidator = Objects.requireNonNull(traceValidator, "traceValidator");
        this.scorer = new ConsistencyScorer(traceValidator);
        this.toolExecutionClient = Objects.requireNonNull(toolExecutionClient, "toolExecutionClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public TraceRecord run(String runId, EvalTask task, ModelClient client) {
        ExpectedReplay expected = replayExpected(runId, task);
        ToolExecutionClient.ToolExecutionSession toolSession = toolExecutionClient.startTask(runId, task);
        String initialHash = toolSession.initialStateHash();
        ToolDependencyPlanner planner = new ToolDependencyPlanner(registry);

        List<ModelOutput> outputs = new ArrayList<>();
        ObjectNode root = mapper.createObjectNode();
        root.put("runId", runId);
        root.put("taskId", task.taskId());
        root.put("templateId", task.templateId());
        root.put("split", task.split());
        root.put("model", client.modelId());
        root.put("modelRevision", client.modelRevision());
        root.put("providerSchemaAdapter", client.providerSchemaAdapter());
        root.set("modelConfig", client.modelConfig());
        root.set("decoding", client.decodingConfig());
        root.put("toolSchemaVersion", registry.version());
        root.put("traceEquivalenceVersion", TraceValidator.TRACE_EQUIVALENCE_VERSION);
        root.put("stateCanonicalizationVersion", CanonicalJson.STATE_CANONICALIZATION_VERSION);
        root.put("seed", 7);
        root.put("temperature", client.decodingConfig().path("temperature").asDouble(0.0d));
        root.put("topP", client.decodingConfig().path("topP").asDouble(1.0d));
        root.put("doSample", client.decodingConfig().path("doSample").asBoolean(false));
        root.put("toolSchemasHash", registry.hash());
        root.put("toolExecutionMode", toolSession.mode());
        root.put("toolExecutionStateId", toolSession.stateId());
        root.put("initialStateHash", initialHash);
        root.set("initialState", toolSession.initialState());
        root.set("expectedTrace", traceValidator.firstAcceptedTrace(task).deepCopy());
        root.put("expectedFinalStateHash", expected.expectedFinalStateHash());
        root.set("expectedFinalState", expected.expectedFinalState());
        ArrayNode steps = root.putArray("steps");
        ArrayNode findings = root.putArray("findings");

        boolean schemaValid = true;
        boolean toolExecutionSuccess = true;
        boolean outputsFromCache = true;
        FinalResponse finalResponse = null;
        String completionStatus = "completed";
        boolean providerError = false;

        for (int i = 0; i < task.maxSteps(); i++) {
            ModelOutput output;
            try {
                output = client.next(task, outputs);
            } catch (ProviderResponseDecodeException e) {
                outputsFromCache = outputsFromCache && e.responseFromCache();
                completionStatus = "provider_decode_failed";
                finalResponse = new FinalResponse(
                        "cannot_complete",
                        "Provider response decode failed.",
                        null,
                        null);
                addDecodeFailureFinding(findings, client, e);
                break;
            } catch (RuntimeException e) {
                providerError = true;
                outputsFromCache = false;
                completionStatus = "provider_error";
                finalResponse = new FinalResponse(
                        "cannot_complete",
                        "Provider request failed.",
                        null,
                        null);
                findings.addObject()
                        .put("type", "provider_error")
                        .put("message", e.getMessage());
                break;
            }
            outputsFromCache = outputsFromCache && output.fromCache();

            if (output.hasToolCalls()) {
                ObjectNode step = steps.addObject();
                step.put("step", i + 1);
                step.put("modelOutput", output.rawText());
                step.put("modelOutputFromCache", output.fromCache());
                ArrayNode calls = step.putArray("toolCalls");
                List<ToolResultMessage> toolResults = new ArrayList<>();
                int serialIndex = 1;
                for (ToolCall call : planner.schedule(toolCalls(output))) {
                    ToolExecutionClient.ExecutionResult execution = toolSession.execute(call.name(), call.arguments());
                    if (!execution.validation().valid()) {
                        schemaValid = false;
                        ObjectNode finding = findings.addObject();
                        finding.put("type", "schema_validation");
                        finding.put("toolCallId", call.id());
                        finding.put("message", String.join("; ", execution.validation().errors()));
                    }
                    ToolCallResult toolResult = execution.result();
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("name", call.name());
                    callNode.set("arguments", call.arguments());
                    callNode.set("canonicalArguments", call.arguments());
                    callNode.put("schemaValid", execution.validation().valid());
                    callNode.put("dependencyGroup", planner.dependencyGroup(call, i + 1, serialIndex));
                    callNode.put("sideEffects", planner.sideEffects(call.name()));
                    callNode.put("startedAt", Instant.EPOCH.toString());
                    callNode.put("completedAt", Instant.EPOCH.plusMillis(1).toString());
                    callNode.put("success", toolResult.success());
                    if (toolResult.success()) {
                        callNode.set("result", toolResult.result());
                    } else {
                        callNode.put("errorCode", toolResult.errorCode());
                        callNode.put("message", toolResult.message());
                        toolExecutionSuccess = false;
                    }
                    toolResults.add(new ToolResultMessage(
                            call.id(),
                            call.name(),
                            toolResult.success(),
                            toolResultNode(toolResult)));
                    if (!planner.readOnly(call.name())) {
                        serialIndex++;
                    }
                }
                outputs.add(output.withToolResults(toolResults));
            } else {
                outputs.add(output);
            }

            if (output.hasFinalResponse()) {
                finalResponse = output.finalResponse();
                break;
            }
        }

        if (finalResponse == null && !providerError) {
            completionStatus = "max_steps_exceeded";
            finalResponse = new FinalResponse(
                    "cannot_complete",
                    "Maximum tool-calling steps exceeded.",
                    null,
                    null);
            findings.addObject()
                    .put("type", "max_step_failure")
                    .put("message", "Task exceeded maxSteps=" + task.maxSteps());
        }

        root.put("completionStatus", completionStatus);
        root.put("outputsFromCache", outputsFromCache);
        root.set("finalResponse", finalResponseNode(finalResponse));
        root.put("actualFinalStateHash", toolSession.finalStateHash());
        root.set("actualFinalState", toolSession.finalState());
        ObjectNode scores = root.putObject("scores");
        scores.put("schemaValid", schemaValid);
        scores.put("toolExecutionSuccess", toolExecutionSuccess);
        scores.put("toolExecutionPass", false);
        scores.put("toolSelectionPass", false);
        scores.put("parameterPass", schemaValid);
        scores.put("tracePass", false);
        scores.put("finalStatePass", false);
        scores.put("structuredResponsePass", false);
        scores.put("structuredGroundingPass", "notImplemented");
        scores.put("maxStepFailure", false);
        scores.put("overallPass", false);
        TraceRecord record = new TraceRecord(root);
        scorer.score(record, task);
        toolSession.close();
        return record;
    }

    private List<ToolCall> toolCalls(ModelOutput output) {
        return output.toolCalls() == null ? List.of() : output.toolCalls();
    }

    private ExpectedReplay replayExpected(String runId, EvalTask task) {
        // The first accepted trace remains the canonical expected-state path for this lab dataset.
        var accepted = traceValidator.firstAcceptedTrace(task);
        try (ToolExecutionClient.ToolExecutionSession expectedSession =
                toolExecutionClient.startTask(runId + "-expected", task)) {
            for (var step : accepted.path("steps")) {
                for (var call : step.path("toolCalls")) {
                    String toolName = call.path("name").asText();
                    ToolExecutionClient.ExecutionResult execution =
                            expectedSession.execute(toolName, call.path("arguments"));
                    if (!execution.validation().valid()) {
                        throw new IllegalStateException("Accepted trace failed schema validation for " + toolName
                                + ": " + String.join("; ", execution.validation().errors()));
                    }
                    ToolCallResult result = execution.result();
                    String expectedErrorCode = call.path("expectedErrorCode").asText(null);
                    if (expectedErrorCode != null && result.success()) {
                        throw new IllegalStateException(
                                "Accepted trace expected tool error but call succeeded: " + toolName);
                    }
                    if (!result.success() && !result.errorCode().equals(expectedErrorCode)) {
                        throw new IllegalStateException("Accepted trace failed: " + result.errorCode());
                    }
                }
            }
            return new ExpectedReplay(
                    expectedSession.finalStateHash(),
                    expectedSession.finalState());
        }
    }

    private void addDecodeFailureFinding(
            ArrayNode findings,
            ModelClient client,
            ProviderResponseDecodeException error) {
        String rawResponse = error.rawResponse();
        ObjectNode finding = findings.addObject();
        finding.put("type", "provider_decode_failed");
        finding.put("message", error.getMessage());
        finding.put("providerSchemaAdapter", firstNonBlank(error.providerSchemaAdapter(), client.providerSchemaAdapter()));
        finding.put("modelId", firstNonBlank(error.modelId(), client.modelId()));
        finding.put("modelRevision", firstNonBlank(error.modelRevision(), client.modelRevision()));
        finding.put("rawResponseLength", rawResponse == null ? 0 : rawResponse.length());
        finding.put("rawResponseEmpty", rawResponse == null || rawResponse.isEmpty());
        finding.put("rawResponseExcerpt", error.rawResponseExcerpt(2048));
        finding.put("responseFromCache", error.responseFromCache());
        if (error.cachePath() != null && !error.cachePath().isBlank()) {
            finding.put("cachePath", error.cachePath());
        }
        if (error.providerRequest() != null && !error.providerRequest().isMissingNode()) {
            finding.set("providerRequest", error.providerRequest());
        }
    }

    private String firstNonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private ObjectNode toolResultNode(ToolCallResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("toolName", result.toolName());
        node.put("success", result.success());
        if (result.success()) {
            node.set("result", result.result());
        } else {
            node.put("errorCode", result.errorCode());
            node.put("message", result.message());
        }
        return node;
    }

    private ObjectNode finalResponseNode(FinalResponse response) {
        ObjectNode node = mapper.createObjectNode();
        node.put("responseType", response.responseType());
        node.put("message", response.message());
        if (response.claims() != null && !response.claims().isMissingNode()) {
            node.set("claims", response.claims());
        } else {
            node.putArray("claims");
        }
        if (response.missingFields() != null && !response.missingFields().isMissingNode()) {
            node.set("missingFields", response.missingFields());
        }
        return node;
    }

    private record ExpectedReplay(
            String expectedFinalStateHash,
            com.fasterxml.jackson.databind.JsonNode expectedFinalState) {
    }
}
