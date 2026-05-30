package dev.mcp.toollab.client.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class AgentDemoService {
    static final String CLIENT_MODE = "quarkus-langchain4j-mcp";
    static final List<String> SCENARIOS = List.of("spec", "price", "no-tool", "serial", "parallel", "mixed-dag");

    private final ToolLabAssistant assistant;

    public AgentDemoService(ToolLabAssistant assistant) {
        this.assistant = assistant;
    }

    @ActivateRequestContext
    public List<AgentTranscript> run(String scenario) {
        String normalized = normalize(scenario);
        if ("all".equals(normalized)) {
            return SCENARIOS.stream().map(this::runScenario).toList();
        }
        if (SCENARIOS.contains(normalized)) {
            return List.of(runScenario(normalized));
        }
        throw new IllegalArgumentException("Unknown scenario: " + scenario);
    }

    private AgentTranscript runScenario(String scenario) {
        String prompt = promptFor(scenario);
        String response = assistant.chat(scenario + "-" + UUID.randomUUID(), prompt);
        return new AgentTranscript(scenario, prompt, response, CLIENT_MODE);
    }

    private String promptFor(String scenario) {
        return switch (scenario) {
            case "spec" -> "What are the exact specs for p5.48xlarge?";
            case "price" -> "What is the on-demand price for p5.48xlarge?";
            case "no-tool" -> "Write a short poem about cloud computing.";
            case "serial" -> "For a 70B parameter model in fp8 inference mode, check whether p5.48xlarge fits "
                    + "and then report its on-demand price.";
            case "parallel" -> "Compare p5.48xlarge and p5e.48xlarge on accelerator memory "
                    + "and on-demand monthly price.";
            case "mixed-dag" -> "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. "
                    + "Check specs and fit for both candidates, compare their exact tool-returned prices, "
                    + "then recommend the cheapest valid option.";
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
    }

    private String normalize(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return "all";
        }
        return scenario.trim().toLowerCase(Locale.ROOT);
    }
}
