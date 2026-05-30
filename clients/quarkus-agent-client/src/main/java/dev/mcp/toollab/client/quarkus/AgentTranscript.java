package dev.mcp.toollab.client.quarkus;

public record AgentTranscript(
        String scenarioId,
        String userPrompt,
        String finalAssistantAnswer,
        String clientMode) {
}
