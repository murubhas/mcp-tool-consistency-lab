package dev.mcp.toollab.client.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentDemoServiceTest {
    @Test
    void exactSpecScenarioCallsAiServiceAndBuildsTranscript() {
        ToolLabAssistant assistant = (memoryId, message) -> "p5.48xlarge has 8 H100 accelerators.";
        AgentDemoService service = new AgentDemoService(assistant);

        AgentTranscript transcript = service.run("spec").getFirst();

        assertEquals("spec", transcript.scenarioId());
        assertEquals("What are the exact specs for p5.48xlarge?", transcript.userPrompt());
        assertEquals("quarkus-langchain4j-mcp", transcript.clientMode());
        assertTrue(transcript.finalAssistantAnswer().contains("8 H100 accelerators"));
    }

    @Test
    void noToolScenarioStillUsesAiServiceWithoutFakeToolFields() {
        ToolLabAssistant assistant = (memoryId, message) -> "Clouds gather code into quiet light.";
        AgentDemoService service = new AgentDemoService(assistant);

        AgentTranscript transcript = service.run("no-tool").getFirst();

        assertEquals("no-tool", transcript.scenarioId());
        assertEquals("quarkus-langchain4j-mcp", transcript.clientMode());
        assertEquals("Clouds gather code into quiet light.", transcript.finalAssistantAnswer());
    }

    @Test
    void allScenarioRunsSixStablePromptsInOrderWithoutLiveNetwork() {
        List<String> prompts = new ArrayList<>();
        List<String> memoryIds = new ArrayList<>();
        ToolLabAssistant assistant = (memoryId, message) -> {
            memoryIds.add(memoryId);
            prompts.add(message);
            return "answer";
        };
        AgentDemoService service = new AgentDemoService(assistant);

        List<AgentTranscript> transcripts = service.run("all");

        assertEquals(List.of("spec", "price", "no-tool", "serial", "parallel", "mixed-dag"),
                transcripts.stream().map(AgentTranscript::scenarioId).toList());
        assertEquals(List.of(
                "What are the exact specs for p5.48xlarge?",
                "What is the on-demand price for p5.48xlarge?",
                "Write a short poem about cloud computing.",
                "For a 70B parameter model in fp8 inference mode, check whether p5.48xlarge fits "
                        + "and then report its on-demand price.",
                "Compare p5.48xlarge and p5e.48xlarge on accelerator memory and on-demand monthly price.",
                "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. "
                        + "Check specs and fit for both candidates, compare their exact tool-returned prices, "
                        + "then recommend the cheapest valid option."),
                prompts);
        assertTrue(prompts.stream().noneMatch(prompt -> prompt.toLowerCase().contains("previous")));
        assertTrue(prompts.stream().noneMatch(prompt -> prompt.toLowerCase().contains("again")));
        assertEquals(6, memoryIds.stream().distinct().count());
        assertTrue(memoryIds.stream()
                .allMatch(memoryId -> memoryId.matches("(spec|price|no-tool|serial|parallel|mixed-dag)-.+")));
    }

    @Test
    void parallelScenarioUsesCatalogSupportedInstances() {
        ToolLabAssistant assistant = (memoryId, message) -> message;
        AgentDemoService service = new AgentDemoService(assistant);

        AgentTranscript transcript = service.run("parallel").getFirst();

        assertEquals("parallel", transcript.scenarioId());
        assertEquals("Compare p5.48xlarge and p5e.48xlarge on accelerator memory and on-demand monthly price.",
                transcript.userPrompt());
        assertEquals(transcript.userPrompt(), transcript.finalAssistantAnswer());
    }

    @Test
    void mixedDagScenarioUsesEvaluatorAlignedPrompt() {
        ToolLabAssistant assistant = (memoryId, message) -> message;
        AgentDemoService service = new AgentDemoService(assistant);

        AgentTranscript transcript = service.run("mixed-dag").getFirst();

        assertEquals("mixed-dag", transcript.scenarioId());
        assertEquals(
                "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. "
                        + "Check specs and fit for both candidates, compare their exact tool-returned prices, "
                        + "then recommend the cheapest valid option.",
                transcript.userPrompt());
        assertEquals(transcript.userPrompt(), transcript.finalAssistantAnswer());
    }
}
