package dev.mcp.toollab.domain;

import java.util.Map;

public final class ToolLabStateFactory {
    private ToolLabStateFactory() {
    }

    public static ToolLabState defaultState(String taskId) {
        return new ToolLabState(
                taskId,
                Map.of(
                        "proj-alpha", 1_000_000,
                        "proj-beta", 3_000_000,
                        "proj-tight", 350_000),
                Map.of(
                        "g6e.xlarge", 4,
                        "g6e.12xlarge", 2,
                        "g7e.2xlarge", 2,
                        "g7e.24xlarge", 1,
                        "p5.48xlarge", 1,
                        "p5e.48xlarge", 1,
                        "trn2.48xlarge", 1));
    }
}
