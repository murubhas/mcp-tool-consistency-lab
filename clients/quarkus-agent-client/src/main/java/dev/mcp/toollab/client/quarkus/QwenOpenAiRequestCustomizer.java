package dev.mcp.toollab.client.quarkus;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
@ModelName("tool-lab")
public class QwenOpenAiRequestCustomizer implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {
    static final String CHAT_TEMPLATE_KWARGS = "chat_template_kwargs";
    static final String ENABLE_THINKING = "enable_thinking";
    static final String PRESERVE_THINKING = "preserve_thinking";

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        builder.customParameters(Map.of(
                CHAT_TEMPLATE_KWARGS,
                Map.of(
                        ENABLE_THINKING, false,
                        PRESERVE_THINKING, false)));
    }
}
