package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CachedModelClient implements ModelClient {
    private final ObjectMapper mapper;
    private final CacheableModelClient delegate;
    private final ToolSchemaRegistry registry;
    private final ModelResponseCache cache;
    private final ProviderResponseMemoizer memoizer;
    private final boolean refreshCache;

    public static Builder builder() {
        return new Builder();
    }

    private CachedModelClient(
            CacheableModelClient delegate,
            ToolSchemaRegistry registry,
            ModelResponseCache cache,
            ProviderResponseMemoizer memoizer,
            ObjectMapper mapper,
            boolean refreshCache) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.memoizer = memoizer;
        this.refreshCache = refreshCache;
    }

    public static final class Builder {
        private CacheableModelClient delegate;
        private ToolSchemaRegistry registry;
        private Path cacheRoot;
        private ModelResponseCache cache;
        private ProviderResponseMemoizer memoizer;
        private ObjectMapper mapper;
        private boolean refreshCache;

        private Builder() {
        }

        public Builder delegate(CacheableModelClient delegate) {
            this.delegate = delegate;
            return this;
        }

        public Builder registry(ToolSchemaRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder cacheRoot(Path cacheRoot) {
            this.cacheRoot = cacheRoot;
            return this;
        }

        public Builder cache(ModelResponseCache cache) {
            this.cache = cache;
            return this;
        }

        public Builder memoizer(ProviderResponseMemoizer memoizer) {
            this.memoizer = memoizer;
            return this;
        }

        public Builder mapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public Builder refreshCache(boolean refreshCache) {
            this.refreshCache = refreshCache;
            return this;
        }

        public CachedModelClient build() {
            ObjectMapper effectiveMapper = Objects.requireNonNull(mapper, "mapper");
            ModelResponseCache effectiveCache = cache == null
                    ? new FileModelResponseCache(Objects.requireNonNull(cacheRoot, "cacheRoot"), effectiveMapper)
                    : cache;
            return new CachedModelClient(
                    Objects.requireNonNull(delegate, "delegate"),
                    Objects.requireNonNull(registry, "registry"),
                    effectiveCache,
                    memoizer,
                    effectiveMapper,
                    refreshCache);
        }
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public String modelRevision() {
        return delegate.modelRevision();
    }

    @Override
    public String providerSchemaAdapter() {
        return delegate.providerSchemaAdapter();
    }

    @Override
    public JsonNode modelConfig() {
        ObjectNode node = delegate.modelConfig().deepCopy();
        node.put("cacheType", cache.getClass().getSimpleName());
        node.put("refreshCache", refreshCache);
        return node;
    }

    @Override
    public JsonNode decodingConfig() {
        return delegate.decodingConfig();
    }

    @Override
    public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
        JsonNode key = cacheKey(task, priorOutputs);
        JsonNode providerRequest = key.path("providerRequest");
        if (!refreshCache) {
            var cached = cache.read(key);
            if (cached.isPresent()) {
                try {
                    return delegate.decodeCachedResponse(cached.get().rawResponse()).withFromCache(true);
                } catch (ProviderResponseDecodeException e) {
                    throw e.withProviderRequest(providerRequest).withCachePath(cached.get().path(), true);
                }
            }
        }

        String rawResponse;
        boolean fromRuntimeCache = false;
        if (refreshCache || memoizer == null) {
            rawResponse = delegate.rawProviderResponse(task, priorOutputs);
        } else {
            AtomicBoolean providerCalled = new AtomicBoolean(false);
            rawResponse = memoizer.memoize(CanonicalJson.writeCanonical(key), () -> {
                providerCalled.set(true);
                return delegate.rawProviderResponse(task, priorOutputs);
            });
            fromRuntimeCache = !providerCalled.get();
        }

        try {
            ModelOutput output = delegate.decodeCachedResponse(rawResponse).withFromCache(fromRuntimeCache);
            cache.write(key, rawResponse);
            return output;
        } catch (ProviderResponseDecodeException e) {
            cache.writeFailure(key, rawResponse, e.getMessage());
            throw e.withProviderRequest(providerRequest).withCachePath(cache.pathFor(key), fromRuntimeCache);
        }
    }

    public JsonNode cacheKey(EvalTask task, List<ModelOutput> priorOutputs) {
        ObjectNode node = mapper.createObjectNode();
        node.put("taskId", task.taskId());
        node.put("modelId", delegate.modelId());
        node.put("modelRevision", delegate.modelRevision());
        node.put("providerSchemaAdapter", delegate.providerSchemaAdapter());
        JsonNode modelConfig = delegate.modelConfig().deepCopy();
        node.set("modelConfig", modelConfig);
        node.put("promptVariant", modelConfig.path("promptVariant").asText("not-configured"));
        node.put("promptHash", modelConfig.path("promptHash").asText("not-configured"));
        node.put("promptSource", modelConfig.path("promptSource").asText("not-configured"));
        node.put("toolSchemasHash", registry.hash());
        node.set("decoding", delegate.decodingConfig());
        node.set("providerRequest", delegate.providerRequest(task, priorOutputs));
        node.set("priorToolResultContext", priorToolResults(priorOutputs));
        return node;
    }

    private ArrayNode priorToolResults(List<ModelOutput> priorOutputs) {
        ArrayNode context = mapper.createArrayNode();
        for (ModelOutput output : priorOutputs) {
            ArrayNode results = mapper.createArrayNode();
            for (ToolResultMessage result : output.toolResults()) {
                ObjectNode resultNode = mapper.createObjectNode();
                resultNode.put("toolCallId", result.toolCallId());
                resultNode.put("toolName", result.toolName());
                resultNode.put("success", result.success());
                resultNode.set("content", result.content());
                results.add(resultNode);
            }
            context.add(results);
        }
        return context;
    }
}
