package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.OpenAiCompatibleSchemaAdapter;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class QwenOpenAiCompatibleClient implements CacheableModelClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String modelId;
    private final String servedModelName;
    private final String modelRevision;
    private final ToolSchemaRegistry registry;
    private final DecodingConfig decodingConfig;
    private final QwenCompletionClient completionClient;
    private final OpenAiCompatibleSchemaAdapter schemaAdapter;
    private final ToolLabPrompt prompt;
    private final Boolean enableThinking;
    private final Boolean preserveThinking;

    public static Builder builder() {
        return new Builder();
    }

    QwenOpenAiCompatibleClient(
            URI endpoint,
            String apiKey,
            String modelId,
            String servedModelName,
            String modelRevision,
            ToolSchemaRegistry registry,
            DecodingConfig decodingConfig,
            QwenCompletionClient completionClient,
            ObjectMapper mapper,
            ToolLabPrompt prompt,
            Boolean enableThinking,
            Boolean preserveThinking) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = apiKey;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.servedModelName = Objects.requireNonNull(servedModelName, "servedModelName");
        this.modelRevision = Objects.requireNonNull(modelRevision, "modelRevision");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.decodingConfig = Objects.requireNonNull(decodingConfig, "decodingConfig");
        this.completionClient = Objects.requireNonNull(completionClient, "completionClient");
        this.prompt = prompt == null ? baselinePrompt() : prompt;
        this.schemaAdapter = new OpenAiCompatibleSchemaAdapter(mapper);
        this.enableThinking = enableThinking;
        this.preserveThinking = preserveThinking;
    }

    public static final class Builder {
        private URI endpoint;
        private String apiKey;
        private String modelId;
        private String servedModelName;
        private String modelRevision;
        private ToolSchemaRegistry registry;
        private DecodingConfig decodingConfig;
        private QwenCompletionClient completionClient;
        private ObjectMapper mapper;
        private ToolLabPrompt prompt;
        private Boolean enableThinking;
        private Boolean preserveThinking;

        private Builder() {
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder servedModelName(String servedModelName) {
            this.servedModelName = servedModelName;
            return this;
        }

        public Builder modelRevision(String modelRevision) {
            this.modelRevision = modelRevision;
            return this;
        }

        public Builder registry(ToolSchemaRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder decodingConfig(DecodingConfig decodingConfig) {
            this.decodingConfig = decodingConfig;
            return this;
        }

        public Builder mapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public Builder prompt(ToolLabPrompt prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        public Builder preserveThinking(Boolean preserveThinking) {
            this.preserveThinking = preserveThinking;
            return this;
        }

        public Builder completionClient(QwenCompletionClient completionClient) {
            this.completionClient = completionClient;
            return this;
        }

        public QwenOpenAiCompatibleClient build() {
            URI effectiveEndpoint = Objects.requireNonNull(endpoint, "endpoint");
            String effectiveModelId = Objects.requireNonNull(modelId, "modelId");
            QwenCompletionClient effectiveCompletionClient = completionClient == null
                    ? new QwenRestCompletionClient(effectiveEndpoint, apiKey)
                    : completionClient;
            return new QwenOpenAiCompatibleClient(
                    effectiveEndpoint,
                    apiKey,
                    effectiveModelId,
                    servedModelName == null ? effectiveModelId : servedModelName,
                    Objects.requireNonNull(modelRevision, "modelRevision"),
                    Objects.requireNonNull(registry, "registry"),
                    Objects.requireNonNull(decodingConfig, "decodingConfig"),
                    effectiveCompletionClient,
                    Objects.requireNonNull(mapper, "mapper"),
                    prompt,
                    enableThinking,
                    preserveThinking);
        }
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public String modelRevision() {
        return modelRevision;
    }

    @Override
    public String providerSchemaAdapter() {
        return schemaAdapter.name();
    }

    @Override
    public JsonNode modelConfig() {
        ObjectNode node = mapper.createObjectNode();
        node.put("servingArtifactUri", endpoint.toString());
        node.put("endpoint", endpoint.toString());
        node.put("servedModelName", servedModelName);
        node.put("promptVariant", prompt.variant());
        node.put("promptHash", prompt.hash());
        node.put("promptSource", prompt.source());
        if (enableThinking != null) {
            node.put("enableThinking", enableThinking);
        }
        if (preserveThinking != null) {
            node.put("preserveThinking", preserveThinking);
        }
        node.put("vllmVersion", "not-configured");
        node.put("servingHardwareShape", "not-configured");
        return node;
    }

    @Override
    public JsonNode decodingConfig() {
        return decodingConfig.toJson(mapper);
    }

    @Override
    public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
        return decodeCachedResponse(rawProviderResponse(task, priorOutputs));
    }

    @Override
    public JsonNode providerRequest(EvalTask task, List<ModelOutput> priorOutputs) {
        return requestBody(task, priorOutputs);
    }

    @Override
    public String rawProviderResponse(EvalTask task, List<ModelOutput> priorOutputs) {
        return completionClient.createCompletion(requestBody(task, priorOutputs));
    }

    @Override
    public ModelOutput decodeCachedResponse(String rawResponse) {
        return decodeRawResponse(rawResponse);
    }

    public ObjectNode requestBody(EvalTask task, List<ModelOutput> priorOutputs) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", servedModelName);
        body.put("temperature", decodingConfig.temperature());
        body.put("top_p", decodingConfig.topP());
        body.put("max_tokens", decodingConfig.maxOutputTokens());
        body.put("stream", false);
        if (enableThinking != null || preserveThinking != null) {
            ObjectNode chatTemplateKwargs = body.putObject("chat_template_kwargs");
            if (enableThinking != null) {
                chatTemplateKwargs.put("enable_thinking", enableThinking);
            }
            if (preserveThinking != null) {
                chatTemplateKwargs.put("preserve_thinking", preserveThinking);
            }
        }
        body.set("tools", schemaAdapter.adapt(registry));
        body.put("tool_choice", "auto");
        body.set("messages", messages(task, priorOutputs));
        return body;
    }

    public ModelOutput decodeRawResponse(String rawResponse) {
        try {
            JsonNode root = mapper.readTree(rawResponse);
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.isMissingNode()) {
                throw new IllegalArgumentException("OpenAI-compatible response is missing choices[0].message");
            }
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode callNode : message.path("tool_calls")) {
                JsonNode function = callNode.path("function");
                String id = requiredText(callNode, "id");
                String name = requiredText(function, "name");
                calls.add(new ToolCall(id, name, parseArguments(function.path("arguments"))));
            }
            if (!calls.isEmpty()) {
                return new ModelOutput(rawResponse, calls, null);
            }
            String content = message.path("content").asText("");
            return new ModelOutput(
                    rawResponse,
                    List.of(),
                    StructuredFinalResponseDecoder.decode(content, mapper));
        } catch (ProviderResponseDecodeException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw decodeException(rawResponse, e.getMessage(), e);
        } catch (Exception e) {
            throw decodeException(rawResponse, "Malformed OpenAI-compatible provider response", e);
        }
    }

    private ProviderResponseDecodeException decodeException(String rawResponse, String message, Throwable cause) {
        return new ProviderResponseDecodeException(
                message,
                rawResponse,
                null,
                providerSchemaAdapter(),
                modelId,
                modelRevision,
                cause);
    }

    private ArrayNode messages(EvalTask task, List<ModelOutput> priorOutputs) {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject()
                .put("role", "system")
                .put("content", prompt.text());
        messages.addObject()
                .put("role", "user")
                .put("content", task.prompt());
        for (ModelOutput output : priorOutputs) {
            if (output.hasToolCalls()) {
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.putNull("content");
                ArrayNode toolCalls = assistant.putArray("tool_calls");
                for (ToolCall call : output.toolCalls()) {
                    ObjectNode callNode = toolCalls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments().toString());
                }
                for (ToolResultMessage result : output.toolResults()) {
                    messages.addObject()
                            .put("role", "tool")
                            .put("tool_call_id", result.toolCallId())
                            .put("content", result.content().toString());
                }
            }
        }
        return messages;
    }

    private JsonNode parseArguments(JsonNode node) throws Exception {
        if (node.isTextual()) {
            return mapper.readTree(node.asText());
        }
        if (node.isObject()) {
            return node;
        }
        throw new IllegalArgumentException("Tool call arguments must be a JSON object or JSON string");
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required text field: " + field);
        }
        return value.asText();
    }

    private static ToolLabPrompt baselinePrompt() {
        return new ToolLabPromptCatalog().resolve(ToolLabPromptCatalog.DEFAULT_VARIANT);
    }
}
