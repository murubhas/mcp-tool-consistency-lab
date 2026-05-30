package dev.mcp.toollab.client.quarkus;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.mcp.toollab.client.quarkus.llmguardrail.RecommendationPlanOutputGuardrail;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;

@RegisterAiService(modelName = "tool-lab")
public interface ToolLabAssistant {
    @SystemMessage(ToolLabPromptCatalog.BASELINE_PROMPT)
    @OutputGuardrails(RecommendationPlanOutputGuardrail.class)
    @McpToolBox("tool-lab")
    String chat(@MemoryId String memoryId, @UserMessage String message);
}
