package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderSchemaAdapter {
    String name();

    JsonNode adapt(ToolSchemaRegistry registry);
}
