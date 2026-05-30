package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public final class AnthropicSchemaAdapter implements ProviderSchemaAdapter {
    private final ObjectMapper mapper;

    public AnthropicSchemaAdapter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String name() {
        return "anthropic-v1";
    }

    @Override
    public JsonNode adapt(ToolSchemaRegistry registry) {
        ArrayNode tools = mapper.createArrayNode();
        for (ToolDefinition definition : registry.all()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.set("input_schema", definition.inputSchema());
        }
        return tools;
    }
}
