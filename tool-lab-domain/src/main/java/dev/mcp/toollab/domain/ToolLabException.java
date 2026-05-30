package dev.mcp.toollab.domain;

public final class ToolLabException extends RuntimeException {
    private final String code;

    public ToolLabException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
