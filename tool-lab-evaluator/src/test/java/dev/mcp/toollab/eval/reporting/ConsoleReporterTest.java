package dev.mcp.toollab.eval.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.eval.trace.TraceRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleReporterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatRecordShowsPassWithToolSequence() {
        ObjectNode json = mapper.createObjectNode();
        json.put("taskId", "compute.single.spec.001");
        json.put("category", "single_tool");

        ObjectNode expectedTrace = json.putObject("expectedTrace");
        ArrayNode expectedSteps = expectedTrace.putArray("steps");
        ObjectNode expectedStep = expectedSteps.addObject();
        expectedStep.putArray("toolCalls").addObject().put("name", "get_instance_spec");

        ArrayNode steps = json.putArray("steps");
        ObjectNode step = steps.addObject();
        ArrayNode toolCalls = step.putArray("toolCalls");
        ObjectNode call = toolCalls.addObject();
        call.put("name", "get_instance_spec");

        ObjectNode scores = json.putObject("scores");
        scores.put("overallPass", true);

        ObjectNode finalResponse = json.putObject("finalResponse");
        finalResponse.put("responseType", "final_answer");
        finalResponse.put("message", "The specs are here.");

        String output = ConsoleReporter.formatRecord(new TraceRecord(json));

        assertTrue(output.contains("Task: compute.single.spec.001"));
        assertTrue(output.contains("Category: single_tool"));
        assertTrue(output.contains("Result: PASS"));
        assertTrue(output.contains("Expected trace: get_instance_spec"));
        assertTrue(output.contains("Observed trace: get_instance_spec"));
        assertTrue(output.contains("Tools: get_instance_spec"));
        assertTrue(output.contains("final_answer"));
        assertFalse(output.contains("Findings:"));
    }

    @Test
    void formatRecordShowsFailWithFindings() {
        ObjectNode json = mapper.createObjectNode();
        json.put("taskId", "compute.no-tool.001");
        json.put("category", "no_tool");

        json.putObject("expectedTrace").putArray("steps");
        json.putArray("steps");

        ObjectNode scores = json.putObject("scores");
        scores.put("overallPass", false);

        ArrayNode findings = json.putArray("findings");
        ObjectNode finding = findings.addObject();
        finding.put("type", "provider_decode_failed");
        finding.put("message", "Provider final response content is empty");

        ObjectNode finalResponse = json.putObject("finalResponse");
        finalResponse.put("responseType", "cannot_complete");
        finalResponse.put("message", "Provider response decode failed.");

        String output = ConsoleReporter.formatRecord(new TraceRecord(json));

        assertTrue(output.contains("Result: FAIL"));
        assertTrue(output.contains("Expected trace: none"));
        assertTrue(output.contains("Observed trace: none"));
        assertTrue(output.contains("Tools: none"));
        assertTrue(output.contains("Findings:"));
        assertTrue(output.contains("provider_decode_failed"));
        assertTrue(output.contains("cannot_complete"));
    }

    @Test
    void formatRecordShowsParallelToolCalls() {
        ObjectNode json = mapper.createObjectNode();
        json.put("taskId", "compute.parallel.specs.001");
        json.put("category", "parallel");

        ObjectNode expectedTrace = json.putObject("expectedTrace");
        ArrayNode expectedSteps = expectedTrace.putArray("steps");
        ObjectNode expectedStep1 = expectedSteps.addObject();
        ArrayNode expectedCalls1 = expectedStep1.putArray("toolCalls");
        expectedCalls1.addObject().put("name", "get_instance_spec");
        expectedCalls1.addObject().put("name", "get_instance_spec");
        ObjectNode expectedStep2 = expectedSteps.addObject();
        expectedStep2.putArray("toolCalls").addObject().put("name", "recommend_instance");

        ArrayNode steps = json.putArray("steps");
        ObjectNode step1 = steps.addObject();
        ArrayNode calls1 = step1.putArray("toolCalls");
        calls1.addObject().put("name", "get_instance_spec");
        calls1.addObject().put("name", "get_instance_spec");
        ObjectNode step2 = steps.addObject();
        ArrayNode calls2 = step2.putArray("toolCalls");
        calls2.addObject().put("name", "recommend_instance");

        ObjectNode scores = json.putObject("scores");
        scores.put("overallPass", true);

        json.putObject("finalResponse").put("responseType", "final_answer");

        String output = ConsoleReporter.formatRecord(new TraceRecord(json));

        assertTrue(output.contains("Expected trace: [get_instance_spec, get_instance_spec] -> recommend_instance"));
        assertTrue(output.contains("Observed trace: [get_instance_spec, get_instance_spec] -> recommend_instance"));
        assertTrue(output.contains("Tools: [get_instance_spec, get_instance_spec] -> recommend_instance"));
    }

    @Test
    void formatSummaryShowsCountAndPath() {
        ObjectNode pass = mapper.createObjectNode();
        pass.putObject("scores").put("overallPass", true);
        ObjectNode fail = mapper.createObjectNode();
        fail.putObject("scores").put("overallPass", false);

        String output = ConsoleReporter.formatSummary(
                List.of(new TraceRecord(pass), new TraceRecord(fail)),
                Path.of("/tmp/results/qwen-live-test"));

        assertTrue(output.contains("Summary: 1/2 passed"));
        assertTrue(output.contains("/tmp/results/qwen-live-test"));
    }

    @Test
    void formatRecordDoesNotTruncateStructuredFinalResponse() {
        ObjectNode json = mapper.createObjectNode();
        json.put("taskId", "compute.long.final.001");
        json.put("category", "single_tool");
        json.putObject("scores").put("overallPass", true);

        ObjectNode finalResponse = json.putObject("finalResponse");
        finalResponse.put("responseType", "final_answer");
        finalResponse.put("message", "x".repeat(260));
        finalResponse.putArray("claims");

        String output = ConsoleReporter.formatRecord(new TraceRecord(json));

        assertTrue(output.contains("x".repeat(260)));
        assertFalse(output.contains("..."));
    }
}
