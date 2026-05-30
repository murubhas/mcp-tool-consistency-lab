package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void liveResponsesAreCachedByTaskModelSchemaAndDecoding() throws Exception {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        AtomicInteger calls = new AtomicInteger();
        QwenOpenAiCompatibleClient live = QwenOpenAiCompatibleClient.builder()
                .endpoint(URI.create("http://localhost:8000/v1/chat/completions"))
                .modelId("Qwen/Qwen3.6-27B")
                .servedModelName("Qwen/Qwen3.6-27B")
                .modelRevision("test")
                .registry(registry)
                .decodingConfig(DecodingConfig.deterministic(1024))
                .completionClient(body -> {
                    calls.incrementAndGet();
                    return """
                            {"choices":[{"message":{"content":"{\\"responseType\\":\\"final_answer\\",\\"message\\":\\"cached\\",\\"claims\\":[]}"}}]}
                            """;
                })
                .mapper(mapper)
                .build();
        EvalTask task = new EvalTask(
                "task-cache",
                "template",
                "eval",
                "compute",
                "single_tool",
                "Answer directly.",
                "default",
                "final_answer",
                2);

        CachedModelClient first = cached(live, registry);
        ModelOutput miss = first.next(task, List.of());
        JsonNode cacheKey = first.cacheKey(task, List.of());
        JsonNode cacheRecord = mapper.readTree(new FileModelResponseCache(tempDir, mapper).pathFor(cacheKey).toFile());

        CachedModelClient second = cached(live, registry);
        ModelOutput hit = second.next(task, List.of());

        assertFalse(miss.fromCache());
        assertTrue(hit.fromCache());
        assertTrue(calls.get() == 1);
        assertTrue(hit.rawText().contains("\"choices\""));
        assertEquals(cacheKey.path("providerRequest"), cacheRecord.path("providerRequest"));
        assertEquals(CanonicalJson.writeCanonical(cacheKey.path("providerRequest")),
                cacheRecord.path("providerRequestCanonicalJson").asText());
        assertTrue(cacheRecord.path("rawResponse").asText().contains("\"choices\""));
        assertEquals(cacheKey.path("providerRequest"), cacheRecord.path("cacheKey").path("providerRequest"));
    }

    @Test
    void cacheKeyChangesAcrossBenchmarkIdentityInputs() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        EvalTask task = task("task-cache", "Answer directly.");
        QwenOpenAiCompatibleClient baseline = qwen(registry, "revision-a", DecodingConfig.deterministic(1024));
        CachedModelClient cached = cached(baseline, registry);
        JsonNode baseKey = cached.cacheKey(task, List.of());
        String base = canonical(baseKey);

        assertTrue(baseKey.has("providerRequest"));
        assertEquals(baseline.modelConfig(), baseKey.path("modelConfig"));
        assertEquals(
                "http://localhost:8000/v1/chat/completions",
                baseKey.path("modelConfig").path("endpoint").asText());
        assertEquals("baseline", baseKey.path("promptVariant").asText());
        assertTrue(baseKey.path("promptHash").asText().startsWith("sha256:"));
        assertEquals("catalog", baseKey.path("promptSource").asText());
        assertNotEquals(base, canonical(cached.cacheKey(task("task-cache", "Different prompt."), List.of())));
        assertNotEquals(base, canonical(cacheKey(
                qwen(registry, "revision-a", DecodingConfig.deterministic(1024), "http://localhost:9000/v1/chat/completions"),
                registry,
                task)));
        assertNotEquals(base, canonical(cacheKey(
                qwen(registry, "revision-b", DecodingConfig.deterministic(1024)),
                registry,
                task)));
        assertNotEquals(base, canonical(cacheKey(
                qwen(registry, "revision-a", DecodingConfig.deterministic(2048)),
                registry,
                task)));
        assertNotEquals(
                canonical(cacheKey(
                        qwen(
                                registry,
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "http://localhost:8000/v1/chat/completions",
                                false),
                        registry,
                        task)),
                canonical(cacheKey(
                        qwen(
                                registry,
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "http://localhost:8000/v1/chat/completions",
                                true),
                        registry,
                        task)));
        assertNotEquals(
                canonical(cacheKey(
                        qwen(
                                registry,
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "http://localhost:8000/v1/chat/completions",
                                false,
                                false),
                        registry,
                        task)),
                canonical(cacheKey(
                        qwen(
                                registry,
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "http://localhost:8000/v1/chat/completions",
                                false,
                                true),
                        registry,
                        task)));
        assertNotEquals(
                canonical(cacheKey(
                        qwen(
                                registry,
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "http://localhost:8000/v1/chat/completions",
                                new ToolLabPromptCatalog().resolve("baseline"),
                                false,
                                false),
                        registry,
                        task)),
                canonical(cacheKey(
                        qwen(
                                registry,
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "http://localhost:8000/v1/chat/completions",
                                new ToolLabPromptCatalog().resolve("refined-v1"),
                                false,
                                false),
                        registry,
                        task)));
        assertNotEquals(base, canonical(cacheKey(
                new StubCacheableClient(
                        "provider-a",
                        "revision-a",
                        DecodingConfig.deterministic(1024),
                        "shape-a"),
                registry,
                task)));
        assertNotEquals(
                canonical(cacheKey(
                        new StubCacheableClient(
                                "provider-a",
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "shape-a"),
                        registry,
                        task)),
                canonical(cacheKey(
                        new StubCacheableClient(
                                "provider-a",
                                "revision-a",
                                DecodingConfig.deterministic(1024),
                                "shape-b"),
                        registry,
                        task)));
        assertNotEquals(
                canonical(cacheKey(
                        sonnet(registry, "us-east-1", "https://bedrock-runtime.us-east-1.amazonaws.com"),
                        registry,
                        task)),
                canonical(cacheKey(
                        sonnet(registry, "us-west-2", "https://bedrock-runtime.us-west-2.amazonaws.com"),
                        registry,
                        task)));
        assertNotEquals(
                canonical(cached.cacheKey(task, List.of(priorOutput("ok")))),
                canonical(cached.cacheKey(task, List.of(priorOutput("different")))));
    }

    @Test
    void failedRawResponsesAreWrittenToFileCache() throws Exception {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        String rawResponse = """
                {"choices":[{"message":{"role":"assistant","content":null}}]}
                """;
        QwenOpenAiCompatibleClient live = QwenOpenAiCompatibleClient.builder()
                .endpoint(URI.create("http://localhost:8000/v1/chat/completions"))
                .modelId("Qwen/Qwen3.6-27B")
                .servedModelName("Qwen/Qwen3.6-27B")
                .modelRevision("test")
                .registry(registry)
                .decodingConfig(DecodingConfig.deterministic(1024))
                .completionClient(body -> rawResponse)
                .mapper(mapper)
                .build();
        CachedModelClient cached = cachedBuilder(live, registry)
                .refreshCache(true)
                .build();

        ProviderResponseDecodeException error = assertThrows(
                ProviderResponseDecodeException.class,
                () -> cached.next(task("task-cache", "Answer directly."), List.of()));

        assertTrue(error.cachePath().endsWith(".json"));
        assertTrue(Files.exists(Path.of(error.cachePath())));
        JsonNode cacheRecord = mapper.readTree(Path.of(error.cachePath()).toFile());
        assertEquals("failed", cacheRecord.path("decodeStatus").asText());
        assertEquals("Provider final response content is empty", cacheRecord.path("decodeError").asText());
        assertEquals(rawResponse, cacheRecord.path("rawResponse").asText());
        JsonNode cacheKey = cached.cacheKey(task("task-cache", "Answer directly."), List.of());
        assertEquals(cacheKey.path("providerRequest"), cacheRecord.path("providerRequest"));
        assertEquals(CanonicalJson.writeCanonical(cacheKey.path("providerRequest")),
                cacheRecord.path("providerRequestCanonicalJson").asText());
    }

    @Test
    void legacyCacheFilesWithoutTopLevelProviderRequestStillReplay() throws Exception {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        AtomicInteger calls = new AtomicInteger();
        QwenOpenAiCompatibleClient live = QwenOpenAiCompatibleClient.builder()
                .endpoint(URI.create("http://localhost:8000/v1/chat/completions"))
                .modelId("Qwen/Qwen3.6-27B")
                .servedModelName("Qwen/Qwen3.6-27B")
                .modelRevision("test")
                .registry(registry)
                .decodingConfig(DecodingConfig.deterministic(1024))
                .completionClient(body -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Provider should not be called on cache hit");
                })
                .mapper(mapper)
                .build();
        EvalTask task = task("task-cache", "Answer directly.");
        FileModelResponseCache fileCache = new FileModelResponseCache(tempDir, mapper);
        CachedModelClient cached = cachedBuilder(live, registry)
                .cache(fileCache)
                .build();
        JsonNode cacheKey = cached.cacheKey(task, List.of());
        String rawResponse = """
                {"choices":[{"message":{"content":"{\\"responseType\\":\\"final_answer\\",\\"message\\":\\"legacy\\",\\"claims\\":[]}"}}]}
                """;
        ObjectNode legacyRecord = mapper.createObjectNode();
        legacyRecord.put("createdAt", "1970-01-01T00:00:00Z");
        legacyRecord.put("updatedAt", "1970-01-01T00:00:00Z");
        legacyRecord.put("decodeStatus", "success");
        legacyRecord.set("cacheKey", cacheKey);
        legacyRecord.put("rawResponse", rawResponse);
        Files.createDirectories(fileCache.pathFor(cacheKey).getParent());
        Files.writeString(fileCache.pathFor(cacheKey), mapper.writeValueAsString(legacyRecord));

        ModelOutput output = cached.next(task, List.of());

        assertTrue(output.fromCache());
        assertTrue(output.rawText().contains("legacy"));
        assertEquals(0, calls.get());
    }

    private QwenOpenAiCompatibleClient qwen(
            ToolSchemaRegistry registry,
            String modelRevision,
            DecodingConfig decodingConfig) {
        return qwen(registry, modelRevision, decodingConfig, "http://localhost:8000/v1/chat/completions");
    }

    private QwenOpenAiCompatibleClient qwen(
            ToolSchemaRegistry registry,
            String modelRevision,
            DecodingConfig decodingConfig,
            String endpoint) {
        return qwen(registry, modelRevision, decodingConfig, endpoint, null, null);
    }

    private QwenOpenAiCompatibleClient qwen(
            ToolSchemaRegistry registry,
            String modelRevision,
            DecodingConfig decodingConfig,
            String endpoint,
            Boolean enableThinking) {
        return qwen(registry, modelRevision, decodingConfig, endpoint, enableThinking, null);
    }

    private QwenOpenAiCompatibleClient qwen(
            ToolSchemaRegistry registry,
            String modelRevision,
            DecodingConfig decodingConfig,
            String endpoint,
            Boolean enableThinking,
            Boolean preserveThinking) {
        return qwen(registry, modelRevision, decodingConfig, endpoint, null, enableThinking, preserveThinking);
    }

    private QwenOpenAiCompatibleClient qwen(
            ToolSchemaRegistry registry,
            String modelRevision,
            DecodingConfig decodingConfig,
            String endpoint,
            ToolLabPrompt prompt,
            Boolean enableThinking,
            Boolean preserveThinking) {
        return QwenOpenAiCompatibleClient.builder()
                .endpoint(URI.create(endpoint))
                .modelId("Qwen/Qwen3.6-27B")
                .servedModelName("Qwen/Qwen3.6-27B")
                .modelRevision(modelRevision)
                .registry(registry)
                .decodingConfig(decodingConfig)
                .completionClient(body -> "{}")
                .mapper(mapper)
                .prompt(prompt)
                .enableThinking(enableThinking)
                .preserveThinking(preserveThinking)
                .build();
    }

    private SonnetBedrockClient sonnet(ToolSchemaRegistry registry, String region, String endpoint) {
        return new SonnetBedrockClient(
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                "revision-a",
                region,
                endpoint,
                registry,
                DecodingConfig.deterministic(1024),
                (modelId, body) -> "{}",
                mapper);
    }

    private EvalTask task(String taskId, String prompt) {
        return new EvalTask(
                taskId,
                "template",
                "eval",
                "compute",
                "single_tool",
                prompt,
                "default",
                "final_answer",
                2);
    }

    private ModelOutput priorOutput(String message) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("instanceType", "g6e.xlarge");
        ObjectNode result = mapper.createObjectNode();
        result.put("message", message);
        return new ModelOutput(
                "{}",
                List.of(new ToolCall("call-1", "get_instance_spec", arguments)),
                null,
                List.of(new ToolResultMessage("call-1", "get_instance_spec", true, result)),
                false);
    }

    private static String canonical(JsonNode node) {
        return CanonicalJson.writeCanonical(node);
    }

    private CachedModelClient cached(CacheableModelClient delegate, ToolSchemaRegistry registry) {
        return cachedBuilder(delegate, registry).build();
    }

    private CachedModelClient.Builder cachedBuilder(CacheableModelClient delegate, ToolSchemaRegistry registry) {
        return CachedModelClient.builder()
                .delegate(delegate)
                .registry(registry)
                .cacheRoot(tempDir)
                .mapper(mapper);
    }

    private JsonNode cacheKey(CacheableModelClient delegate, ToolSchemaRegistry registry, EvalTask task) {
        return cached(delegate, registry).cacheKey(task, List.of());
    }

    private final class StubCacheableClient implements CacheableModelClient {
        private final String adapter;
        private final String revision;
        private final DecodingConfig decoding;
        private final String shape;

        private StubCacheableClient(String adapter, String revision, DecodingConfig decoding, String shape) {
            this.adapter = adapter;
            this.revision = revision;
            this.decoding = decoding;
            this.shape = shape;
        }

        @Override
        public String modelId() {
            return "stub-model";
        }

        @Override
        public String modelRevision() {
            return revision;
        }

        @Override
        public String providerSchemaAdapter() {
            return adapter;
        }

        @Override
        public JsonNode modelConfig() {
            ObjectNode node = mapper.createObjectNode();
            node.put("shape", shape);
            return node;
        }

        @Override
        public JsonNode decodingConfig() {
            return decoding.toJson(mapper);
        }

        @Override
        public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String rawProviderResponse(EvalTask task, List<ModelOutput> priorOutputs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode providerRequest(EvalTask task, List<ModelOutput> priorOutputs) {
            ObjectNode node = mapper.createObjectNode();
            node.put("prompt", task.prompt());
            return node;
        }

        @Override
        public ModelOutput decodeCachedResponse(String rawResponse) {
            throw new UnsupportedOperationException();
        }
    }
}
