package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class ToolSchemaRegistry {
    private static final String RESOURCE = "/schemas/tool-schemas.json";

    private final String version;
    private final String hash;
    private final Map<String, ToolDefinition> definitions;

    private ToolSchemaRegistry(String version, String hash, Map<String, ToolDefinition> definitions) {
        this.version = version;
        this.hash = hash;
        this.definitions = Collections.unmodifiableMap(new TreeMap<>(definitions));
    }

    public static ToolSchemaRegistry loadDefault(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        try (InputStream stream = ToolSchemaRegistry.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            JsonNode root = mapper.readTree(stream);
            TreeMap<String, ToolDefinition> definitions = new TreeMap<>();
            for (JsonNode toolNode : root.path("tools")) {
                JsonNode metadata = toolNode.path("x-tool-lab");
                ToolDefinition definition = new ToolDefinition(
                        toolNode.path("name").asText(),
                        toolNode.path("description").asText(),
                        toolNode.path("input_schema"),
                        metadata.path("sideEffects").asText(),
                        metadata.path("idempotent").asBoolean(),
                        metadata.path("retryPolicy").asText());
                definitions.put(definition.name(), definition);
            }
            String canonical = CanonicalJson.writeCanonical(root);
            return new ToolSchemaRegistry(
                    root.path("toolSchemaVersion").asText(),
                    Hashing.sha256(canonical),
                    definitions);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load tool schemas", e);
        }
    }

    public String version() {
        return version;
    }

    public String hash() {
        return hash;
    }

    public Collection<ToolDefinition> all() {
        return definitions.values();
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public ToolDefinition require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown tool schema: " + name));
    }
}
