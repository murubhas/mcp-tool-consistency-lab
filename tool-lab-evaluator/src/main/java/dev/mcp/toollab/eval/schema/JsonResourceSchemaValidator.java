package dev.mcp.toollab.eval.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JsonResourceSchemaValidator {
    private final ObjectMapper mapper;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public JsonResourceSchemaValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ValidationResult validateResource(String schemaResource, JsonNode value) {
        JsonSchema schema = factory.getSchema(load(schemaResource));
        Set<ValidationMessage> messages = schema.validate(value);
        if (messages.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.invalid(messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted(Comparator.naturalOrder())
                .toList());
    }

    public JsonNode load(String resource) {
        try (InputStream stream = JsonResourceSchemaValidator.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing schema resource " + resource);
            }
            return mapper.readTree(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resource, e);
        }
    }
}
