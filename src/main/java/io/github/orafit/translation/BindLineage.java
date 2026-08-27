package io.github.orafit.translation;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps each rendered JDBC parameter position to the original application parameter position.
 * Positions are one-based to match JDBC.
 *
 * <p>A rewrite may duplicate, remove, or reorder bind occurrences. This lineage is therefore the
 * authoritative mapping used by the JDBC wrapper after translation.
 *
 * @param inputCount number of parameter positions visible to the application
 * @param outputToInput original application position for each rendered parameter occurrence
 */
public record BindLineage(int inputCount, List<Integer> outputToInput) {
    public BindLineage {
        if (inputCount < 0) throw new IllegalArgumentException("inputCount must be >= 0");
        outputToInput = List.copyOf(outputToInput);
        for (int input : outputToInput) {
            if (input < 1 || input > inputCount) {
                throw new IllegalArgumentException("invalid input bind position: " + input);
            }
        }
    }

    public static BindLineage identity(int count) {
        List<Integer> mapping = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) mapping.add(i);
        return new BindLineage(count, mapping);
    }

    public List<Integer> outputPositions(int inputPosition) {
        if (inputPosition < 1 || inputPosition > inputCount) {
            throw new IllegalArgumentException("invalid input bind position: " + inputPosition);
        }
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < outputToInput.size(); i++) {
            if (outputToInput.get(i) == inputPosition) positions.add(i + 1);
        }
        return List.copyOf(positions);
    }
}
