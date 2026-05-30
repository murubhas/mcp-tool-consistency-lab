package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class FileModelResponseCache implements ModelResponseCache {
    private final ObjectMapper mapper;
    private final Path cacheRoot;

    public FileModelResponseCache(Path cacheRoot, ObjectMapper mapper) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<CachedResponse> read(JsonNode cacheKey) {
        Path path = pathFor(cacheKey);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(path.toFile());
            return Optional.of(new CachedResponse(root.path("rawResponse").asText(), path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read model response cache " + path, e);
        }
    }

    @Override
    public void write(JsonNode cacheKey, String rawResponse) {
        write(cacheKey, rawResponse, "success", null);
    }

    @Override
    public void writeFailure(JsonNode cacheKey, String rawResponse, String decodeError) {
        write(cacheKey, rawResponse, "failed", decodeError);
    }

    private void write(JsonNode cacheKey, String rawResponse, String decodeStatus, String decodeError) {
        Path path = pathFor(cacheKey);
        ObjectNode root = mapper.createObjectNode();
        root.put("createdAt", Instant.EPOCH.toString());
        root.put("updatedAt", Instant.now().toString());
        root.put("decodeStatus", decodeStatus);
        if (decodeError != null && !decodeError.isBlank()) {
            root.put("decodeError", decodeError);
        }
        root.set("cacheKey", cacheKey);
        JsonNode providerRequest = cacheKey.path("providerRequest");
        if (!providerRequest.isMissingNode()) {
            root.set("providerRequest", providerRequest);
            root.put("providerRequestCanonicalJson", CanonicalJson.writeCanonical(providerRequest));
        }
        root.put("rawResponse", rawResponse);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write model response cache " + path, e);
        }
    }

    @Override
    public Path pathFor(JsonNode cacheKey) {
        String hash = Hashing.shortSha256(CanonicalJson.writeCanonical(cacheKey), 32);
        return cacheRoot.resolve(hash + ".json");
    }
}
