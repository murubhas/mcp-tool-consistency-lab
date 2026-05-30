package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSchemaAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void adaptersExposeAllMilestoneTools() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);

        assertEquals(9, new OpenAiCompatibleSchemaAdapter(mapper).adapt(registry).size());
        assertEquals(9, new AnthropicSchemaAdapter(mapper).adapt(registry).size());
        assertTrue(registry.find("commit_plan").orElseThrow().idempotent());
    }

    @Test
    void providerSchemasPreserveCanonicalToolNames() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        Set<String> canonicalNames = new TreeSet<>();
        registry.all().forEach(tool -> canonicalNames.add(tool.name()));

        JsonNode openAiTools = new OpenAiCompatibleSchemaAdapter(mapper).adapt(registry);
        Set<String> openAiNames = new TreeSet<>();
        openAiTools.forEach(tool -> openAiNames.add(tool.path("function").path("name").asText()));

        JsonNode anthropicTools = new AnthropicSchemaAdapter(mapper).adapt(registry);
        Set<String> anthropicNames = new TreeSet<>();
        anthropicTools.forEach(tool -> anthropicNames.add(tool.path("name").asText()));

        assertEquals(canonicalNames, openAiNames);
        assertEquals(canonicalNames, anthropicNames);
    }

    @Test
    void providerSchemasUseStableCanonicalToolOrder() {
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        List<String> expectedNames = registry.all().stream()
                .map(ToolDefinition::name)
                .sorted()
                .toList();

        JsonNode openAiTools = new OpenAiCompatibleSchemaAdapter(mapper).adapt(registry);
        List<String> openAiNames = new ArrayList<>();
        openAiTools.forEach(tool -> openAiNames.add(tool.path("function").path("name").asText()));

        JsonNode anthropicTools = new AnthropicSchemaAdapter(mapper).adapt(registry);
        List<String> anthropicNames = new ArrayList<>();
        anthropicTools.forEach(tool -> anthropicNames.add(tool.path("name").asText()));

        assertEquals(expectedNames, openAiNames);
        assertEquals(expectedNames, anthropicNames);
    }
}
