package dev.mcp.toollab.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ToolLabPromptCatalog {
    public static final String DEFAULT_VARIANT = "baseline";
    public static final String REFINED_V1 = "refined-v1";
    public static final String REFINED_V2 = "refined-v2";
    public static final String SOURCE = "catalog";
    public static final String BASELINE_PROMPT_RESOURCE = "prompts/tool-lab-baseline-system.txt";
    public static final String BASELINE_PROMPT = """
            You are evaluating deterministic tool calling. Use the provided tools when needed.
            After all needed tool results are available, respond with only one JSON object.
            Do not use markdown, code fences, prose before or after JSON, or hidden reasoning in the final answer.
            Required fields: responseType, message, claims, missingFields.
            responseType must be one of: final_answer, clarification, no_tool_applicable, cannot_complete.
            message must be a string. claims and missingFields must be arrays.
            If no fields are missing, missingFields must be []. If no structured claims are made, claims must be [].
            Example final answer: {"responseType":"final_answer","message":"The requested result is available from the tool output.","claims":[],"missingFields":[]}
            """;

    private static final Map<String, String> PROMPTS = Map.of(
            DEFAULT_VARIANT,
            BASELINE_PROMPT,
            REFINED_V1,
            """
            You are evaluating deterministic tool calling. Use the provided tools only when needed by the user's request.
            Call only tools required by the user request; do not call extra tools to add nice-to-have information.
            For exact instance spec questions, call get_instance_spec for the requested instance type and then answer.
            Do not call get_instance_price unless the user asks for price, cost, budget, spend, rate, or monthly/hourly pricing.
            Do not call budget, capacity, or plan mutation tools unless the user asks to create, allocate, reserve, or commit a plan.
            After sufficient tool results are available, produce the final structured JSON immediately.
            The final assistant response must be exactly one JSON object.
            Do not use markdown, code fences, prose before or after JSON, or hidden reasoning in the final answer.
            Required fields: responseType, message, claims, missingFields.
            responseType must be one of: final_answer, clarification, no_tool_applicable, cannot_complete.
            message must be a string. claims and missingFields must be arrays.
            If no fields are missing, missingFields must be []. If no structured claims are made, claims must be [].
            Example final answer: {"responseType":"final_answer","message":"The requested result is available from the tool output.","claims":[],"missingFields":[]}
            """,
            REFINED_V2,
            """
            You are evaluating deterministic tool calling. Use the provided tools only when needed by the user's request.
            Call only tools required by the user request; do not call extra tools to add nice-to-have information.
            For exact instance spec questions about one instance type, the total tool plan is exactly one call: get_instance_spec for that instance type.
            After a successful get_instance_spec result for the requested instance type is present, do not call any tool again; the next assistant message must be the final structured JSON.
            For exact instance spec questions, do not call get_instance_price, check_model_fit, search_instances, recommend_instance, or mutation tools.
            For price or monthly cost questions about one instance type, the total tool plan is exactly one call: get_instance_price for that instance type. After the price result, answer immediately.
            Do not call get_instance_price unless the user asks for price, cost, budget, spend, rate, or monthly/hourly pricing.
            Do not call budget, capacity, or plan mutation tools unless the user asks to create, allocate, reserve, or commit a plan.
            If the user request is unrelated to accelerated compute planning, call no tools and respond with responseType no_tool_applicable.
            After sufficient tool results are available, produce the final structured JSON immediately.
            The final assistant response must be exactly one JSON object.
            Do not use markdown, code fences, prose before or after JSON, or hidden reasoning in the final answer.
            Required fields: responseType, message, claims, missingFields.
            responseType must be one of: final_answer, clarification, no_tool_applicable, cannot_complete.
            message must be a string. claims and missingFields must be arrays.
            If no fields are missing, missingFields must be []. If no structured claims are made, claims must be [].
            Example final answer: {"responseType":"final_answer","message":"The requested result is available from the tool output.","claims":[],"missingFields":[]}
            """);

    public ToolLabPrompt resolve(String variant) {
        String resolvedVariant = variant == null || variant.isBlank() ? DEFAULT_VARIANT : variant.trim();
        String text = PROMPTS.get(resolvedVariant);
        if (text == null) {
            throw new IllegalArgumentException("Unknown prompt variant: " + resolvedVariant
                    + ". Supported variants: " + String.join(", ", variants()));
        }
        return new ToolLabPrompt(resolvedVariant, SOURCE, text, Hashing.sha256(resolvedVariant + "\n" + text));
    }

    public Set<String> variants() {
        return new TreeMap<>(PROMPTS).keySet();
    }

    public static String readPromptResource(String resource) {
        try (InputStream stream = ToolLabPromptCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing prompt resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + resource, e);
        }
    }
}
