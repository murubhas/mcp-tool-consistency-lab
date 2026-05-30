package dev.mcp.toollab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.eval.harness.FakeToolExecutionClient;
import dev.mcp.toollab.eval.harness.ToolExecutionClient;
import dev.mcp.toollab.eval.model.ModelClient;
import dev.mcp.toollab.eval.model.ModelOutput;
import dev.mcp.toollab.eval.model.ProviderResponseDecodeException;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.schema.ToolSchemaValidator;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistencyEvalCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void dryRunLimitRestrictsSelectedTasks() throws Exception {
        int exitCode = new TestCommand().run(
                "eval",
                "--dry-run",
                "--limit",
                "1",
                "--results-root",
                tempDir.toString());

        assertEquals(0, exitCode);
        String summary = Files.readString(latestSummary());
        assertTrue(summary.contains("- Selected tasks: 1"));
        assertTrue(summary.contains("- Repeat: 1"));
        assertTrue(summary.contains("- Tasks: 1"));
    }

    @Test
    void dryRunTaskIdAndRepeatAreAppliedAfterLoadingTasks() throws Exception {
        int exitCode = new TestCommand().run(
                "eval",
                "--dry-run",
                "--task-id",
                "compute.single.spec.001,compute.single.price.002",
                "--repeat",
                "2",
                "--results-root",
                tempDir.toString());

        assertEquals(0, exitCode);
        String summary = Files.readString(latestSummary());
        assertTrue(summary.contains("- Selected tasks: 2"));
        assertTrue(summary.contains("- Repeat: 2"));
        assertTrue(summary.contains("- Tasks: 4"));
    }

    @Test
    void sonnetRejectsCliRegionOverride() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TestCommand().run(
                        "eval",
                        "--model",
                        "sonnet",
                        "--dry-run=false",
                        "--model-id",
                        "anthropic.test",
                        "--region",
                        "us-east-1"));

        assertTrue(error.getMessage().contains("quarkus.bedrockruntime.aws.region"));
    }

    @Test
    void sonnetRejectsCliEndpointOverride() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TestCommand().run(
                        "eval",
                        "--model",
                        "sonnet",
                        "--dry-run=false",
                        "--model-id",
                        "anthropic.test",
                        "--endpoint",
                        "https://bedrock-runtime.us-east-1.amazonaws.com"));

        assertTrue(error.getMessage().contains("quarkus.bedrockruntime.endpoint-override"));
    }

    @Test
    void failFastStopsAfterFirstFailedRecord() throws Exception {
        int exitCode = new DecodeFailingCommand().run(
                "eval",
                "--dry-run",
                "--limit",
                "2",
                "--repeat",
                "2",
                "--fail-fast",
                "--results-root",
                tempDir.toString());

        assertEquals(1, exitCode);
        String summary = Files.readString(latestSummary());
        assertTrue(summary.contains("- Selected tasks: 2"));
        assertTrue(summary.contains("- Repeat: 2"));
        assertTrue(summary.contains("- Tasks: 1"));
        assertTrue(summary.contains("| provider_decode_failed | 1 |"));
    }

    private Path latestSummary() throws Exception {
        try (var stream = Files.list(tempDir)) {
            Path runDir = stream.max(Comparator.comparing(Path::toString)).orElseThrow();
            return runDir.resolve("summary.md");
        }
    }

    @Vetoed
    private static class TestCommand extends ConsistencyEvalCommand {
        TestCommand() {
            mapper = new ObjectMapper();
        }

        @Override
        protected ToolExecutionClient buildToolExecutionClient(ToolSchemaRegistry registry) {
            return new FakeToolExecutionClient(new ToolSchemaValidator(registry));
        }
    }

    @Vetoed
    private static final class DecodeFailingCommand extends TestCommand {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        protected ModelClient buildClient(ToolSchemaRegistry registry) {
            return new ModelClient() {
                @Override
                public String modelId() {
                    return "decode-failing-model";
                }

                @Override
                public String modelRevision() {
                    return "test";
                }

                @Override
                public String providerSchemaAdapter() {
                    return "test-provider";
                }

                @Override
                public JsonNode modelConfig() {
                    return mapper.createObjectNode();
                }

                @Override
                public JsonNode decodingConfig() {
                    return mapper.createObjectNode();
                }

                @Override
                public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
                    throw new ProviderResponseDecodeException(
                            "Provider final response content is empty",
                            "{\"choices\":[{\"message\":{\"content\":null}}]}",
                            mapper.createObjectNode().put("taskId", task.taskId()),
                            providerSchemaAdapter(),
                            modelId(),
                            modelRevision(),
                            null);
                }
            };
        }
    }
}
