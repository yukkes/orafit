package io.github.orafit.translation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Execution plan extracted from one supported Oracle routine-call envelope.
 *
 * <p>The plan describes only JDBC-visible call state. Routine execution remains delegated to the
 * PostgreSQL connection after the SQL envelope has been translated.
 *
 * @param kind procedure/function shape, or NONE
 * @param routine normalized routine name
 * @param returnParameterIndex one-based function return parameter, or zero for procedures
 * @param arguments ordered routine arguments and their bind occurrences
 */
public record CallPlan(
        Kind kind, String routine, int returnParameterIndex, List<Argument> arguments) {
    private static final CallPlan NONE = new CallPlan(Kind.NONE, "", 0, List.of());

    public CallPlan {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        if (routine == null) throw new IllegalArgumentException("routine must not be null");
        arguments = List.copyOf(arguments);
        if (kind == Kind.NONE) {
            if (!routine.isEmpty() || returnParameterIndex != 0 || !arguments.isEmpty()) {
                throw new IllegalArgumentException("invalid empty call plan");
            }
        } else if (routine.isBlank()) {
            throw new IllegalArgumentException("call routine must not be blank");
        } else if (kind == Kind.FUNCTION && returnParameterIndex < 1) {
            throw new IllegalArgumentException("function call requires return parameter");
        } else if (kind == Kind.PROCEDURE && returnParameterIndex != 0) {
            throw new IllegalArgumentException("procedure call cannot have return parameter");
        }
    }

    public static CallPlan none() {
        return NONE;
    }

    public boolean present() {
        return kind != Kind.NONE;
    }

    public boolean function() {
        return kind == Kind.FUNCTION;
    }

    public Set<Integer> outputIndices() {
        return function() ? Set.of(returnParameterIndex) : Set.of();
    }

    /**
     * One logical routine argument and the JDBC binds contributing to it.
     *
     * @param name Oracle named-argument label, or empty for positional arguments
     * @param bindIndices one-based bind occurrences used by the argument expression
     * @param directBindIndex one-based direct bind when the argument is exactly one bind, else zero
     */
    public record Argument(String name, List<Integer> bindIndices, int directBindIndex) {
        public Argument {
            if (name == null) name = "";
            bindIndices = List.copyOf(bindIndices);
            if (directBindIndex < 0
                    || directBindIndex > 0 && !bindIndices.contains(directBindIndex)) {
                throw new IllegalArgumentException("invalid direct bind index");
            }
            Set<Integer> seen = new HashSet<>();
            for (int bind : bindIndices) {
                if (bind < 1 || !seen.add(bind)) {
                    throw new IllegalArgumentException("invalid call argument bind index");
                }
            }
        }

        public boolean named() {
            return !name.isBlank();
        }

        public boolean directBind() {
            return directBindIndex > 0;
        }
    }

    public enum Kind {
        NONE,
        PROCEDURE,
        FUNCTION
    }
}
