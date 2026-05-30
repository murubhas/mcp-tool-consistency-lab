package dev.mcp.toollab.server;

import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.Role;
import io.quarkiverse.mcp.server.TextContent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ToolLabMcpPrompts {
    private final ToolLabPromptCatalog promptCatalog;

    @Inject
    public ToolLabMcpPrompts(ToolLabPromptCatalog promptCatalog) {
        this.promptCatalog = promptCatalog;
    }

    @Prompt(description = "Tool-calling instruction prompt template for deterministic compute evaluations.")
    public PromptMessage compute_tool_calling_prompt(
            @PromptArg(description = "Prompt variant: baseline, refined-v1, or refined-v2", defaultValue = "baseline")
                    String variant) {
        return new PromptMessage(Role.USER, new TextContent(promptCatalog.resolve(variant).text()));
    }
}
