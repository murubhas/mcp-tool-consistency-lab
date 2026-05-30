package dev.mcp.toollab.server;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.MetaKey;

final class McpStateIds {
    static final String DEFAULT_MCP_STATE_ID = "mcp-default";
    static final String META_STATE_ID = "toolLabStateId";
    static final String META_TASK_ID = "toolLabTaskId";

    private McpStateIds() {
    }

    static String stateId(Meta meta, McpConnection connection) {
        String stateId = metaValue(meta, META_STATE_ID);
        if (stateId == null || stateId.isBlank()) {
            return connection == null ? DEFAULT_MCP_STATE_ID : "mcp-session-" + connection.id();
        }
        return stateId;
    }

    static String taskId(Meta meta, String stateId) {
        String taskId = metaValue(meta, META_TASK_ID);
        return taskId == null || taskId.isBlank() ? stateId : taskId;
    }

    static String metaValue(Meta meta, String key) {
        if (meta == null) {
            return null;
        }
        Object value = meta.getValue(MetaKey.of(key));
        return value == null ? null : value.toString();
    }
}
