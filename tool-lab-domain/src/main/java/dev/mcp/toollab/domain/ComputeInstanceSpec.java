package dev.mcp.toollab.domain;

import java.util.List;

public record ComputeInstanceSpec(
        String instanceType,
        String accelerator,
        int acceleratorCount,
        int acceleratorMemoryGib,
        int vcpus,
        int memoryGib,
        int efaInterfaces,
        int networkGbps,
        int hourlyPriceCents,
        List<String> workloads) {

    public int monthlyPriceCents() {
        return hourlyPriceCents * 730;
    }

    public boolean supportsWorkload(String workload) {
        return workloads.stream().anyMatch(item -> item.equals(workload));
    }

    public boolean hasEfa() {
        return efaInterfaces > 0;
    }
}
