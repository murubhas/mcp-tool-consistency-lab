package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Optional;

public interface ModelResponseCache {
    Optional<CachedResponse> read(JsonNode cacheKey);

    void write(JsonNode cacheKey, String rawResponse);

    default void writeFailure(JsonNode cacheKey, String rawResponse, String decodeError) {
        write(cacheKey, rawResponse);
    }

    Path pathFor(JsonNode cacheKey);

    record CachedResponse(String rawResponse, Path path) {
    }
}
