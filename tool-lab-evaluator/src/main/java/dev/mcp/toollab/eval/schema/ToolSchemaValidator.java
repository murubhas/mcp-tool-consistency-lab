package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ToolSchemaValidator {
    private final ToolSchemaRegistry registry;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public ToolSchemaValidator(ToolSchemaRegistry registry) {
        this.registry = registry;
    }

    public ValidationResult validate(String toolName, JsonNode arguments) {
        ToolDefinition definition;
        try {
            definition = registry.require(toolName);
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(List.of(e.getMessage()));
        }
        JsonSchema schema = factory.getSchema(definition.inputSchema());
        Set<ValidationMessage> messages = schema.validate(arguments);
        if (messages.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.invalid(messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted(Comparator.naturalOrder())
                .toList());
    }
}
