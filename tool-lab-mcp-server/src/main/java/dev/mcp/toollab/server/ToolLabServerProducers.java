package dev.mcp.toollab.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.contract.ToolPolicy;
import dev.mcp.toollab.contract.ToolPolicyCatalog;
import dev.mcp.toollab.contract.ToolPolicyEvidenceRequirement;
import dev.mcp.toollab.domain.BudgetPolicy;
import dev.mcp.toollab.domain.ComputeCatalog;
import dev.mcp.toollab.domain.ComputeToolService;
import dev.mcp.toollab.domain.ModelFitCalculator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.stream.Collectors;

@ApplicationScoped
public class ToolLabServerProducers {
    @Produces
    @ApplicationScoped
    ComputeCatalog computeCatalog(ObjectMapper mapper) {
        return ComputeCatalog.loadDefault(mapper);
    }

    @Produces
    @ApplicationScoped
    ModelFitCalculator modelFitCalculator() {
        return new ModelFitCalculator();
    }

    @Produces
    @ApplicationScoped
    BudgetPolicy budgetPolicy() {
        return new BudgetPolicy();
    }

    @Produces
    @ApplicationScoped
    ToolLabPromptCatalog promptCatalog() {
        return new ToolLabPromptCatalog();
    }

    @Produces
    @Singleton
    RecommendationPolicyConfig recommendationPolicyConfig() {
        ToolPolicy policy = ToolPolicyCatalog.load(ToolPolicyCatalog.CANDIDATE_RECOMMENDATION_V1);
        return new RecommendationPolicyConfig(
                policy,
                policy.requiredEvidence().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                ToolPolicyEvidenceRequirement::tool,
                                requirement -> requirement)));
    }

    @Produces
    @ApplicationScoped
    ComputeToolService computeToolService(
            ComputeCatalog catalog,
            ModelFitCalculator fitCalculator,
            BudgetPolicy budgetPolicy,
            ObjectMapper mapper) {
        return new ComputeToolService(catalog, fitCalculator, budgetPolicy, mapper);
    }
}
