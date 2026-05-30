package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderResponseDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
    private final DecodingConfig decoding = DecodingConfig.deterministic(1024);

    @Test
    void decodesOpenAiCompatibleToolCalls() {
        QwenOpenAiCompatibleClient client = qwen();
        String snapshot = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "tool_calls": [{
                        "id": "call_001",
                        "type": "function",
                        "function": {
                          "name": "get_instance_spec",
                          "arguments": "{\\"instanceType\\":\\"g6e.xlarge\\"}"
                        }
                      }]
                    }
                  }]
                }
                """;

        ModelOutput output = client.decodeRawResponse(snapshot);

        assertEquals(1, output.toolCalls().size());
        assertEquals("call_001", output.toolCalls().get(0).id());
        assertEquals("get_instance_spec", output.toolCalls().get(0).name());
        assertEquals("g6e.xlarge", output.toolCalls().get(0).arguments().path("instanceType").asText());
    }

    @Test
    void decodesOpenAiCompatibleStructuredFinalResponse() {
        QwenOpenAiCompatibleClient client = qwen();
        String snapshot = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "{\\"responseType\\":\\"final_answer\\",\\"message\\":\\"Use g6e.xlarge.\\",\\"claims\\":[]}"
                    }
                  }]
                }
                """;

        ModelOutput output = client.decodeRawResponse(snapshot);

        assertTrue(output.hasFinalResponse());
        assertEquals("final_answer", output.finalResponse().responseType());
    }

    @Test
    void rejectsMalformedOpenAiCompatibleResponses() {
        assertThrows(ProviderResponseDecodeException.class, () -> qwen().decodeRawResponse("{}"));
    }

    @Test
    void preservesOpenAiCompatibleRawResponseWhenFinalContentIsEmpty() {
        String rawResponse = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null
                    }
                  }]
                }
                """;

        ProviderResponseDecodeException error = assertThrows(
                ProviderResponseDecodeException.class,
                () -> qwen().decodeRawResponse(rawResponse));

        assertEquals("Provider final response content is empty", error.getMessage());
        assertEquals(rawResponse, error.rawResponse());
        assertEquals("openai-compatible-v1", error.providerSchemaAdapter());
        assertTrue(error.rawResponseExcerpt(80).contains("\"choices\""));
    }

    @Test
    void rejectsReasoningOnlyQwenFinalResponseFixture() throws Exception {
        String rawResponse = resource("provider-responses/qwen-empty-content-reasoning-final.json");

        ProviderResponseDecodeException error = assertThrows(
                ProviderResponseDecodeException.class,
                () -> qwen().decodeRawResponse(rawResponse));

        assertEquals("Provider final response content is empty", error.getMessage());
        assertEquals(rawResponse, error.rawResponse());
        assertTrue(error.rawResponse().contains("\"content\":null"));
        assertTrue(error.rawResponse().contains("\"tool_calls\":[]"));
        assertTrue(error.rawResponse().contains("\"reasoning\""));
        assertTrue(error.rawResponse().contains("responseType"));
    }

    @Test
    void decodesAnthropicBedrockToolUse() {
        SonnetBedrockClient client = sonnet();
        String snapshot = """
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [{
                    "type": "tool_use",
                    "id": "toolu_001",
                    "name": "search_instances",
                    "input": {"workload":"inference","minAcceleratorMemoryGib":80}
                  }],
                  "stop_reason": "tool_use"
                }
                """;

        ModelOutput output = client.decodeRawResponse(snapshot);

        assertEquals(1, output.toolCalls().size());
        assertEquals("toolu_001", output.toolCalls().get(0).id());
        assertEquals("search_instances", output.toolCalls().get(0).name());
        assertEquals(80, output.toolCalls().get(0).arguments().path("minAcceleratorMemoryGib").asInt());
    }

    @Test
    void decodesAnthropicBedrockStructuredFinalResponse() {
        SonnetBedrockClient client = sonnet();
        String snapshot = """
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [{
                    "type": "text",
                    "text": "{\\"responseType\\":\\"cannot_complete\\",\\"message\\":\\"Missing instance type.\\",\\"missingFields\\":[\\"instanceType\\"]}"
                  }],
                  "stop_reason": "end_turn"
                }
                """;

        ModelOutput output = client.decodeRawResponse(snapshot);

        assertTrue(output.hasFinalResponse());
        assertEquals("cannot_complete", output.finalResponse().responseType());
    }

    @Test
    void rejectsMalformedAnthropicBedrockResponses() {
        assertThrows(ProviderResponseDecodeException.class, () -> sonnet().decodeRawResponse("{\"content\":{}}"));
    }

    @Test
    void qwenRequestsUseOpenAiCompatibleToolSchema() {
        QwenOpenAiCompatibleClient client = qwen();
        var body = client.requestBody(sampleTask(), List.of());

        assertEquals("function", body.path("tools").path(0).path("type").asText());
        assertTrue(body.path("tools").path(0).path("function").has("parameters"));
        assertEquals("system", body.path("messages").path(0).path("role").asText());
        String systemPrompt = body.path("messages").path(0).path("content").asText();
        assertTrue(systemPrompt.contains("respond with only one JSON object"));
        assertTrue(systemPrompt.contains("No markdown") || systemPrompt.contains("Do not use markdown"));
        assertTrue(systemPrompt.contains("\"missingFields\":[]"));
    }

    @Test
    void qwenRequestPreservesDefaultThinkingBehavior() {
        QwenOpenAiCompatibleClient client = qwen();
        var body = client.requestBody(sampleTask(), List.of());

        assertFalse(body.has("chat_template_kwargs"));
    }

    @Test
    void qwenRequestCanDisableThinkingThroughChatTemplateKwargs() {
        QwenOpenAiCompatibleClient client = qwen(false);
        var body = client.requestBody(sampleTask(), List.of());

        assertFalse(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
        assertFalse(body.path("chat_template_kwargs").has("preserve_thinking"));
    }

    @Test
    void qwenRequestCanDisableThinkingAndPreserveThinkingThroughChatTemplateKwargs() {
        QwenOpenAiCompatibleClient client = qwen(false, false);
        var body = client.requestBody(sampleTask(), List.of());

        assertFalse(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
        assertFalse(body.path("chat_template_kwargs").path("preserve_thinking").asBoolean(true));
    }

    @Test
    void qwenRequestUsesResolvedPromptVariant() {
        ToolLabPrompt refined = new ToolLabPromptCatalog().resolve("refined-v2");
        QwenOpenAiCompatibleClient client = qwen(refined, null, null);
        var body = client.requestBody(sampleTask(), List.of());

        String systemPrompt = body.path("messages").path(0).path("content").asText();
        assertEquals(refined.text(), systemPrompt);
        assertTrue(systemPrompt.contains("the total tool plan is exactly one call: get_instance_spec"));
        assertEquals("refined-v2", client.modelConfig().path("promptVariant").asText());
        assertEquals(refined.hash(), client.modelConfig().path("promptHash").asText());
        assertEquals("catalog", client.modelConfig().path("promptSource").asText());
    }

    @Test
    void qwenRestClientUsesQuarkusRestClientAnnotations() throws Exception {
        assertEquals(
                "qwen-openai",
                QwenOpenAiRestClient.class.getAnnotation(RegisterRestClient.class).configKey());
        assertTrue(QwenOpenAiRestClient.class
                .getMethod("createCompletion", String.class, com.fasterxml.jackson.databind.node.ObjectNode.class)
                .isAnnotationPresent(POST.class));
        assertEquals(
                Response.class,
                QwenOpenAiRestClient.class
                        .getMethod("createCompletion", String.class, com.fasterxml.jackson.databind.node.ObjectNode.class)
                        .getReturnType());
    }

    @Test
    void sonnetRequestsUseAnthropicToolSchema() {
        SonnetBedrockClient client = sonnet();
        var body = client.requestBody(sampleTask(), List.of());

        assertTrue(body.path("tools").path(0).has("input_schema"));
        assertTrue(body.has("system"));
        assertEquals("user", body.path("messages").path(0).path("role").asText());
    }

    private QwenOpenAiCompatibleClient qwen() {
        return qwen(null, null);
    }

    private QwenOpenAiCompatibleClient qwen(Boolean enableThinking) {
        return qwen(enableThinking, null);
    }

    private QwenOpenAiCompatibleClient qwen(Boolean enableThinking, Boolean preserveThinking) {
        return qwen(null, enableThinking, preserveThinking);
    }

    private QwenOpenAiCompatibleClient qwen(
            ToolLabPrompt prompt,
            Boolean enableThinking,
            Boolean preserveThinking) {
        return QwenOpenAiCompatibleClient.builder()
                .endpoint(URI.create("http://localhost:8000/v1/chat/completions"))
                .modelId("Qwen/Qwen3.6-27B")
                .servedModelName("Qwen/Qwen3.6-27B")
                .modelRevision("test")
                .registry(registry)
                .decodingConfig(decoding)
                .completionClient(body -> "{}")
                .mapper(mapper)
                .prompt(prompt)
                .enableThinking(enableThinking)
                .preserveThinking(preserveThinking)
                .build();
    }

    private SonnetBedrockClient sonnet() {
        return new SonnetBedrockClient(
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                "test",
                "us-east-1",
                "quarkus-default",
                registry,
                decoding,
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

    private String resource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
