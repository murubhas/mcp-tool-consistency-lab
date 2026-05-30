package dev.mcp.toollab.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.TreeMap;

public final class CanonicalJson {
    public static final String STATE_CANONICALIZATION_VERSION = "state-canon-v1";

    // Contract-side canonicalization is intentionally independent of any CDI-managed mapper.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalJson() {
    }

    public static String writeCanonical(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sort(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write canonical JSON", e);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sortedArray = MAPPER.createArrayNode();
            node.forEach(item -> sortedArray.add(sort(item)));
            return sortedArray;
        }
        ObjectNode sortedObject = MAPPER.createObjectNode();
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
            sortedObject.set(entry.getKey(), sort(entry.getValue()));
        }
        return sortedObject;
    }
}
