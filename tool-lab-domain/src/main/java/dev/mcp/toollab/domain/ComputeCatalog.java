package dev.mcp.toollab.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class ComputeCatalog {
    private static final String RESOURCE = "/compute-catalog.json";

    private final Map<String, ComputeInstanceSpec> instances;

    private ComputeCatalog(Map<String, ComputeInstanceSpec> instances) {
        this.instances = Map.copyOf(instances);
    }

    public static ComputeCatalog loadDefault() {
        return loadDefault(new ObjectMapper());
    }

    public static ComputeCatalog loadDefault(ObjectMapper mapper) {
        try (InputStream stream = ComputeCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            List<ComputeInstanceSpec> specs = mapper.readValue(
                    stream,
                    new TypeReference<List<ComputeInstanceSpec>>() {});
            TreeMap<String, ComputeInstanceSpec> byType = new TreeMap<>();
            for (ComputeInstanceSpec spec : specs) {
                byType.put(spec.instanceType(), spec);
            }
            return new ComputeCatalog(byType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load compute catalog", e);
        }
    }

    public Optional<ComputeInstanceSpec> find(String instanceType) {
        return Optional.ofNullable(instances.get(instanceType));
    }

    public ComputeInstanceSpec require(String instanceType) {
        return find(instanceType).orElseThrow(() -> new ToolLabException(
                "UNKNOWN_INSTANCE",
                "Unknown instance type: " + instanceType));
    }

    public List<ComputeInstanceSpec> search(
            String workload,
            int minAcceleratorMemoryGib,
            Integer maxMonthlyCostCents,
            boolean requireEfa) {
        return instances.values().stream()
                .filter(spec -> spec.supportsWorkload(workload))
                .filter(spec -> spec.acceleratorMemoryGib() >= minAcceleratorMemoryGib)
                .filter(spec -> !requireEfa || spec.hasEfa())
                .filter(spec -> maxMonthlyCostCents == null || spec.monthlyPriceCents() <= maxMonthlyCostCents)
                .sorted(Comparator
                        .comparingInt(ComputeInstanceSpec::monthlyPriceCents)
                        .thenComparing(ComputeInstanceSpec::instanceType))
                .toList();
    }

    public Map<String, ComputeInstanceSpec> instances() {
        return instances;
    }
}
