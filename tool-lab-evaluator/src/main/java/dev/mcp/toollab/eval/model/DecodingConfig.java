package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;

public record DecodingConfig(double temperature, double topP, boolean doSample, int maxOutputTokens) {
    public static DecodingConfig deterministic(int maxOutputTokens) {
        return new DecodingConfig(0.0d, 1.0d, false, maxOutputTokens);
    }

    public JsonNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("temperature", temperature);
        node.put("topP", topP);
        node.put("doSample", doSample);
        node.put("maxOutputTokens", maxOutputTokens);
        return node;
    }

    public String cacheKey(ObjectMapper mapper) {
        return CanonicalJson.writeCanonical(toJson(mapper));
    }
}
