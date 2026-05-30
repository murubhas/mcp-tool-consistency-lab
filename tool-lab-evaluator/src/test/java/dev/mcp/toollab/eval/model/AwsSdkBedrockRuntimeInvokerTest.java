package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AwsSdkBedrockRuntimeInvokerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
    private final AwsSdkBedrockRuntimeInvoker invoker = new AwsSdkBedrockRuntimeInvoker(stubClient(), mapper);

    @Test
    void translatesAnthropicRequestShapeToBedrockConverse() {
        ObjectNode arguments = mapper.createObjectNode()
                .put("instanceType", "p5.48xlarge");
        ObjectNode result = mapper.createObjectNode()
                .put("instanceType", "p5.48xlarge")
                .put("acceleratorMemoryGib", 640);
        ModelOutput priorOutput = new ModelOutput(
                "{}",
                List.of(new ToolCall("toolu_001", "get_instance_spec", arguments)),
                null)
                .withToolResults(List.of(new ToolResultMessage("toolu_001", "get_instance_spec", true, result)));

        ObjectNode request = sonnet().requestBody(sampleTask(), List.of(priorOutput));

        ConverseRequest converse = invoker.toConverseRequest("us.anthropic.claude-sonnet-4-6", request);

        assertEquals("us.anthropic.claude-sonnet-4-6", converse.modelId());
        assertEquals(1024, converse.inferenceConfig().maxTokens());
        assertEquals(0.0f, converse.inferenceConfig().temperature());
        assertEquals(1.0f, converse.inferenceConfig().topP());
        assertEquals(request.path("system").asText(), converse.system().get(0).text());
        assertEquals(3, converse.messages().size());
        assertEquals("user", converse.messages().get(0).roleAsString());
        assertEquals(sampleTask().prompt(), converse.messages().get(0).content().get(0).text());
        assertEquals("toolu_001", converse.messages().get(1).content().get(0).toolUse().toolUseId());
        assertEquals(ToolResultStatus.SUCCESS, converse.messages().get(2).content().get(0).toolResult().status());
        Document toolResult = converse.messages().get(2).content().get(0).toolResult().content().get(0).json();
        assertEquals(640, toolResult.asMap().get("acceleratorMemoryGib").asNumber().intValue());
        assertNotNull(converse.toolConfig());
        assertEquals(
                request.path("tools").path(0).path("name").asText(),
                converse.toolConfig().tools().get(0).toolSpec().name());
    }

    @Test
    void translatesBedrockConverseResponseToAnthropicResponseShape() {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .toolUseId("toolu_001")
                .name("get_instance_spec")
                .input(Document.fromMap(Map.of("instanceType", Document.fromString("p5.48xlarge"))))
                .build();
        ConverseResponse response = ConverseResponse.builder()
                .stopReason(StopReason.TOOL_USE)
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role("assistant")
                        .content(ContentBlock.fromText("Looking up the instance."), ContentBlock.fromToolUse(toolUse))
                        .build()))
                .build();

        JsonNode anthropic = invoker.toAnthropicResponse(response);

        assertEquals("message", anthropic.path("type").asText());
        assertEquals("assistant", anthropic.path("role").asText());
        assertEquals("tool_use", anthropic.path("stop_reason").asText());
        assertEquals("text", anthropic.path("content").path(0).path("type").asText());
        assertEquals("tool_use", anthropic.path("content").path(1).path("type").asText());
        assertEquals("toolu_001", anthropic.path("content").path(1).path("id").asText());
        assertEquals("p5.48xlarge", anthropic.path("content").path(1).path("input").path("instanceType").asText());
    }

    private SonnetBedrockClient sonnet() {
        return new SonnetBedrockClient(
                "us.anthropic.claude-sonnet-4-6",
                "test",
                "us-east-2",
                "quarkus-default",
                registry,
                DecodingConfig.deterministic(1024),
                (modelId, body) -> "{}",
                mapper);
    }

    private EvalTask sampleTask() {
        return new EvalTask(
                "test-task",
                "template",
                "eval",
                "compute",
                "single_tool",
                "Find a GPU instance.",
                "default",
                "final_answer",
                4);
    }

    private BedrockRuntimeClient stubClient() {
        return new BedrockRuntimeClient() {
            @Override
            public String serviceName() {
                return BedrockRuntimeClient.SERVICE_NAME;
            }

            @Override
            public void close() {
            }
        };
    }
}
