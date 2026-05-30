package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public final class OpenAiCompatibleSchemaAdapter implements ProviderSchemaAdapter {
    private final ObjectMapper mapper;

    public OpenAiCompatibleSchemaAdapter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String name() {
        return "openai-compatible-v1";
    }

    @Override
    public JsonNode adapt(ToolSchemaRegistry registry) {
        ArrayNode tools = mapper.createArrayNode();
        for (ToolDefinition definition : registry.all()) {
            ObjectNode function = mapper.createObjectNode();
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.inputSchema());
            ObjectNode wrapper = tools.addObject();
            wrapper.put("type", "function");
            wrapper.set("function", function);
        }
        return tools;
    }
}
