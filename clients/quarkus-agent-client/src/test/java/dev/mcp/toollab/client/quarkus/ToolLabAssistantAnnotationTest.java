package dev.mcp.toollab.client.quarkus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.mcp.toollab.client.quarkus.llmguardrail.RecommendationPlanOutputGuardrail;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

class ToolLabAssistantAnnotationTest {
    @Test
    void assistantUsesQuarkusAiServiceAndMcpToolBox() throws Exception {
        RegisterAiService aiService = ToolLabAssistant.class.getAnnotation(RegisterAiService.class);
        assertNotNull(aiService);
        assertEquals("tool-lab", aiService.modelName());

        Method chat = ToolLabAssistant.class.getMethod("chat", String.class, String.class);
        SystemMessage systemMessage = chat.getAnnotation(SystemMessage.class);
        assertNotNull(systemMessage);
        assertArrayEquals(new String[] {ToolLabPromptCatalog.BASELINE_PROMPT}, systemMessage.value());
        assertArrayEquals(new String[] {"tool-lab"}, chat.getAnnotation(McpToolBox.class).value());
        assertArrayEquals(
                new Class[] {RecommendationPlanOutputGuardrail.class},
                chat.getAnnotation(OutputGuardrails.class).value());
        assertNotNull(chat.getParameters()[0].getAnnotation(MemoryId.class));
        assertNotNull(chat.getParameters()[1].getAnnotation(UserMessage.class));
    }
}
