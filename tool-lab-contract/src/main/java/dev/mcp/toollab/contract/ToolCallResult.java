package dev.mcp.toollab.contract;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCallResult(
        String toolName,
        boolean success,
        String errorCode,
        String message,
        JsonNode result) {

    public static ToolCallResult success(String toolName, JsonNode result) {
        return new ToolCallResult(toolName, true, null, null, result);
    }

    public static ToolCallResult failure(String toolName, String errorCode, String message) {
        return new ToolCallResult(toolName, false, errorCode, message, null);
    }
}
