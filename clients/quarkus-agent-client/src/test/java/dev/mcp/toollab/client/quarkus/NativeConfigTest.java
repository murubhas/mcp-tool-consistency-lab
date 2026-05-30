package dev.mcp.toollab.client.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.Map;

@QuarkusTest
class NativeConfigTest {
    @Inject
    Config config;
    @Inject
    @ModelName("tool-lab")
    ChatModel chatModel;

    @Test
    void bindsNativeMcpAndDefaultQwenConfiguration() {
        assertEquals("streamable-http", config.getValue("quarkus.langchain4j.mcp.tool-lab.transport-type", String.class));
        assertEquals("http://localhost:8088/mcp", config.getValue("quarkus.langchain4j.mcp.tool-lab.url", String.class));
        assertEquals("120s",
                config.getValue("quarkus.langchain4j.mcp.tool-lab.tool-execution-timeout", String.class));
        assertEquals("openai", config.getValue("quarkus.langchain4j.tool-lab.chat-model.provider", String.class));
        assertEquals("http://localhost:8000/v1",
                config.getValue("quarkus.langchain4j.openai.tool-lab.base-url", String.class));
        assertEquals("qwen36-27b-all1000-plus-toollab-no-tool-fp8",
                config.getValue("quarkus.langchain4j.openai.tool-lab.chat-model.model-name", String.class));
        assertEquals("120s", config.getValue("quarkus.langchain4j.openai.tool-lab.timeout", String.class));
        assertEquals("us.anthropic.claude-sonnet-4-6",
                config.getValue("quarkus.langchain4j.bedrock.tool-lab.chat-model.model-id", String.class));
    }

    @Test
    void qwenOpenAiModelCarriesThinkingTemplateKwargs() {
        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) chatModel.defaultRequestParameters();

        assertEquals(
                Map.of(
                        QwenOpenAiRequestCustomizer.ENABLE_THINKING, false,
                        QwenOpenAiRequestCustomizer.PRESERVE_THINKING, false),
                parameters.customParameters().get(QwenOpenAiRequestCustomizer.CHAT_TEMPLATE_KWARGS));
    }
}
