package io.github.orafit.rewrite;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.schema.Sequence;
import net.sf.jsqlparser.schema.Sequence.Parameter;
import net.sf.jsqlparser.schema.Sequence.ParameterType;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.sequence.AlterSequence;

import java.util.ArrayList;
import java.util.List;

/** Lowers Oracle sequence restart semantics without losing the sequence's declared minimum. */
final class SequenceRestartRule {
    Result rewrite(Statement statement) throws TranslationException {
        if (!(statement instanceof AlterSequence alter)) return Result.unchanged();
        Sequence sequence = alter.getSequence();
        List<Parameter> parameters = sequence.getParameters();
        if (parameters == null || parameters.isEmpty()) return Result.unchanged();

        int restart = indexOf(parameters, "RESTART", false);
        if (restart < 0) return Result.unchanged();
        int startWith = indexOf(parameters, "START WITH", true);
        if (startWith >= 0) {
            rejectCombinedOptions(parameters, 2);
            Long value = parameters.get(startWith).getValue();
            List<Parameter> rewritten = new ArrayList<>(parameters);
            rewritten.remove(Math.max(restart, startWith));
            rewritten.remove(Math.min(restart, startWith));
            rewritten.add(
                    Math.min(restart, startWith),
                    new Parameter(ParameterType.RESTART_WITH).withValue(value));
            sequence.setParameters(rewritten);
            return new Result(true, null);
        }

        rejectCombinedOptions(parameters, 1);
        String name = sequence.getFullyQualifiedName().replace("'", "''");
        return new Result(
                true,
                "DO $$ BEGIN PERFORM orafit.restart_sequence('" + name + "'::regclass); END $$");
    }

    private static void rejectCombinedOptions(List<Parameter> parameters, int supportedCount)
            throws TranslationException {
        if (parameters.size() != supportedCount) {
            throw new TranslationException(
                    "SEQUENCE_RESTART_OPTIONS",
                    "ALTER SEQUENCE RESTART combined with other options is unsupported");
        }
    }

    private static int indexOf(List<Parameter> parameters, String text, boolean prefix) {
        for (int i = 0; i < parameters.size(); i++) {
            String formatted = parameters.get(i).formatParameter();
            if (prefix ? formatted.startsWith(text + " ") : formatted.equals(text)) return i;
        }
        return -1;
    }

    record Result(boolean changed, String renderedSql) {
        static Result unchanged() {
            return new Result(false, null);
        }
    }
}
