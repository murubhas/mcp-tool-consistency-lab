package dev.mcp.toollab.eval.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcp.toollab.eval.trace.TraceRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class ConsoleReporter {
    private ConsoleReporter() {
    }

    public static String formatRecord(TraceRecord record) {
        JsonNode json = record.json();
        StringBuilder sb = new StringBuilder();

        sb.append("Task: ").append(json.path("taskId").asText()).append('\n');
        sb.append("Category: ").append(json.path("category").asText("")).append('\n');
        sb.append("Result: ").append(record.score("overallPass") ? "PASS" : "FAIL").append('\n');
        String observedTrace = toolSequence(json);
        sb.append("Expected trace: ").append(expectedTrace(json)).append('\n');
        sb.append("Observed trace: ").append(observedTrace).append('\n');
        sb.append("Tools: ").append(observedTrace).append('\n');

        JsonNode findings = json.path("findings");
        if (findings.isArray() && !findings.isEmpty()) {
            sb.append("Findings:\n");
            for (JsonNode finding : findings) {
                sb.append("  - ").append(finding.path("type").asText())
                        .append(": ").append(truncate(finding.path("message").asText(), 120)).append('\n');
            }
        }

        sb.append("Final:\n");
        JsonNode finalResponse = json.path("finalResponse");
        if (finalResponse.isMissingNode() || finalResponse.isNull()) {
            sb.append("  (none)\n");
        } else {
            sb.append("  ").append(finalResponse).append('\n');
        }

        return sb.toString();
    }

    public static String formatSummary(List<TraceRecord> records, Path runDir) {
        long passed = records.stream().filter(r -> r.score("overallPass")).count();
        return "Summary: " + passed + "/" + records.size() + " passed\n"
                + "Results: " + runDir.toAbsolutePath() + "\n";
    }

    static String toolSequence(JsonNode json) {
        return traceSequence(json);
    }

    private static String expectedTrace(JsonNode json) {
        JsonNode expectedTrace = json.path("expectedTrace");
        return expectedTrace.isMissingNode() || expectedTrace.isNull()
                ? "not recorded"
                : traceSequence(expectedTrace);
    }

    private static String traceSequence(JsonNode traceOrRecord) {
        JsonNode steps = traceOrRecord.path("steps");
        if (steps.isMissingNode()) {
            return "not recorded";
        }
        if (!steps.isArray() || steps.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode step : steps) {
            JsonNode toolCalls = step.path("toolCalls");
            if (!toolCalls.isArray()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode call : toolCalls) {
                names.add(call.path("name").asText("?"));
            }
            if (names.size() == 1) {
                parts.add(names.getFirst());
            } else if (!names.isEmpty()) {
                StringJoiner joiner = new StringJoiner(", ", "[", "]");
                names.forEach(joiner::add);
                parts.add(joiner.toString());
            }
        }
        return parts.isEmpty() ? "none" : String.join(" -> ", parts);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
