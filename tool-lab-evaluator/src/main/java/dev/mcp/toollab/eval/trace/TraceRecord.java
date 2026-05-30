package dev.mcp.toollab.eval.trace;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record TraceRecord(ObjectNode json) {
    public String taskId() {
        return json.path("taskId").asText();
    }

    public boolean score(String name) {
        return json.path("scores").path(name).asBoolean(false);
    }
}
