package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolResultMessage(String toolCallId, String toolName, boolean success, JsonNode content) {
}
