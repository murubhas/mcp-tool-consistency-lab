package dev.mcp.toollab.client.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentDemoCommandScenarioTest {
    @Test
    void explicitScenarioWinsOverQuarkusArgsFallback() {
        assertEquals("price", AgentDemoCommand.resolveScenario("price", "--scenario spec", null));
    }

    @Test
    void devModeFallbackParsesSpaceSeparatedScenarioArgument() {
        assertEquals("spec", AgentDemoCommand.resolveScenario("all", "--scenario spec", null));
    }

    @Test
    void devModeFallbackParsesEqualsScenarioArgument() {
        assertEquals("no-tool", AgentDemoCommand.resolveScenario("all", "--scenario=no-tool", null));
    }

    @Test
    void devModeFallbackParsesSerialAndParallelScenarioArguments() {
        assertEquals("serial", AgentDemoCommand.resolveScenario("all", "--scenario serial", null));
        assertEquals("parallel", AgentDemoCommand.resolveScenario("all", "--scenario=parallel", null));
    }

    @Test
    void devModeFallbackParsesMixedDagScenarioArgument() {
        assertEquals("mixed-dag", AgentDemoCommand.resolveScenario("all", "--scenario mixed-dag", null));
        assertEquals("mixed-dag", AgentDemoCommand.resolveScenario("all", "--scenario=mixed-dag", null));
    }

    @Test
    void packagedJarFallbackParsesScenarioFromJavaCommand() {
        assertEquals("mixed-dag", AgentDemoCommand.resolveScenario(
                "all", null, "target/quarkus-app/quarkus-run.jar --scenario mixed-dag"));
        assertEquals("parallel", AgentDemoCommand.resolveScenario(
                "all", null, "target/quarkus-app/quarkus-run.jar --scenario=parallel"));
    }

    @Test
    void allRemainsDefaultWhenNoScenarioArgumentIsProvided() {
        assertEquals("all", AgentDemoCommand.resolveScenario("all", "--help", "target/quarkus-app/quarkus-run.jar"));
    }
}
