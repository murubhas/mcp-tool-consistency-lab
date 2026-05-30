package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.eval.EvalTask;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MockModelClient implements ModelClient {
    private static final String RESOURCE = "/mock-model-outputs/milestone-1.json";

    private final Map<String, List<ModelOutput>> outputs;
    private final ObjectMapper mapper;
    private final DecodingConfig decodingConfig = DecodingConfig.deterministic(1024);
    private final ToolLabPrompt prompt;

    public MockModelClient(ObjectMapper mapper) {
        this(new ToolLabPromptCatalog().resolve(ToolLabPromptCatalog.DEFAULT_VARIANT), mapper);
    }

    public MockModelClient(ToolLabPrompt prompt, ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.prompt = prompt == null
                ? new ToolLabPromptCatalog().resolve(ToolLabPromptCatalog.DEFAULT_VARIANT)
                : prompt;
        this.outputs = load();
    }

    @Override
    public String modelId() {
        return "mock-dry-run-model";
    }

    @Override
    public String modelRevision() {
        return "milestone-1-fixture";
    }

    @Override
    public String providerSchemaAdapter() {
        return "canonical-mock-v1";
    }

    @Override
    public JsonNode modelConfig() {
        ObjectNode node = mapper.createObjectNode();
        node.put("servingArtifactUri", "local-fixture");
        node.put("outputsFromCache", true);
        node.put("promptVariant", prompt.variant());
        node.put("promptHash", prompt.hash());
        node.put("promptSource", prompt.source());
        return node;
    }

    @Override
    public JsonNode decodingConfig() {
        return decodingConfig.toJson(mapper);
    }

    @Override
    public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
        List<ModelOutput> taskOutputs = outputs.get(task.taskId());
        if (taskOutputs == null || priorOutputs.size() >= taskOutputs.size()) {
            return new ModelOutput(
                    "{\"responseType\":\"cannot_complete\",\"message\":\"mock output exhausted\"}",
                    List.of(),
                    new FinalResponse("cannot_complete", "mock output exhausted", null, null))
                    .withFromCache(true);
        }
        return taskOutputs.get(priorOutputs.size());
    }

    private Map<String, List<ModelOutput>> load() {
        try (InputStream stream = MockModelClient.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            JsonNode root = mapper.readTree(stream);
            Map<String, List<ModelOutput>> byTask = new HashMap<>();
            for (JsonNode taskNode : root.path("tasks")) {
                List<ModelOutput> taskOutputs = new ArrayList<>();
                for (JsonNode outputNode : taskNode.path("outputs")) {
                    List<ToolCall> calls = new ArrayList<>();
                    for (JsonNode callNode : outputNode.path("toolCalls")) {
                        calls.add(new ToolCall(
                                callNode.path("id").asText(),
                                callNode.path("name").asText(),
                                callNode.path("arguments")));
                    }
                    FinalResponse finalResponse = null;
                    if (outputNode.has("finalResponse")) {
                        JsonNode finalNode = outputNode.path("finalResponse");
                        finalResponse = new FinalResponse(
                                finalNode.path("responseType").asText(),
                                finalNode.path("message").asText(),
                                finalNode.get("claims"),
                                finalNode.get("missingFields"));
                    }
                    taskOutputs.add(new ModelOutput(outputNode.toString(), calls, finalResponse).withFromCache(true));
                }
                byTask.put(taskNode.path("taskId").asText(), List.copyOf(taskOutputs));
            }
            return Map.copyOf(byTask);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load mock outputs", e);
        }
    }
}
