package dev.mcp.toollab.eval.validation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.trace.TraceRecord;

public final class ConsistencyScorer {
    private final TraceValidator traceValidator;
    private final StateValidator stateValidator = new StateValidator();

    public ConsistencyScorer(TraceValidator traceValidator) {
        this.traceValidator = traceValidator;
    }

    public void score(TraceRecord record, EvalTask task) {
        ObjectNode scores = (ObjectNode) record.json().path("scores");
        boolean schemaValid = scores.path("schemaValid").asBoolean(false);
        boolean toolSelectionPass = traceValidator.toolSelectionMatches(record, task);
        boolean toolExecutionPass = traceValidator.toolOutcomesMatch(record, task);
        boolean tracePass = traceValidator.traceMatches(record, task);
        boolean finalStatePass = stateValidator.finalStateMatches(
                record.json().path("expectedFinalStateHash").asText(),
                record.json().path("actualFinalStateHash").asText());
        boolean structured = traceValidator.structuredResponseMatches(record, task);
        boolean maxStepFailure = "max_steps_exceeded".equals(record.json().path("completionStatus").asText());

        scores.put("parameterPass", schemaValid);
        scores.put("toolSelectionPass", toolSelectionPass);
        scores.put("toolExecutionPass", toolExecutionPass);
        scores.put("tracePass", tracePass);
        scores.put("finalStatePass", finalStatePass);
        scores.put("structuredResponsePass", structured);
        scores.put("maxStepFailure", maxStepFailure);
        scores.put("overallPass", schemaValid
                && toolSelectionPass
                && toolExecutionPass
                && tracePass
                && finalStatePass
                && structured
                && !maxStepFailure);
    }
}
