package dev.mcp.toollab.domain;

public final class ModelFitCalculator {
    public ModelFitResult check(
            ComputeInstanceSpec spec,
            int modelBillionParameters,
            String precision,
            String mode) {
        int required = requiredAcceleratorMemoryGib(modelBillionParameters, precision, mode);
        int available = spec.acceleratorMemoryGib();
        return new ModelFitResult(
                spec.instanceType(),
                modelBillionParameters,
                precision,
                mode,
                required,
                available,
                available >= required,
                available - required);
    }

    public int requiredAcceleratorMemoryGib(int modelBillionParameters, String precision, String mode) {
        double bytesPerParameter = switch (precision) {
            case "bf16" -> 2.0d;
            case "fp8" -> 1.0d;
            case "int4" -> 0.5d;
            default -> throw new ToolLabException("INVALID_PRECISION", "Unsupported precision: " + precision);
        };
        double multiplier = switch (mode) {
            case "inference" -> 1.2d;
            case "fine_tuning" -> 4.0d;
            default -> throw new ToolLabException("INVALID_MODE", "Unsupported mode: " + mode);
        };
        return (int) Math.ceil(modelBillionParameters * bytesPerParameter * multiplier);
    }
}
