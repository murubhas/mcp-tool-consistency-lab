package dev.mcp.toollab.eval;

public record EvalTask(
        String taskId,
        String templateId,
        String split,
        String domain,
        String category,
        String prompt,
        String initialStateProfile,
        String expectedResponseType,
        int maxSteps) {
}
