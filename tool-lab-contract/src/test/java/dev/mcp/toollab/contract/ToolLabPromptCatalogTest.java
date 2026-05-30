package dev.mcp.toollab.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolLabPromptCatalogTest {
    @Test
    void baselinePromptConstantMatchesSharedResource() {
        assertEquals(
                ToolLabPromptCatalog.BASELINE_PROMPT,
                ToolLabPromptCatalog.readPromptResource(ToolLabPromptCatalog.BASELINE_PROMPT_RESOURCE));
    }
}
