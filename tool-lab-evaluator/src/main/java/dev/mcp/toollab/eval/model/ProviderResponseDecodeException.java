package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

public final class ProviderResponseDecodeException extends RuntimeException {
    private final String rawResponse;
    private final JsonNode providerRequest;
    private final String providerSchemaAdapter;
    private final String modelId;
    private final String modelRevision;
    private final String cachePath;
    private final boolean responseFromCache;

    public ProviderResponseDecodeException(
            String message,
            String rawResponse,
            JsonNode providerRequest,
            String providerSchemaAdapter,
            String modelId,
            String modelRevision,
            Throwable cause) {
        this(message, rawResponse, providerRequest, providerSchemaAdapter, modelId, modelRevision, null, false, cause);
    }

    private ProviderResponseDecodeException(
            String message,
            String rawResponse,
            JsonNode providerRequest,
            String providerSchemaAdapter,
            String modelId,
            String modelRevision,
            String cachePath,
            boolean responseFromCache,
            Throwable cause) {
        super(message, cause);
        this.rawResponse = rawResponse;
        this.providerRequest = providerRequest;
        this.providerSchemaAdapter = providerSchemaAdapter;
        this.modelId = modelId;
        this.modelRevision = modelRevision;
        this.cachePath = cachePath;
        this.responseFromCache = responseFromCache;
    }

    public String rawResponse() {
        return rawResponse;
    }

    public JsonNode providerRequest() {
        return providerRequest;
    }

    public String providerSchemaAdapter() {
        return providerSchemaAdapter;
    }

    public String modelId() {
        return modelId;
    }

    public String modelRevision() {
        return modelRevision;
    }

    public String cachePath() {
        return cachePath;
    }

    public boolean responseFromCache() {
        return responseFromCache;
    }

    public ProviderResponseDecodeException withProviderRequest(JsonNode request) {
        return new ProviderResponseDecodeException(
                getMessage(),
                rawResponse,
                request,
                providerSchemaAdapter,
                modelId,
                modelRevision,
                cachePath,
                responseFromCache,
                getCause());
    }

    public ProviderResponseDecodeException withCachePath(Path path, boolean fromCache) {
        return new ProviderResponseDecodeException(
                getMessage(),
                rawResponse,
                providerRequest,
                providerSchemaAdapter,
                modelId,
                modelRevision,
                path == null ? null : path.toString(),
                fromCache,
                getCause());
    }

    public String rawResponseExcerpt(int maxChars) {
        if (rawResponse == null) {
            return "";
        }
        if (rawResponse.length() <= maxChars) {
            return rawResponse;
        }
        return rawResponse.substring(0, maxChars);
    }
}
