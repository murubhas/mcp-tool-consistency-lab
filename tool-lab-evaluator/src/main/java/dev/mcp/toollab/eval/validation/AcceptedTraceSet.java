package dev.mcp.toollab.eval.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.contract.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class AcceptedTraceSet {
    private static final String RESOURCE = "/accepted-traces/compute-v1.accepted-traces.json";

    private final JsonNode root;
    private final String hash;
    private final Map<String, JsonNode> byTask;

    private AcceptedTraceSet(JsonNode root, String hash, Map<String, JsonNode> byTask) {
        this.root = root;
        this.hash = hash;
        this.byTask = Map.copyOf(byTask);
    }

    public static AcceptedTraceSet loadDefault(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        try (InputStream stream = AcceptedTraceSet.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            JsonNode root = mapper.readTree(stream);
            Map<String, JsonNode> byTask = new HashMap<>();
            for (JsonNode task : root.path("tasks")) {
                byTask.put(task.path("taskId").asText(), task.path("acceptedTraces"));
            }
            return new AcceptedTraceSet(root, Hashing.sha256(root.toString()), byTask);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load accepted traces", e);
        }
    }

    public JsonNode root() {
        return root;
    }

    public String hash() {
        return hash;
    }

    public JsonNode acceptedTraces(String taskId) {
        JsonNode traces = byTask.get(taskId);
        if (traces == null) {
            throw new IllegalArgumentException("No accepted trace for task " + taskId);
        }
        return traces;
    }
}
