package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;

public record FinalResponse(String responseType, String message, JsonNode claims, JsonNode missingFields) {
}
