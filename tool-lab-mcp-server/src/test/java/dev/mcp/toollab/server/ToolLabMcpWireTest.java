package dev.mcp.toollab.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ToolLabMcpWireTest {
    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "search_instances",
            "get_instance_spec",
            "get_instance_price",
            "check_model_fit",
            "create_plan",
            "allocate_budget",
            "reserve_capacity",
            "commit_plan",
            "recommend_instance");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mcpWireListsExpectedToolsAndSchemas() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            client.when().toolsList(page -> {
                Set<String> actualTools = page.tools().stream()
                        .map(McpAssured.ToolInfo::name)
                        .collect(Collectors.toSet());

                assertEquals(EXPECTED_TOOLS, actualTools);
                var spec = page.findByName("get_instance_spec");
                assertNotNull(spec);
                assertEquals("object", spec.inputSchema().getString("type"));
                assertTrue(spec.inputSchema().getJsonObject("properties").containsKey("instanceType"));
                assertTrue(spec.annotations().orElseThrow().readOnlyHint());
            });
        }
    }

    @Test
    void mcpWireToolCallReturnsStructuredAndTextContent() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            client.when().toolsCall(
                    "get_instance_spec",
                    Map.of("instanceType", "p5.48xlarge"),
                    response -> {
                        assertFalse(response.isError());
                        JsonNode structured = mapper.valueToTree(response.structuredContent());
                        assertEquals("p5.48xlarge", structured.path("instanceType").asText());
                        assertEquals(640, structured.path("acceleratorMemoryGib").asInt());

                        JsonNode textJson = parseTextContent(response);
                        assertEquals(structured.path("instanceType").asText(), textJson.path("instanceType").asText());
                        assertEquals(
                                structured.path("acceleratorMemoryGib").asInt(),
                                textJson.path("acceleratorMemoryGib").asInt());
                    });
        }
    }

    @Test
    void mcpWireExpectedToolErrorIncludesDomainCodeInContent() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            client.when().toolsCall(
                    "commit_plan",
                    Map.of(
                            "planId", "plan-missing",
                            "idempotencyKey", "commit-missing"),
                    response -> {
                        assertTrue(response.isError());
                        String text = response.firstContent().asText().text();
                        assertTrue(text.startsWith("UNKNOWN_PLAN:"));
                        assertTrue(text.contains("plan-missing"));
                    });
        }
    }

    @Test
    void recommendInstanceWithoutEvidenceFailsPolicyGuardrail() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            client.when().toolsCall(
                    "recommend_instance",
                    recommendArgs(),
                    response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text()
                                .startsWith(RecommendationPolicyInputGuardrail.POLICY_PRECONDITION_MISSING + ":"));
                    });
        }
    }

    @Test
    void recommendationGuardrailIsScopedToMixedDagPolicyCase() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            client.when().toolsCall(
                    "recommend_instance",
                    Map.of(
                            "candidateInstanceTypes", List.of("g6e.xlarge", "g7e.2xlarge"),
                            "modelBillionParameters", 34,
                            "precision", "fp8",
                            "mode", "inference",
                            "optimizeFor", "cheapest",
                            "requireEfa", false),
                    response -> assertFalse(response.isError()));
        }
    }

    @Test
    void recommendInstanceSucceedsAfterRequiredEvidenceOverMcpWire() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            recordAllEvidence(client, null);

            client.when().toolsCall(
                    "recommend_instance",
                    recommendArgs(),
                    response -> {
                        assertFalse(response.isError());
                        JsonNode structured = mapper.valueToTree(response.structuredContent());
                        assertEquals("p5.48xlarge", structured.path("recommendedInstanceType").asText());
                        assertTrue(structured.path("fits").asBoolean());
                    });
        }
    }

    @Test
    void mismatchedFitEvidenceDoesNotAuthorizeRecommendation() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            for (String instanceType : List.of("p5.48xlarge", "p5e.48xlarge")) {
                callTool(client, "get_instance_spec", Map.of("instanceType", instanceType), null);
                callTool(client, "check_model_fit", mismatchedFitArgs(instanceType), null);
                callTool(client, "get_instance_price", priceArgs(instanceType), null);
            }

            client.when().toolsCall(
                    "recommend_instance",
                    recommendArgs(),
                    response -> {
                        assertTrue(response.isError());
                        String text = response.firstContent().asText().text();
                        assertTrue(text.startsWith(RecommendationPolicyInputGuardrail.POLICY_PRECONDITION_MISSING + ":"));
                        assertTrue(text.contains("p5.48xlarge:fit"));
                        assertTrue(text.contains("p5e.48xlarge:fit"));
                    });
        }
    }

    @Test
    void policyEvidenceIsMcpStateScoped() {
        try (var client = McpAssured.newConnectedStreamableClient()) {
            recordAllEvidence(client, "state-with-evidence");

            client.when().toolsCall("recommend_instance")
                    .withArguments(recommendArgs())
                    .withMetadata(Map.of(ToolLabMcpTools.META_STATE_ID, "state-without-evidence"))
                    .withAssert(response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text()
                                .startsWith(RecommendationPolicyInputGuardrail.POLICY_PRECONDITION_MISSING + ":"));
                    })
                    .send();
        }
    }

    private JsonNode parseTextContent(ToolResponse response) {
        assertFalse(response.content().isEmpty());
        assertEquals("text", response.firstContent().getType());
        try {
            return mapper.readTree(response.firstContent().asText().text());
        } catch (Exception e) {
            throw new AssertionError("MCP text content was not JSON", e);
        }
    }

    private void recordAllEvidence(McpTestClient<?, ?> client, String stateId) {
        for (String instanceType : List.of("p5.48xlarge", "p5e.48xlarge")) {
            callTool(client, "get_instance_spec", Map.of("instanceType", instanceType), stateId);
            callTool(client, "check_model_fit", fitArgs(instanceType), stateId);
            callTool(client, "get_instance_price", priceArgs(instanceType), stateId);
        }
    }

    private void callTool(McpTestClient<?, ?> client, String toolName, Map<String, Object> args, String stateId) {
        var call = client.when().toolsCall(toolName)
                .withArguments(args)
                .withAssert(response -> assertFalse(response.isError()));
        if (stateId != null) {
            call.withMetadata(Map.of(ToolLabMcpTools.META_STATE_ID, stateId));
        }
        call.send();
    }

    private static Map<String, Object> fitArgs(String instanceType) {
        return Map.of(
                "instanceType", instanceType,
                "modelBillionParameters", 70,
                "precision", "bf16",
                "mode", "fine_tuning");
    }

    private static Map<String, Object> mismatchedFitArgs(String instanceType) {
        return Map.of(
                "instanceType", instanceType,
                "modelBillionParameters", 34,
                "precision", "fp8",
                "mode", "inference");
    }

    private static Map<String, Object> priceArgs(String instanceType) {
        return Map.of(
                "instanceType", instanceType,
                "purchaseOption", "on_demand");
    }

    private static Map<String, Object> recommendArgs() {
        return Map.of(
                "candidateInstanceTypes", List.of("p5.48xlarge", "p5e.48xlarge"),
                "modelBillionParameters", 70,
                "precision", "bf16",
                "mode", "fine_tuning",
                "optimizeFor", "cheapest",
                "requireEfa", false);
    }
}
