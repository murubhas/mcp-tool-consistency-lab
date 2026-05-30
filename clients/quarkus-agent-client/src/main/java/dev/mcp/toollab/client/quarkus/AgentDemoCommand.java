package dev.mcp.toollab.client.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@ApplicationScoped
@Command(
        name = "agent-demo",
        mixinStandardHelpOptions = true,
        description = "Run canned scenarios against the Tool Lab MCP server.")
public class AgentDemoCommand implements Runnable {
    private final AgentDemoService demoService;

    @Option(
            names = "--scenario",
            defaultValue = "all",
            description = "Scenario to run: all, spec, price, no-tool, serial, parallel, or mixed-dag.")
    String scenario;

    public AgentDemoCommand(AgentDemoService demoService) {
        this.demoService = demoService;
    }

    @Override
    public void run() {
        for (AgentTranscript transcript : demoService.run(resolveScenario(
                scenario, System.getProperty("quarkus.args"), System.getProperty("sun.java.command")))) {
            print(transcript);
        }
    }

    static String resolveScenario(String scenario, String quarkusArgs, String javaCommand) {
        String effectiveScenario = scenario == null || scenario.isBlank() ? "all" : scenario;
        if (!"all".equals(effectiveScenario.trim())) {
            return effectiveScenario;
        }

        String fromQuarkusArgs = scenarioFromArgs(quarkusArgs);
        if (fromQuarkusArgs != null) {
            return fromQuarkusArgs;
        }

        // Supports packaged-jar/direct args and Quarkus dev-mode paths; acceptable for this demo on standard JDKs.
        String fromJavaCommand = scenarioFromArgs(javaCommand);
        if (fromJavaCommand != null) {
            return fromJavaCommand;
        }

        return effectiveScenario;
    }

    private static String scenarioFromArgs(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }

        String[] parts = args.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("--scenario=")) {
                return part.substring("--scenario=".length());
            }
            if ("--scenario".equals(part) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private void print(AgentTranscript transcript) {
        System.out.printf("Scenario: %s%n", transcript.scenarioId());
        System.out.printf("User: %s%n", transcript.userPrompt());
        System.out.printf("Client mode: %s%n", transcript.clientMode());
        System.out.printf("Final: %s%n%n", transcript.finalAssistantAnswer());
    }
}
