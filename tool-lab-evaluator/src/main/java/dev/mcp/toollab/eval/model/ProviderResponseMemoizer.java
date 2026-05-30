package dev.mcp.toollab.eval.model;

import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Supplier;

@ApplicationScoped
@Unremovable
public class ProviderResponseMemoizer {
    public static final String CACHE_NAME = "tool-lab-provider-response-cache";

    @CacheResult(cacheName = CACHE_NAME)
    public String memoize(@CacheKey String canonicalCacheIdentity, Supplier<String> loader) {
        return loader.get();
    }
}
