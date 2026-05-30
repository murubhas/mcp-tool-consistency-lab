package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        String sideEffects,
        boolean idempotent,
        String retryPolicy) {

    public boolean readOnly() {
        return "read_only".equals(sideEffects);
    }
}
