package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AwsSdkBedrockRuntimeInvoker implements BedrockRuntimeInvoker {
    private final BedrockRuntimeClient client;
    private final ObjectMapper mapper;

    public AwsSdkBedrockRuntimeInvoker(BedrockRuntimeClient client, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String invoke(String modelId, ObjectNode request) {
        try {
            ConverseResponse response = client.converse(toConverseRequest(modelId, request));
            return mapper.writeValueAsString(toAnthropicResponse(response));
        } catch (Exception e) {
            throw new IllegalStateException("Bedrock Runtime converse failed", e);
        }
    }

    ConverseRequest toConverseRequest(String modelId, ObjectNode request) {
        ConverseRequest.Builder builder = ConverseRequest.builder()
                .modelId(modelId)
                .messages(messages(request.path("messages")))
                .inferenceConfig(inferenceConfig(request));

        String system = request.path("system").asText("");
        if (!system.isBlank()) {
            builder.system(SystemContentBlock.fromText(system));
        }
        ToolConfiguration toolConfig = toolConfig(request.path("tools"));
        if (toolConfig != null) {
            builder.toolConfig(toolConfig);
        }
        return builder.build();
    }

    ObjectNode toAnthropicResponse(ConverseResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("stop_reason", response.stopReasonAsString());
        ArrayNode content = root.putArray("content");

        ConverseOutput output = response.output();
        Message message = output == null ? null : output.message();
        if (message == null || !message.hasContent()) {
            return root;
        }
        for (ContentBlock block : message.content()) {
            if (block.text() != null) {
                content.addObject()
                        .put("type", "text")
                        .put("text", block.text());
            } else if (block.toolUse() != null) {
                ToolUseBlock toolUse = block.toolUse();
                ObjectNode tool = content.addObject();
                tool.put("type", "tool_use");
                tool.put("id", toolUse.toolUseId());
                tool.put("name", toolUse.name());
                tool.set("input", fromDocument(toolUse.input()));
            }
        }
        return root;
    }

    private InferenceConfiguration inferenceConfig(ObjectNode request) {
        InferenceConfiguration.Builder builder = InferenceConfiguration.builder();
        if (request.has("max_tokens")) {
            builder.maxTokens(request.path("max_tokens").asInt());
        }
        if (request.has("temperature")) {
            builder.temperature((float) request.path("temperature").asDouble());
        }
        if (request.has("top_p")) {
            builder.topP((float) request.path("top_p").asDouble());
        }
        return builder.build();
    }

    private List<Message> messages(JsonNode messages) {
        List<Message> result = new ArrayList<>();
        if (!messages.isArray()) {
            return result;
        }
        for (JsonNode message : messages) {
            result.add(Message.builder()
                    .role(message.path("role").asText())
                    .content(contentBlocks(message.path("content")))
                    .build());
        }
        return result;
    }

    private List<ContentBlock> contentBlocks(JsonNode content) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!content.isArray()) {
            return blocks;
        }
        for (JsonNode block : content) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                blocks.add(ContentBlock.fromText(block.path("text").asText()));
            } else if ("tool_use".equals(type)) {
                blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                        .toolUseId(block.path("id").asText())
                        .name(block.path("name").asText())
                        .input(toDocument(block.path("input")))
                        .build()));
            } else if ("tool_result".equals(type)) {
                blocks.add(ContentBlock.fromToolResult(toolResult(block)));
            }
        }
        return blocks;
    }

    private ToolResultBlock toolResult(JsonNode block) {
        return ToolResultBlock.builder()
                .toolUseId(block.path("tool_use_id").asText())
                .status(block.path("is_error").asBoolean(false) ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
                .content(toolResultContent(block.path("content")))
                .build();
    }

    private ToolResultContentBlock toolResultContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return ToolResultContentBlock.fromText("");
        }
        if (!content.isTextual()) {
            return ToolResultContentBlock.fromJson(toDocument(content));
        }
        String text = content.asText();
        try {
            return ToolResultContentBlock.fromJson(toDocument(mapper.readTree(text)));
        } catch (Exception e) {
            return ToolResultContentBlock.fromText(text);
        }
    }

    private ToolConfiguration toolConfig(JsonNode tools) {
        if (!tools.isArray() || tools.isEmpty()) {
            return null;
        }
        List<Tool> result = new ArrayList<>();
        for (JsonNode tool : tools) {
            result.add(Tool.fromToolSpec(ToolSpecification.builder()
                    .name(tool.path("name").asText())
                    .description(tool.path("description").asText())
                    .inputSchema(ToolInputSchema.fromJson(toDocument(tool.path("input_schema"))))
                    .build()));
        }
        return ToolConfiguration.builder()
                .tools(result)
                .build();
    }

    private static Document toDocument(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Document.fromNull();
        }
        if (node.isObject()) {
            Map<String, Document> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), toDocument(entry.getValue())));
            return Document.fromMap(values);
        }
        if (node.isArray()) {
            List<Document> values = new ArrayList<>();
            node.forEach(value -> values.add(toDocument(value)));
            return Document.fromList(values);
        }
        if (node.isBoolean()) {
            return Document.fromBoolean(node.asBoolean());
        }
        if (node.isNumber()) {
            return Document.fromNumber(node.decimalValue());
        }
        return Document.fromString(node.asText());
    }

    private JsonNode fromDocument(Document document) {
        if (document == null || document.isNull()) {
            return mapper.nullNode();
        }
        if (document.isMap()) {
            ObjectNode node = mapper.createObjectNode();
            document.asMap().forEach((key, value) -> node.set(key, fromDocument(value)));
            return node;
        }
        if (document.isList()) {
            ArrayNode node = mapper.createArrayNode();
            document.asList().forEach(value -> node.add(fromDocument(value)));
            return node;
        }
        if (document.isBoolean()) {
            return mapper.getNodeFactory().booleanNode(document.asBoolean());
        }
        if (document.isNumber()) {
            return mapper.getNodeFactory().numberNode(document.asNumber().bigDecimalValue());
        }
        return mapper.getNodeFactory().textNode(document.asString());
    }
}
