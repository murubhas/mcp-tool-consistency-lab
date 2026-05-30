package dev.mcp.toollab.server;

import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.Role;
import io.quarkiverse.mcp.server.TextContent;
import dev.mcp.toollab.server.telemetry.ToolCallRecorder;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ToolLabMcpToolsTest {
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

    @Inject
    ToolLabMcpTools tools;

    @Inject
    ToolCallRecorder recorder;

    @Inject
    ToolLabMcpPrompts prompts;

    @Inject
    ToolLabPromptCatalog promptCatalog;

    @Test
    void toolBeanIsCdiManaged() {
        assertTrue(Arc.container().instance(ToolLabMcpTools.class).isAvailable());
        assertTrue(Arc.container().instance(ToolLabMcpPrompts.class).isAvailable());
        assertTrue(Arc.container().instance(ToolCallRecorder.class).isAvailable());
    }

    @Test
    void exposesExactlyTheNineMilestoneMcpTools() {
        Set<String> actualTools = toolMethods().keySet();

        assertEquals(EXPECTED_TOOLS, actualTools);
    }

    @Test
    void mcpToolParametersUseToolArgAnnotations() {
        for (Method method : toolMethods().values()) {
            assertTrue(Arrays.stream(method.getParameters())
                    .allMatch(parameter -> parameter.isAnnotationPresent(ToolArg.class)
                            || parameter.getType().equals(Meta.class)
                            || parameter.getType().equals(McpConnection.class)));
        }
    }

    @Test
    void mcpToolAnnotationsRepresentReadOnlyAndMutatingMetadata() {
        Map<String, Method> toolMethods = toolMethods();

        assertReadOnly(toolMethods.get("search_instances"));
        assertReadOnly(toolMethods.get("get_instance_spec"));
        assertReadOnly(toolMethods.get("get_instance_price"));
        assertReadOnly(toolMethods.get("check_model_fit"));
        assertReadOnly(toolMethods.get("recommend_instance"));

        assertMutatingIdempotent(toolMethods.get("create_plan"));
        assertMutatingIdempotent(toolMethods.get("allocate_budget"));
        assertMutatingIdempotent(toolMethods.get("reserve_capacity"));
        assertMutatingIdempotent(toolMethods.get("commit_plan"));
    }

    @Test
    void executesToolThroughCdiManagedMcpBean() {
        int before = recorder.calls();

        var result = tools.get_instance_spec("g7e.2xlarge");

        assertEquals("g7e.2xlarge", result.path("instanceType").asText());
        assertEquals(before + 1, recorder.calls());
    }

    @Test
    void exposesStateSnapshotResourceTemplateWithoutAddingBusinessTools() throws Exception {
        Method method = ToolLabMcpTools.class.getDeclaredMethod("tool_lab_state_snapshot", String.class, String.class);

        ResourceTemplate resource = method.getAnnotation(ResourceTemplate.class);

        assertEquals("tool-lab://state/{stateId}/{taskId}", resource.uriTemplate());
        assertEquals("application/json", resource.mimeType());
        assertEquals(EXPECTED_TOOLS, toolMethods().keySet());
    }

    @Test
    void exposesExpectedMcpPromptMethod() {
        Map<String, Method> promptMethods = promptMethods();

        assertEquals(Set.of("compute_tool_calling_prompt"), promptMethods.keySet());
        Method method = promptMethods.get("compute_tool_calling_prompt");
        assertTrue(method.getAnnotation(Prompt.class).description().contains("Tool-calling instruction"));
        assertTrue(Arrays.stream(method.getParameters())
                .allMatch(parameter -> parameter.isAnnotationPresent(PromptArg.class)));
    }

    @Test
    void mcpPromptUsesUserRoleForMcpProtocolSemantics() {
        var prompt = prompts.compute_tool_calling_prompt("baseline");

        assertEquals(Role.USER, prompt.role());
        assertEquals(promptCatalog.resolve("baseline").text(), ((TextContent) prompt.content()).text());
    }

    @Test
    void promptVariantsResolveToDifferentText() {
        String baseline = ((TextContent) prompts.compute_tool_calling_prompt("baseline").content()).text();
        String refined = ((TextContent) prompts.compute_tool_calling_prompt("refined-v1").content()).text();
        String refinedV2 = ((TextContent) prompts.compute_tool_calling_prompt("refined-v2").content()).text();

        assertEquals(promptCatalog.resolve("baseline").text(), baseline);
        assertEquals(promptCatalog.resolve("refined-v1").text(), refined);
        assertEquals(promptCatalog.resolve("refined-v2").text(), refinedV2);
        assertFalse(baseline.equals(refined));
        assertFalse(refined.equals(refinedV2));
    }

    @Test
    void refinedPromptContainsSpecOnlyNoExtraPriceRule() {
        String refined = ((TextContent) prompts.compute_tool_calling_prompt("refined-v1").content()).text();

        assertTrue(refined.contains("For exact instance spec questions, call get_instance_spec"));
        assertTrue(refined.contains("Do not call get_instance_price unless the user asks for price"));
        assertTrue(refined.contains("After sufficient tool results are available"));
    }

    @Test
    void refinedV2PromptContainsDuplicateReadOnlyStopRule() {
        String refined = ((TextContent) prompts.compute_tool_calling_prompt("refined-v2").content()).text();

        assertTrue(refined.contains("the total tool plan is exactly one call: get_instance_spec"));
        assertTrue(refined.contains("do not call any tool again"));
        assertTrue(refined.contains("If the user request is unrelated to accelerated compute planning"));
    }

    private Map<String, Method> toolMethods() {
        return Arrays.stream(ToolLabMcpTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .collect(Collectors.toMap(Method::getName, method -> method));
    }

    private Map<String, Method> promptMethods() {
        return Arrays.stream(ToolLabMcpPrompts.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Prompt.class))
                .collect(Collectors.toMap(Method::getName, method -> method));
    }

    private void assertReadOnly(Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        assertTrue(annotation.annotations().readOnlyHint());
        assertFalse(annotation.annotations().destructiveHint());
        assertTrue(annotation.annotations().idempotentHint());
        assertFalse(annotation.annotations().openWorldHint());
    }

    private void assertMutatingIdempotent(Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        assertFalse(annotation.annotations().readOnlyHint());
        assertFalse(annotation.annotations().destructiveHint());
        assertTrue(annotation.annotations().idempotentHint());
        assertFalse(annotation.annotations().openWorldHint());
    }
}
