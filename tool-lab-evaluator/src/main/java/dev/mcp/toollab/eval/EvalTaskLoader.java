package dev.mcp.toollab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.contract.Hashing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class EvalTaskLoader {
    private static final String RESOURCE = "/eval-tasks.jsonl";
    private final ObjectMapper mapper;

    public EvalTaskLoader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public LoadedTasks loadMilestoneTasks() {
        try (InputStream stream = EvalTaskLoader.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            List<EvalTask> tasks = new ArrayList<>();
            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    raw.append(line).append('\n');
                    JsonNode node = mapper.readTree(line);
                    tasks.add(new EvalTask(
                            node.path("taskId").asText(),
                            node.path("templateId").asText(),
                            node.path("split").asText(),
                            node.path("domain").asText(),
                            node.path("category").asText(),
                            node.path("prompt").asText(),
                            node.path("initialStateProfile").asText(),
                            node.path("expectedResponseType").asText(),
                            node.path("maxSteps").asInt()));
                }
            }
            return new LoadedTasks(List.copyOf(tasks), Hashing.sha256(raw.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load eval tasks", e);
        }
    }

    public record LoadedTasks(List<EvalTask> tasks, String hash) {
    }
}
