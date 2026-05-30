package dev.mcp.toollab.domain;

public record ModelFitResult(
        String instanceType,
        int modelBillionParameters,
        String precision,
        String mode,
        int requiredAcceleratorMemoryGib,
        int availableAcceleratorMemoryGib,
        boolean fits,
        int memoryHeadroomGib) {
}
