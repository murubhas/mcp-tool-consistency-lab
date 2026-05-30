package dev.mcp.toollab.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.eval.reporting.RunManifestWriter;
import dev.mcp.toollab.eval.schema.JsonResourceSchemaValidator;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.schema.ValidationResult;
import dev.mcp.toollab.eval.model.MockModelClient;
import dev.mcp.toollab.eval.validation.AcceptedTraceSet;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaConformanceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonResourceSchemaValidator validator = new JsonResourceSchemaValidator(mapper);

    @Test
    void evalTasksConformToSchema() throws Exception {
        try (var stream = getClass().getResourceAsStream("/eval-tasks.jsonl");
             var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ValidationResult result = validator.validateResource(
                        "/schemas/eval-tasks.schema.json",
                        mapper.readTree(line));
                assertTrue(result.valid(), result.errors().toString());
            }
        }
    }

    @Test
    void acceptedTracesConformToSchema() {
        ValidationResult result = validator.validateResource(
                "/schemas/accepted-traces.schema.json",
                AcceptedTraceSet.loadDefault(mapper).root());

        assertTrue(result.valid(), result.errors().toString());
    }

    @Test
    void generatedManifestConformsToSchema() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        AcceptedTraceSet accepted = AcceptedTraceSet.loadDefault(mapper);
        var manifest = new RunManifestWriter(mapper).build(
                "test-run",
                registry,
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                accepted,
                new MockModelClient(mapper),
                true);

        ValidationResult result = validator.validateResource("/schemas/run-manifest.schema.json", manifest);

        assertTrue(result.valid(), result.errors().toString());
    }
}
