package dev.mcp.toollab.eval.validation;

public final class StateValidator {
    public boolean finalStateMatches(String expectedHash, String actualHash) {
        return expectedHash.equals(actualHash);
    }
}
