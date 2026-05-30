package dev.mcp.toollab.client.quarkus.policy;

import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public final class ToolEvidenceLedger {
    private final Set<EvidenceKey> evidence = ConcurrentHashMap.newKeySet();

    public void recordSuccessfulToolResult(String toolName, Map<String, ?> arguments) {
        Object instanceType = arguments.get("instanceType");
        if (instanceType instanceof String value && !value.isBlank()) {
            evidence.add(new EvidenceKey(toolName, value, fitScope(toolName, arguments)));
        }
    }

    boolean hasEvidence(String toolName, String instanceType, RecommendationWorkload workload) {
        FitScope fitScope = fitScope(toolName, workload);
        if ("check_model_fit".equals(toolName) && FitScope.ANY.equals(fitScope)) {
            return evidence.stream()
                    .anyMatch(key -> toolName.equals(key.toolName()) && instanceType.equals(key.instanceType()));
        }
        return evidence.contains(new EvidenceKey(toolName, instanceType, fitScope));
    }

    private FitScope fitScope(String toolName, Map<String, ?> arguments) {
        if (!"check_model_fit".equals(toolName)) {
            return FitScope.ANY;
        }
        return new FitScope(
                intArgument(arguments, "modelBillionParameters"),
                stringArgument(arguments, "precision"),
                stringArgument(arguments, "mode"));
    }

    private FitScope fitScope(String toolName, RecommendationWorkload workload) {
        return "check_model_fit".equals(toolName) ? workload.fitScope() : FitScope.ANY;
    }

    private static int intArgument(Map<String, ?> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            return Integer.parseInt(string);
        }
        throw new IllegalArgumentException("Missing integer argument: " + name);
    }

    private static String stringArgument(Map<String, ?> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw new IllegalArgumentException("Missing string argument: " + name);
    }

    record RecommendationWorkload(List<String> candidateInstanceTypes, int modelBillionParameters, String precision, String mode) {
        static final RecommendationWorkload ANY = new RecommendationWorkload(List.of(), -1, "*", "*");

        RecommendationWorkload {
            candidateInstanceTypes = List.copyOf(candidateInstanceTypes);
            Objects.requireNonNull(precision, "precision");
            Objects.requireNonNull(mode, "mode");
        }

        FitScope fitScope() {
            return new FitScope(modelBillionParameters, precision, mode);
        }
    }

    private record EvidenceKey(String toolName, String instanceType, FitScope fitScope) {
        private EvidenceKey {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(instanceType, "instanceType");
            Objects.requireNonNull(fitScope, "fitScope");
        }
    }

    private record FitScope(int modelBillionParameters, String precision, String mode) {
        static final FitScope ANY = new FitScope(-1, "*", "*");
    }
}
