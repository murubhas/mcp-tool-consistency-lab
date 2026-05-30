package dev.mcp.toollab.server.telemetry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ToolCallRecorder {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public void record(String toolName, boolean success, long durationNanos) {
        calls.incrementAndGet();
        if (!success) {
            failures.incrementAndGet();
        }
    }

    public int calls() {
        return calls.get();
    }

    public int failures() {
        return failures.get();
    }
}
