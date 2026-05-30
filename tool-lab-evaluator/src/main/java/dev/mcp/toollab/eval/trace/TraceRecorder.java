package dev.mcp.toollab.eval.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class TraceRecorder {
    private final ObjectMapper mapper;
    private final Path outputPath;

    public TraceRecorder(Path outputPath, ObjectMapper mapper) {
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public void write(Iterable<TraceRecord> records) {
        try {
            Files.createDirectories(outputPath.getParent());
            StringBuilder builder = new StringBuilder();
            for (TraceRecord record : records) {
                builder.append(mapper.writeValueAsString(record.json())).append('\n');
            }
            Files.writeString(outputPath, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write trace JSONL " + outputPath, e);
        }
    }
}
