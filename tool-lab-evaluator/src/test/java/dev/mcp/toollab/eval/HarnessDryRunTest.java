package dev.mcp.toollab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.eval.harness.FakeToolExecutionClient;
import dev.mcp.toollab.eval.harness.ToolCallingHarness;
import dev.mcp.toollab.eval.model.FinalResponse;
import dev.mcp.toollab.eval.model.MockModelClient;
import dev.mcp.toollab.eval.model.ModelClient;
import dev.mcp.toollab.eval.model.ModelOutput;
import dev.mcp.toollab.eval.model.ProviderResponseDecodeException;
import dev.mcp.toollab.eval.model.ToolCall;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.schema.ToolSchemaValidator;
import dev.mcp.toollab.eval.validation.AcceptedTraceSet;
import dev.mcp.toollab.eval.validation.TraceValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessDryRunTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolCallingHarness fakeHarness(ToolSchemaRegistry registry, TraceValidator traceValidator) {
        return new ToolCallingHarness(
                registry,
                traceValidator,
                new FakeToolExecutionClient(new ToolSchemaValidator(registry)),
                mapper);
    }

    @Test
    void allMilestoneMockTasksPassDeterministicValidators() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTaskLoader.LoadedTasks loaded = new EvalTaskLoader(mapper).loadMilestoneTasks();

        long passed = loaded.tasks().stream()
                .map(task -> harness.run("test-run", task, new MockModelClient(mapper)))
                .filter(record -> record.score("overallPass"))
                .count();

        assertEquals(21, loaded.tasks().size());
        assertEquals(21, passed);
    }

    @Test
    void fakeHarnessRecordsMcpHttpToolExecutionMode() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.single.spec.001",
                "compute.single.spec",
                "eval",
                "compute_planning",
                "single_tool",
                "What are the exact specs for p5.48xlarge?",
                "default",
                "final_answer",
                4);

        var record = harness.run("test-run", task, new MockModelClient(mapper));

        assertEquals("mcp-http", record.json().path("toolExecutionMode").asText());
        assertEquals("compute.single.spec.001", record.json().path("toolExecutionStateId").asText());
    }

    @Test
    void maxStepFailureIsStructured() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.single.spec.001",
                "compute.single.spec",
                "eval",
                "compute_planning",
                "single_tool",
                "What are the specs?",
                "default",
                "final_answer",
                0);

        var record = harness.run("test-run", task, new MockModelClient(mapper));

        assertTrue(record.score("maxStepFailure"));
    }

    @Test
    void providerDecodeFailureIsRecordedWithRawResponseEvidence() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.single.spec.001",
                "compute.single.spec",
                "eval",
                "compute_planning",
                "single_tool",
                "What are the exact specs for p5.48xlarge?",
                "default",
                "final_answer",
                4);

        var record = harness.run("test-run", task, new DecodeFailingClient());

        assertEquals("provider_decode_failed", record.json().path("completionStatus").asText());
        JsonNode finding = record.json().path("findings").path(0);
        assertEquals("provider_decode_failed", finding.path("type").asText());
        assertEquals("Provider final response content is empty", finding.path("message").asText());
        assertTrue(finding.path("rawResponseExcerpt").asText().contains("\"content\":null"));
        assertTrue(finding.path("providerRequest").path("turn").asInt() == 2);
        assertFalse(record.score("overallPass"));
    }

    @Test
    void extraReadOnlyToolInvocationFailsTraceAndToolSelection() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.single.spec.001",
                "compute.single.spec",
                "eval",
                "compute_planning",
                "single_tool",
                "What are the exact specs for p5.48xlarge?",
                "default",
                "final_answer",
                4);

        var record = harness.run("test-run", task, new ExtraReadOnlyToolClient());

        assertEquals("completed", record.json().path("completionStatus").asText());
        assertEquals(2, record.json().path("steps").size());
        assertEquals("get_instance_spec", record.json().path("steps").path(0).path("toolCalls").path(0).path("name").asText());
        assertEquals("get_instance_price", record.json().path("steps").path(1).path("toolCalls").path(0).path("name").asText());
        assertTrue(record.score("schemaValid"));
        assertTrue(record.score("parameterPass"));
        assertTrue(record.score("toolExecutionSuccess"));
        assertTrue(record.score("finalStatePass"));
        assertTrue(record.score("structuredResponsePass"));
        assertFalse(record.score("toolSelectionPass"));
        assertFalse(record.score("tracePass"));
        assertFalse(record.score("overallPass"));
    }

    @Test
    void wrongSerialDependencyOrderFailsTraceWithoutSchemaOrExecutionFailure() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.seq.spec-fit-price.002",
                "compute.seq.spec-fit-price",
                "eval",
                "compute_planning",
                "sequential",
                "For g6e.12xlarge, check specs, whether a 70B BF16 inference model fits, and monthly price.",
                "default",
                "final_answer",
                6);

        var record = harness.run("test-run", task, new WrongSerialOrderClient());

        assertEquals("completed", record.json().path("completionStatus").asText());
        assertEquals(3, record.json().path("steps").size());
        assertEquals(
                "check_model_fit",
                record.json().path("steps").path(0).path("toolCalls").path(0).path("name").asText());
        assertEquals(
                "get_instance_price",
                record.json().path("steps").path(1).path("toolCalls").path(0).path("name").asText());
        assertEquals(
                "get_instance_spec",
                record.json().path("steps").path(2).path("toolCalls").path(0).path("name").asText());
        assertTrue(record.score("schemaValid"));
        assertTrue(record.score("parameterPass"));
        assertTrue(record.score("toolExecutionSuccess"));
        assertTrue(record.score("finalStatePass"));
        assertTrue(record.score("structuredResponsePass"));
        assertFalse(record.score("tracePass"));
        assertFalse(record.score("overallPass"));
    }

    @Test
    void extraReadOnlyParallelToolFailsTraceAndToolSelection() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.parallel.specs.001",
                "compute.parallel.specs",
                "eval",
                "compute_planning",
                "parallel",
                "Compare p5.48xlarge and p5e.48xlarge specs and prices for 70B BF16 fine-tuning.",
                "default",
                "final_answer",
                6);

        var record = harness.run("test-run", task, new ExtraParallelToolClient());

        assertEquals("completed", record.json().path("completionStatus").asText());
        assertEquals(2, record.json().path("steps").size());
        assertEquals(3, record.json().path("steps").path(0).path("toolCalls").size());
        assertEquals(2, record.json().path("steps").path(1).path("toolCalls").size());
        assertTrue(record.score("schemaValid"));
        assertTrue(record.score("parameterPass"));
        assertTrue(record.score("toolExecutionSuccess"));
        assertTrue(record.score("finalStatePass"));
        assertTrue(record.score("structuredResponsePass"));
        assertFalse(record.score("toolSelectionPass"));
        assertFalse(record.score("tracePass"));
        assertFalse(record.score("overallPass"));
    }

    @Test
    void collapsedMixedDagLayersFailTraceWithoutSchemaOrExecutionFailure() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);
        ToolCallingHarness harness = fakeHarness(registry, traceValidator);
        EvalTask task = new EvalTask(
                "compute.mixed-dag.fit-price-recommend.001",
                "compute.mixed-dag.fit-price-recommend",
                "eval",
                "compute_planning",
                "mixed_dag",
                "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. Check specs and fit for both candidates, compare their exact tool-returned prices, then recommend the cheapest valid option.",
                "default",
                "final_answer",
                7);

        var record = harness.run("test-run", task, new CollapsedMixedDagClient());

        assertEquals("completed", record.json().path("completionStatus").asText());
        assertEquals(2, record.json().path("steps").size());
        assertEquals(6, record.json().path("steps").path(0).path("toolCalls").size());
        assertEquals("recommend_instance", record.json().path("steps").path(1).path("toolCalls").path(0).path("name").asText());
        assertTrue(record.score("schemaValid"));
        assertTrue(record.score("parameterPass"));
        assertTrue(record.score("toolExecutionSuccess"));
        assertTrue(record.score("finalStatePass"));
        assertTrue(record.score("structuredResponsePass"));
        assertFalse(record.score("toolSelectionPass"));
        assertFalse(record.score("tracePass"));
        assertFalse(record.score("overallPass"));
    }

    private final class DecodeFailingClient implements ModelClient {
        private int turn;

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
            ObjectNode node = mapper.createObjectNode();
            node.put("temperature", 0.0);
            node.put("topP", 1.0);
            node.put("doSample", false);
            return node;
        }

        @Override
        public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
            turn++;
            if (turn == 1) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("instanceType", "p5.48xlarge");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_spec\"}]}",
                        List.of(new ToolCall("call-1", "get_instance_spec", arguments)),
                        null);
            }
            ObjectNode providerRequest = mapper.createObjectNode();
            providerRequest.put("turn", turn);
            throw new ProviderResponseDecodeException(
                    "Provider final response content is empty",
                    "{\"choices\":[{\"message\":{\"content\":null}}]}",
                    providerRequest,
                    providerSchemaAdapter(),
                    modelId(),
                    modelRevision(),
                    null);
        }
    }

    private final class ExtraReadOnlyToolClient implements ModelClient {
        private int turn;

        @Override
        public String modelId() {
            return "extra-tool-model";
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
            ObjectNode node = mapper.createObjectNode();
            node.put("temperature", 0.0);
            node.put("topP", 1.0);
            node.put("doSample", false);
            return node;
        }

        @Override
        public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
            turn++;
            if (turn == 1) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("instanceType", "p5.48xlarge");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_spec\"}]}",
                        List.of(new ToolCall("call-1", "get_instance_spec", arguments)),
                        null);
            }
            if (turn == 2) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("instanceType", "p5.48xlarge");
                arguments.put("purchaseOption", "on_demand");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_price\"}]}",
                        List.of(new ToolCall("call-2", "get_instance_price", arguments)),
                        null);
            }
            return new ModelOutput(
                    "{\"content\":\"final\"}",
                    List.of(),
                    new FinalResponse(
                            "final_answer",
                            "The p5.48xlarge specs are available from the tool output.",
                            mapper.createArrayNode(),
                            mapper.createArrayNode()));
        }
    }

    private final class WrongSerialOrderClient implements ModelClient {
        private int turn;

        @Override
        public String modelId() {
            return "wrong-serial-order-model";
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
            ObjectNode node = mapper.createObjectNode();
            node.put("temperature", 0.0);
            node.put("topP", 1.0);
            node.put("doSample", false);
            return node;
        }

        @Override
        public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
            turn++;
            if (turn == 1) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("instanceType", "g6e.12xlarge");
                arguments.put("modelBillionParameters", 70);
                arguments.put("precision", "bf16");
                arguments.put("mode", "inference");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"check_model_fit\"}]}",
                        List.of(new ToolCall("call-1", "check_model_fit", arguments)),
                        null);
            }
            if (turn == 2) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("instanceType", "g6e.12xlarge");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_price\"}]}",
                        List.of(new ToolCall("call-2", "get_instance_price", arguments)),
                        null);
            }
            if (turn == 3) {
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("instanceType", "g6e.12xlarge");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_spec\"}]}",
                        List.of(new ToolCall("call-3", "get_instance_spec", arguments)),
                        null);
            }
            return new ModelOutput(
                    "{\"content\":\"final\"}",
                    List.of(),
                    new FinalResponse(
                            "final_answer",
                            "The answer has valid structure, but the tool dependency order was wrong.",
                            mapper.createArrayNode(),
                            mapper.createArrayNode()));
        }
    }

    private final class ExtraParallelToolClient implements ModelClient {
        private int turn;

        @Override
        public String modelId() {
            return "extra-parallel-tool-model";
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
            ObjectNode node = mapper.createObjectNode();
            node.put("temperature", 0.0);
            node.put("topP", 1.0);
            node.put("doSample", false);
            return node;
        }

        @Override
        public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
            turn++;
            if (turn == 1) {
                ObjectNode p5Spec = mapper.createObjectNode();
                p5Spec.put("instanceType", "p5.48xlarge");
                ObjectNode p5eSpec = mapper.createObjectNode();
                p5eSpec.put("instanceType", "p5e.48xlarge");
                ObjectNode extraFit = mapper.createObjectNode();
                extraFit.put("instanceType", "p5.48xlarge");
                extraFit.put("modelBillionParameters", 70);
                extraFit.put("precision", "bf16");
                extraFit.put("mode", "fine_tuning");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_spec\"},{\"name\":\"get_instance_spec\"},{\"name\":\"check_model_fit\"}]}",
                        List.of(
                                new ToolCall("call-1", "get_instance_spec", p5Spec),
                                new ToolCall("call-2", "get_instance_spec", p5eSpec),
                                new ToolCall("call-3", "check_model_fit", extraFit)),
                        null);
            }
            if (turn == 2) {
                ObjectNode p5Price = mapper.createObjectNode();
                p5Price.put("instanceType", "p5.48xlarge");
                ObjectNode p5ePrice = mapper.createObjectNode();
                p5ePrice.put("instanceType", "p5e.48xlarge");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_price\"},{\"name\":\"get_instance_price\"}]}",
                        List.of(
                                new ToolCall("call-4", "get_instance_price", p5Price),
                                new ToolCall("call-5", "get_instance_price", p5ePrice)),
                        null);
            }
            return new ModelOutput(
                    "{\"content\":\"final\"}",
                    List.of(),
                    new FinalResponse(
                            "final_answer",
                            "The answer has valid structure, but the parallel group included an extra tool.",
                            mapper.createArrayNode(),
                            mapper.createArrayNode()));
        }
    }

    private final class CollapsedMixedDagClient implements ModelClient {
        private int turn;

        @Override
        public String modelId() {
            return "collapsed-mixed-dag-model";
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
            ObjectNode node = mapper.createObjectNode();
            node.put("temperature", 0.0);
            node.put("topP", 1.0);
            node.put("doSample", false);
            return node;
        }

        @Override
        public ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
            turn++;
            if (turn == 1) {
                ObjectNode p5Spec = mapper.createObjectNode();
                p5Spec.put("instanceType", "p5.48xlarge");
                ObjectNode p5eSpec = mapper.createObjectNode();
                p5eSpec.put("instanceType", "p5e.48xlarge");
                ObjectNode p5Fit = mapper.createObjectNode();
                p5Fit.put("instanceType", "p5.48xlarge");
                p5Fit.put("modelBillionParameters", 70);
                p5Fit.put("precision", "bf16");
                p5Fit.put("mode", "fine_tuning");
                ObjectNode p5eFit = mapper.createObjectNode();
                p5eFit.put("instanceType", "p5e.48xlarge");
                p5eFit.put("modelBillionParameters", 70);
                p5eFit.put("precision", "bf16");
                p5eFit.put("mode", "fine_tuning");
                ObjectNode p5Price = mapper.createObjectNode();
                p5Price.put("instanceType", "p5.48xlarge");
                ObjectNode p5ePrice = mapper.createObjectNode();
                p5ePrice.put("instanceType", "p5e.48xlarge");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"get_instance_spec\"},{\"name\":\"get_instance_spec\"},{\"name\":\"check_model_fit\"},{\"name\":\"check_model_fit\"},{\"name\":\"get_instance_price\"},{\"name\":\"get_instance_price\"}]}",
                        List.of(
                                new ToolCall("call-1", "get_instance_spec", p5Spec),
                                new ToolCall("call-2", "get_instance_spec", p5eSpec),
                                new ToolCall("call-3", "check_model_fit", p5Fit),
                                new ToolCall("call-4", "check_model_fit", p5eFit),
                                new ToolCall("call-5", "get_instance_price", p5Price),
                                new ToolCall("call-6", "get_instance_price", p5ePrice)),
                        null);
            }
            if (turn == 2) {
                ObjectNode recommendation = mapper.createObjectNode();
                recommendation.putArray("candidateInstanceTypes")
                        .add("p5.48xlarge")
                        .add("p5e.48xlarge");
                recommendation.put("modelBillionParameters", 70);
                recommendation.put("precision", "bf16");
                recommendation.put("mode", "fine_tuning");
                recommendation.put("optimizeFor", "cheapest");
                return new ModelOutput(
                        "{\"tool_calls\":[{\"name\":\"recommend_instance\"}]}",
                        List.of(new ToolCall("call-7", "recommend_instance", recommendation)),
                        null);
            }
            return new ModelOutput(
                    "{\"content\":\"final\"}",
                    List.of(),
                    new FinalResponse(
                            "final_answer",
                            "The answer has valid structure, but the DAG dependency layers were collapsed.",
                            mapper.createArrayNode(),
                            mapper.createArrayNode()));
        }
    }
}
