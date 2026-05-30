package dev.mcp.toollab.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static JsonNode arg(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
