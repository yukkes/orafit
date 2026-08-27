package io.github.orafit.translation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps Oracle {@code RETURNING ... INTO} output parameters to PostgreSQL RETURNING result columns.
 *
 * @param outputs ordered output-parameter/result-column mappings
 */
public record ReturningPlan(List<Output> outputs) {
    private static final ReturningPlan NONE = new ReturningPlan(List.of());

    public ReturningPlan {
        outputs = List.copyOf(outputs);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            if (output.parameterIndex() < 1
                    || output.resultOrdinal() != i + 1
                    || !seen.add(output.parameterIndex())) {
                throw new IllegalArgumentException("invalid RETURNING output plan");
            }
        }
    }

    public static ReturningPlan none() {
        return NONE;
    }

    public boolean present() {
        return !outputs.isEmpty();
    }

    public boolean isOutput(int parameterIndex) {
        return outputs.stream().anyMatch(output -> output.parameterIndex() == parameterIndex);
    }

    public Set<Integer> outputIndices() {
        Set<Integer> result = new HashSet<>();
        for (Output output : outputs) result.add(output.parameterIndex());
        return Set.copyOf(result);
    }

    /**
     * @param parameterIndex one-based application output-parameter position
     * @param resultOrdinal one-based PostgreSQL RETURNING result-column ordinal
     */
    public record Output(int parameterIndex, int resultOrdinal) {}
}
