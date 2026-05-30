package dev.mcp.toollab.client.quarkus.llmguardrail;

import dev.langchain4j.service.guardrail.OutputGuardrails;

interface GuardedPlanAssistantDemo {
    @OutputGuardrails(RecommendationPlanOutputGuardrail.class)
    String chat(String message);
}
