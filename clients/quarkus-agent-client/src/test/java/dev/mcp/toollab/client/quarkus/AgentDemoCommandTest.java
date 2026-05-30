package dev.mcp.toollab.client.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

@QuarkusMainTest
class AgentDemoCommandTest {
    @Test
    @Launch({ "--help" })
    void helpDoesNotRequireLiveMcpServer(LaunchResult result) {
        assertEquals(0, result.exitCode());
        assertTrue(result.getOutput().contains("Run canned scenarios against the Tool Lab MCP server."),
                result.getOutput());
        assertTrue(result.getOutput().contains("mixed-dag"), result.getOutput());
    }
}
