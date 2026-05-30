package dev.mcp.toollab.eval.model;

import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderResponseMemoizerTest {
    @Test
    void memoizerUsesStableQuarkusCacheNameAndExplicitCacheKey() throws Exception {
        var method = ProviderResponseMemoizer.class.getMethod("memoize", String.class, Supplier.class);

        assertEquals(
                ProviderResponseMemoizer.CACHE_NAME,
                method.getAnnotation(CacheResult.class).cacheName());
        assertTrue(method.getParameters()[0].isAnnotationPresent(CacheKey.class));
    }
}
