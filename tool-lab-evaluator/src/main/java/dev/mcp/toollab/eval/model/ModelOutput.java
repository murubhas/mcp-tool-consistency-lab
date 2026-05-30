package dev.mcp.toollab.eval.model;

import java.util.List;

public record ModelOutput(
        String rawText,
        List<ToolCall> toolCalls,
        FinalResponse finalResponse,
        List<ToolResultMessage> toolResults,
        boolean fromCache) {

    public ModelOutput(String rawText, List<ToolCall> toolCalls, FinalResponse finalResponse) {
        this(rawText, toolCalls, finalResponse, List.of(), false);
    }

    public ModelOutput {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean hasFinalResponse() {
        return finalResponse != null;
    }

    public ModelOutput withToolResults(List<ToolResultMessage> results) {
        return new ModelOutput(rawText, toolCalls, finalResponse, results, fromCache);
    }

    public ModelOutput withFromCache(boolean cached) {
        return new ModelOutput(rawText, toolCalls, finalResponse, toolResults, cached);
    }
}
