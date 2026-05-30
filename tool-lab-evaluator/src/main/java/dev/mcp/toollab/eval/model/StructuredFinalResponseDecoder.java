package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class StructuredFinalResponseDecoder {
    private StructuredFinalResponseDecoder() {
    }

    static FinalResponse decode(String content, ObjectMapper mapper) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Provider final response content is empty");
        }
        try {
            JsonNode node = mapper.readTree(normalized);
            String responseType = node.path("responseType").asText("final_answer");
            String message = node.path("message").asText(normalized);
            return new FinalResponse(responseType, message, node.get("claims"), node.get("missingFields"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Provider final response is not structured JSON", e);
        }
    }

    private static String normalize(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
