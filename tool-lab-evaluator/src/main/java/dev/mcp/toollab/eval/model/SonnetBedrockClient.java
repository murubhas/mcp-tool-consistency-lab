package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.AnthropicSchemaAdapter;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SonnetBedrockClient implements CacheableModelClient {
    private final ObjectMapper mapper;
    private final String modelId;
    private final String modelRevision;
    private final String region;
    private final String endpointOverride;
    private final ToolSchemaRegistry registry;
    private final DecodingConfig decodingConfig;
    private final BedrockRuntimeInvoker invoker;
    private final ToolLabPrompt prompt;
    private final AnthropicSchemaAdapter schemaAdapter;

    public SonnetBedrockClient(
            String modelId,
            String modelRevision,
            String region,
            String endpointOverride,
            ToolSchemaRegistry registry,
            DecodingConfig decodingConfig,
            BedrockRuntimeInvoker invoker,
            ObjectMapper mapper) {
        this(modelId, modelRevision, region, endpointOverride, registry, decodingConfig, invoker, mapper, baselinePrompt());
    }

    public SonnetBedrockClient(
            String modelId,
            String modelRevision,
            String region,
            String endpointOverride,
            ToolSchemaRegistry registry,
            DecodingConfig decodingConfig,
            BedrockRuntimeInvoker invoker,
            ObjectMapper mapper,
            ToolLabPrompt prompt) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.modelId = modelId;
        this.modelRevision = modelRevision;
        this.region = region;
        this.endpointOverride = endpointOverride;
        this.registry = registry;
        this.decodingConfig = decodingConfig;
        this.invoker = invoker;
        this.prompt = prompt == null ? baselinePrompt() : prompt;
        this.schemaAdapter = new AnthropicSchemaAdapter(mapper);
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
        node.put("servingArtifactUri", "quarkus-bedrockruntime");
        node.put("bedrockModelId", modelId);
        node.put("bedrockRegion", region);
        node.put("endpointOverride", endpointOverride);
        node.put("promptVariant", prompt.variant());
        node.put("promptHash", prompt.hash());
        node.put("promptSource", prompt.source());
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
        return invoker.invoke(modelId, requestBody(task, priorOutputs));
    }

    @Override
    public ModelOutput decodeCachedResponse(String rawResponse) {
        return decodeRawResponse(rawResponse);
    }

    public ObjectNode requestBody(EvalTask task, List<ModelOutput> priorOutputs) {
        ObjectNode body = mapper.createObjectNode();
        body.put("anthropic_version", "bedrock-2023-05-31");
        body.put("max_tokens", decodingConfig.maxOutputTokens());
        body.put("temperature", decodingConfig.temperature());
        body.put("top_p", decodingConfig.topP());
        body.put("system", prompt.text());
        body.set("tools", schemaAdapter.adapt(registry));
        body.set("messages", messages(task, priorOutputs));
        return body;
    }

    public ModelOutput decodeRawResponse(String rawResponse) {
        try {
            JsonNode root = mapper.readTree(rawResponse);
            JsonNode content = root.path("content");
            if (!content.isArray()) {
                throw new IllegalArgumentException("Anthropic response is missing content array");
            }
            List<ToolCall> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("tool_use".equals(type)) {
                    calls.add(new ToolCall(
                            requiredText(block, "id"),
                            requiredText(block, "name"),
                            block.path("input")));
                } else if ("text".equals(type)) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(block.path("text").asText());
                }
            }
            if (!calls.isEmpty()) {
                return new ModelOutput(rawResponse, calls, null);
            }
            return new ModelOutput(
                    rawResponse,
                    List.of(),
                    StructuredFinalResponseDecoder.decode(text.toString(), mapper));
        } catch (ProviderResponseDecodeException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw decodeException(rawResponse, e.getMessage(), e);
        } catch (Exception e) {
            throw decodeException(rawResponse, "Malformed Anthropic/Bedrock provider response", e);
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
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode userContent = user.putArray("content");
        userContent.addObject()
                .put("type", "text")
                .put("text", task.prompt());

        for (ModelOutput output : priorOutputs) {
            if (output.hasToolCalls()) {
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                ArrayNode assistantContent = assistant.putArray("content");
                for (ToolCall call : output.toolCalls()) {
                    ObjectNode block = assistantContent.addObject();
                    block.put("type", "tool_use");
                    block.put("id", call.id());
                    block.put("name", call.name());
                    block.set("input", call.arguments());
                }
                ObjectNode toolResultMessage = messages.addObject();
                toolResultMessage.put("role", "user");
                ArrayNode resultContent = toolResultMessage.putArray("content");
                for (ToolResultMessage result : output.toolResults()) {
                    ObjectNode block = resultContent.addObject();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", result.toolCallId());
                    block.put("content", result.content().toString());
                    block.put("is_error", !result.success());
                }
            }
        }
        return messages;
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
