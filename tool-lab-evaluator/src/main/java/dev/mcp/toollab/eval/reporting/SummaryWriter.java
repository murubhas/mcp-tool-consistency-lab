package dev.mcp.toollab.eval.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.eval.trace.TraceRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SummaryWriter {
    public void write(String runId, List<TraceRecord> records, Path outputPath) {
        write(runId, records, outputPath, records.size(), 1);
    }

    public void write(String runId, List<TraceRecord> records, Path outputPath, int selectedTaskCount, int repeat) {
        long passed = records.stream().filter(record -> record.score("overallPass")).count();
        Map<String, int[]> byCategory = new TreeMap<>();
        Map<String, Integer> byCompletionStatus = new TreeMap<>();
        for (TraceRecord record : records) {
            String category = record.json().path("category").asText("unknown");
            int[] counts = byCategory.computeIfAbsent(category, ignored -> new int[2]);
            counts[1]++;
            if (record.score("overallPass")) {
                counts[0]++;
            }
            String status = record.json().path("completionStatus").asText("unknown");
            byCompletionStatus.merge(status, 1, Integer::sum);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("# Tool Consistency Dry-Run Summary\n\n");
        summary.append("- Run ID: `").append(runId).append("`\n");
        if (!records.isEmpty()) {
            summary.append("- Tool execution mode: `")
                    .append(records.get(0).json().path("toolExecutionMode").asText("mcp-http"))
                    .append("`\n");
        }
        ToolLabPrompt defaultPrompt = new ToolLabPromptCatalog().resolve(ToolLabPromptCatalog.DEFAULT_VARIANT);
        JsonNode prompt = records.isEmpty()
                ? null
                : records.get(0).json().path("modelConfig");
        if (prompt != null && !prompt.isMissingNode()) {
            summary.append("- Prompt variant: `")
                    .append(prompt.path("promptVariant").asText(defaultPrompt.variant()))
                    .append("`\n");
            summary.append("- Prompt hash: `")
                    .append(prompt.path("promptHash").asText(defaultPrompt.hash()))
                    .append("`\n");
            summary.append("- Prompt source: `")
                    .append(prompt.path("promptSource").asText(defaultPrompt.source()))
                    .append("`\n");
        }
        summary.append("- Selected tasks: ").append(selectedTaskCount).append("\n");
        summary.append("- Repeat: ").append(repeat).append("\n");
        summary.append("- Tasks: ").append(records.size()).append("\n");
        summary.append("- Overall pass: ").append(passed).append("/").append(records.size()).append("\n\n");
        summary.append("| Category | Pass | Total |\n");
        summary.append("|---|---:|---:|\n");
        for (Map.Entry<String, int[]> entry : byCategory.entrySet()) {
            summary.append("| ").append(entry.getKey()).append(" | ")
                    .append(entry.getValue()[0]).append(" | ")
                    .append(entry.getValue()[1]).append(" |\n");
        }
        summary.append("\n| Completion Status | Count |\n");
        summary.append("|---|---:|\n");
        for (Map.Entry<String, Integer> entry : byCompletionStatus.entrySet()) {
            summary.append("| ").append(entry.getKey()).append(" | ")
                    .append(entry.getValue()).append(" |\n");
        }
        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, summary.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write summary " + outputPath, e);
        }
    }
}
