package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsValidToolArguments() throws Exception {
        ToolSchemaValidator validator = new ToolSchemaValidator(ToolSchemaRegistry.loadDefault(mapper));

        ValidationResult result = validator.validate(
                "check_model_fit",
                mapper.readTree("""
                        {"instanceType":"g6e.xlarge","modelBillionParameters":34,"precision":"fp8","mode":"inference"}
                        """));

        assertTrue(result.valid());
    }

    @Test
    void rejectsMissingRequiredParameters() throws Exception {
        ToolSchemaValidator validator = new ToolSchemaValidator(ToolSchemaRegistry.loadDefault(mapper));

        ValidationResult result = validator.validate(
                "check_model_fit",
                mapper.readTree("""
                        {"instanceType":"g6e.xlarge","precision":"fp8","mode":"inference"}
                        """));

        assertFalse(result.valid());
    }

    @Test
    void acceptsExplicitSchemaDefaultForOptionalParameter() throws Exception {
        ToolSchemaValidator validator = new ToolSchemaValidator(ToolSchemaRegistry.loadDefault(mapper));

        ValidationResult result = validator.validate(
                "get_instance_price",
                mapper.readTree("""
                        {"instanceType":"g7e.2xlarge","purchaseOption":"on_demand"}
                        """));

        assertTrue(result.valid());
    }

    @Test
    void rejectsNonDefaultOptionalParameterValue() throws Exception {
        ToolSchemaValidator validator = new ToolSchemaValidator(ToolSchemaRegistry.loadDefault(mapper));

        ValidationResult result = validator.validate(
                "get_instance_price",
                mapper.readTree("""
                        {"instanceType":"g7e.2xlarge","purchaseOption":"spot"}
                        """));

        assertFalse(result.valid());
    }

    @Test
    void rejectsUnknownExtraParameter() throws Exception {
        ToolSchemaValidator validator = new ToolSchemaValidator(ToolSchemaRegistry.loadDefault(mapper));

        ValidationResult result = validator.validate(
                "get_instance_price",
                mapper.readTree("""
                        {"instanceType":"g7e.2xlarge","purchaseOption":"on_demand","unexpected":"value"}
                        """));

        assertFalse(result.valid());
    }
}
