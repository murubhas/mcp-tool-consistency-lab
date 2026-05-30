package dev.mcp.toollab.eval.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.trace.TraceRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
    private final TraceValidator validator = new TraceValidator(AcceptedTraceSet.loadDefault(mapper), registry, mapper);

    @Test
    void readOnlyParallelCallsCanonicalizeAsUnordered() throws Exception {
        JsonNode first = mapper.readTree("""
                [{"toolCalls":[
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}}
                ]}]
                """);
        JsonNode reversed = mapper.readTree("""
                [{"toolCalls":[
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}}
                ]}]
                """);

        assertEquals(
                CanonicalJson.writeCanonical(validator.canonicalTrace(first)),
                CanonicalJson.writeCanonical(validator.canonicalTrace(reversed)));
    }

    @Test
    void mixedReadOnlySameTurnCallsCanonicalizeAsUnorderedWithinGroup() throws Exception {
        JsonNode first = mapper.readTree("""
                [{"toolCalls":[
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                  {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                  {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                ]}]
                """);
        JsonNode shuffled = mapper.readTree("""
                [{"toolCalls":[
                  {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}},
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                  {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                  {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}}
                ]}]
                """);

        assertEquals(
                CanonicalJson.writeCanonical(validator.canonicalTrace(first)),
                CanonicalJson.writeCanonical(validator.canonicalTrace(shuffled)));
    }

    @Test
    void acceptedParallelSpecAndPriceGroupsPassWithShuffledCallOrder() throws Exception {
        TraceRecord shuffled = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}}
                  ]}
                ]
                """);

        assertTrue(validator.toolSelectionMatches(shuffled, parallelSpecsTask()));
        assertTrue(validator.traceMatches(shuffled, parallelSpecsTask()));
    }

    @Test
    void missingParallelReadOnlyCallFailsAcceptedTrace() throws Exception {
        TraceRecord missingSpec = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(missingSpec, parallelSpecsTask()));
        assertFalse(validator.traceMatches(missingSpec, parallelSpecsTask()));
    }

    @Test
    void mutatingCallsCanonicalizeAsOrderSensitive() throws Exception {
        JsonNode first = mapper.readTree("""
                [{"toolCalls":[
                  {"name":"create_plan","arguments":{"idempotencyKey":"a"}},
                  {"name":"commit_plan","arguments":{"planId":"plan-a","idempotencyKey":"b"}}
                ]}]
                """);
        JsonNode reversed = mapper.readTree("""
                [{"toolCalls":[
                  {"name":"commit_plan","arguments":{"planId":"plan-a","idempotencyKey":"b"}},
                  {"name":"create_plan","arguments":{"idempotencyKey":"a"}}
                ]}]
                """);

        assertNotEquals(
                CanonicalJson.writeCanonical(validator.canonicalTrace(first)),
                CanonicalJson.writeCanonical(validator.canonicalTrace(reversed)));
    }

    @Test
    void serialReadOnlyDependencyStepsRemainOrderSensitive() throws Exception {
        TraceRecord wrongOrder = traceRecord("""
                [
                  {"toolCalls":[{"name":"check_model_fit","arguments":{"instanceType":"g6e.12xlarge","modelBillionParameters":70,"precision":"bf16","mode":"inference"}}]},
                  {"toolCalls":[{"name":"get_instance_price","arguments":{"instanceType":"g6e.12xlarge"}}]},
                  {"toolCalls":[{"name":"get_instance_spec","arguments":{"instanceType":"g6e.12xlarge"}}]}
                ]
                """);

        assertFalse(validator.traceMatches(wrongOrder, serialSpecFitPriceTask()));
    }

    @Test
    void serialReadOnlyDependencyStepsCannotCollapseIntoSameTurnGroup() throws Exception {
        TraceRecord sameTurn = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"g6e.12xlarge"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"g6e.12xlarge","modelBillionParameters":70,"precision":"bf16","mode":"inference"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"g6e.12xlarge"}}
                  ]}
                ]
                """);

        assertFalse(validator.traceMatches(sameTurn, serialSpecFitPriceTask()));
    }

    @Test
    void acceptedMixedDagPassesWithShuffledParallelLayers() throws Exception {
        TraceRecord shuffled = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertTrue(validator.toolSelectionMatches(shuffled, mixedDagTask()));
        assertTrue(validator.traceMatches(shuffled, mixedDagTask()));
    }

    @Test
    void mixedDagEvidenceLayersCannotCollapseIntoOneReadOnlyGroup() throws Exception {
        TraceRecord sameTurn = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(sameTurn, mixedDagTask()));
        assertFalse(validator.traceMatches(sameTurn, mixedDagTask()));
    }

    @Test
    void mixedDagAdjacentSpecAndFitLayersCannotCollapse() throws Exception {
        TraceRecord collapsed = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(collapsed, mixedDagTask()));
        assertFalse(validator.traceMatches(collapsed, mixedDagTask()));
    }

    @Test
    void mixedDagAdjacentFitAndPriceLayersCannotCollapse() throws Exception {
        TraceRecord collapsed = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(collapsed, mixedDagTask()));
        assertFalse(validator.traceMatches(collapsed, mixedDagTask()));
    }

    @Test
    void mixedDagJoinCannotRunBeforePriceEvidence() throws Exception {
        TraceRecord wrongJoinOrder = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(wrongJoinOrder, mixedDagTask()));
        assertFalse(validator.traceMatches(wrongJoinOrder, mixedDagTask()));
    }

    @Test
    void mixedDagPriceBeforeFitFails() throws Exception {
        TraceRecord wrongOrder = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(wrongOrder, mixedDagTask()));
        assertFalse(validator.traceMatches(wrongOrder, mixedDagTask()));
    }

    @Test
    void mixedDagMissingFitEvidenceFails() throws Exception {
        TraceRecord missingFit = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_price","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(missingFit, mixedDagTask()));
        assertFalse(validator.traceMatches(missingFit, mixedDagTask()));
    }

    @Test
    void mixedDagMissingPriceEvidenceFails() throws Exception {
        TraceRecord missingPrice = traceRecord("""
                [
                  {"toolCalls":[
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}},
                    {"name":"get_instance_spec","arguments":{"instanceType":"p5e.48xlarge"}}
                  ]},
                  {"toolCalls":[
                    {"name":"check_model_fit","arguments":{"instanceType":"p5.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}},
                    {"name":"check_model_fit","arguments":{"instanceType":"p5e.48xlarge","modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning"}}
                  ]},
                  {"toolCalls":[
                    {"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge"}}
                  ]},
                  {"toolCalls":[{"name":"recommend_instance","arguments":{"candidateInstanceTypes":["p5.48xlarge","p5e.48xlarge"],"modelBillionParameters":70,"precision":"bf16","mode":"fine_tuning","optimizeFor":"cheapest"}}]}
                ]
                """);

        assertFalse(validator.toolSelectionMatches(missingPrice, mixedDagTask()));
        assertFalse(validator.traceMatches(missingPrice, mixedDagTask()));
    }

    @Test
    void firstAcceptedTraceRetainsDeclaredToolFailure() {
        EvalTask task = new EvalTask(
                "compute.error.unknown-plan.001",
                "compute.error.unknown-plan",
                "eval",
                "compute_planning",
                "error_recovery",
                "Commit a missing plan.",
                "default",
                "cannot_complete",
                2);

        JsonNode accepted = validator.firstAcceptedTrace(task);

        assertEquals(
                "UNKNOWN_PLAN",
                accepted.path("steps").path(0).path("toolCalls").path(0).path("expectedErrorCode").asText());
    }

    @Test
    void optionalDefaultArgumentsMatchWhenActualProvidesSchemaDefault() throws Exception {
        TraceRecord record = traceRecord("""
                [{"toolCalls":[
                  {"name":"get_instance_price","arguments":{"instanceType":"g7e.2xlarge","purchaseOption":"on_demand"}}
                ]}]
                """);

        assertTrue(validator.traceMatches(record, priceTask()));
    }

    @Test
    void optionalDefaultArgumentsDoNotHideNonDefaultValues() throws Exception {
        TraceRecord record = traceRecord("""
                [{"toolCalls":[
                  {"name":"get_instance_price","arguments":{"instanceType":"g7e.2xlarge","purchaseOption":"spot"}}
                ]}]
                """);

        assertFalse(validator.traceMatches(record, priceTask()));
    }

    @Test
    void optionalDefaultArgumentsDoNotHideUnknownExtraArguments() throws Exception {
        TraceRecord record = traceRecord("""
                [{"toolCalls":[
                  {"name":"get_instance_price","arguments":{"instanceType":"g7e.2xlarge","unexpected":"value"}}
                ]}]
                """);

        assertFalse(validator.traceMatches(record, priceTask()));
    }

    @Test
    void extraReadOnlyToolInvocationStillFailsAcceptedTrace() throws Exception {
        TraceRecord record = traceRecord("""
                [
                  {"toolCalls":[{"name":"get_instance_spec","arguments":{"instanceType":"p5.48xlarge"}}]},
                  {"toolCalls":[{"name":"get_instance_price","arguments":{"instanceType":"p5.48xlarge","purchaseOption":"on_demand"}}]}
                ]
                """);

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

        assertFalse(validator.toolSelectionMatches(record, task));
        assertFalse(validator.traceMatches(record, task));
    }

    private TraceRecord traceRecord(String stepsJson) throws Exception {
        var root = mapper.createObjectNode();
        root.set("steps", mapper.readTree(stepsJson));
        root.set("finalResponse", mapper.readTree("""
                {"responseType":"final_answer","message":"ok","claims":[],"missingFields":[]}
                """));
        return new TraceRecord(root);
    }

    private EvalTask priceTask() {
        return new EvalTask(
                "compute.single.price.002",
                "compute.single.price",
                "eval",
                "compute_planning",
                "single_tool",
                "What is the monthly on-demand price for g7e.2xlarge?",
                "default",
                "final_answer",
                4);
    }

    private EvalTask serialSpecFitPriceTask() {
        return new EvalTask(
                "compute.seq.spec-fit-price.002",
                "compute.seq.spec-fit-price",
                "eval",
                "compute_planning",
                "sequential",
                "For g6e.12xlarge, check specs, whether a 70B BF16 inference model fits, and monthly price.",
                "default",
                "final_answer",
                6);
    }

    private EvalTask parallelSpecsTask() {
        return new EvalTask(
                "compute.parallel.specs.001",
                "compute.parallel.specs",
                "eval",
                "compute_planning",
                "parallel",
                "Compare p5.48xlarge and p5e.48xlarge specs and prices for 70B BF16 fine-tuning.",
                "default",
                "final_answer",
                6);
    }

    private EvalTask mixedDagTask() {
        return new EvalTask(
                "compute.mixed-dag.fit-price-recommend.001",
                "compute.mixed-dag.fit-price-recommend",
                "eval",
                "compute_planning",
                "mixed_dag",
                "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. Check specs and fit for both candidates, compare their exact tool-returned prices, then recommend the cheapest valid option.",
                "default",
                "final_answer",
                7);
    }
}
