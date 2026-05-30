package dev.mcp.toollab.client.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelName;
import org.junit.jupiter.api.Test;

import java.util.Map;

class QwenOpenAiRequestCustomizerTest {
    @Test
    void addsQwenThinkingTemplateKwargsToNamedOpenAiModel() throws Exception {
        OpenAiChatModel.OpenAiChatModelBuilder builder = new OpenAiChatModel.OpenAiChatModelBuilder()
                .baseUrl("http://127.0.0.1:1/v1")
                .apiKey("dummy")
                .modelName("qwen-test");

        new QwenOpenAiRequestCustomizer().customize(builder);

        var field = OpenAiChatModel.OpenAiChatModelBuilder.class.getDeclaredField("customParameters");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> customParameters = (Map<String, Object>) field.get(builder);

        assertEquals(
                Map.of(
                        QwenOpenAiRequestCustomizer.ENABLE_THINKING, false,
                        QwenOpenAiRequestCustomizer.PRESERVE_THINKING, false),
                customParameters.get(QwenOpenAiRequestCustomizer.CHAT_TEMPLATE_KWARGS));
    }

    @Test
    void customizerTargetsToolLabModelName() {
        ModelName modelName = QwenOpenAiRequestCustomizer.class.getAnnotation(ModelName.class);

        assertEquals("tool-lab", modelName.value());
    }
}
