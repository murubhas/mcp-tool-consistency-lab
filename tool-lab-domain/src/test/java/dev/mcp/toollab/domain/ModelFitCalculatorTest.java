package dev.mcp.toollab.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelFitCalculatorTest {
    @Test
    void calculatesFineTuningMemoryDeterministically() {
        ModelFitCalculator calculator = new ModelFitCalculator();

        assertEquals(560, calculator.requiredAcceleratorMemoryGib(70, "bf16", "fine_tuning"));
        assertEquals(84, calculator.requiredAcceleratorMemoryGib(70, "fp8", "inference"));
    }

    @Test
    void checksFitAgainstCatalogMemory() {
        ComputeInstanceSpec p5 = ComputeCatalog.loadDefault().require("p5.48xlarge");

        ModelFitResult result = new ModelFitCalculator().check(p5, 70, "bf16", "fine_tuning");

        assertTrue(result.fits());
        assertEquals(80, result.memoryHeadroomGib());
    }
}
