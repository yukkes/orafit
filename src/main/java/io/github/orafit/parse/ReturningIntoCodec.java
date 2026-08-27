package io.github.orafit.parse;

import io.github.orafit.translation.ReturningPlan;
import io.github.orafit.translation.TranslationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Removes scalar Oracle RETURNING ... INTO output binds before the single JSqlParser parse. */
public final class ReturningIntoCodec {
    private static final String MARKER = ":__orafit_b";

    public Result normalize(String sql, int bindCount) throws TranslationException {
        String masked = SqlGate.mask(sql);
        boolean update = SqlGate.firstWord(masked, "UPDATE");
        if (!SqlGate.firstWord(masked, "INSERT")
                && !update
                && !SqlGate.firstWord(masked, "DELETE")) {
            throw unsupported("RETURNING INTO is supported only for INSERT, UPDATE, and DELETE");
        }
        int end = statementEnd(masked);
        if (!masked.substring(Math.min(end + 1, masked.length())).trim().isEmpty()) {
            throw unsupported("RETURNING INTO does not support multiple JDBC statements");
        }
        int returning = lastTopLevelWord(masked, "RETURNING", 0, end);
        int into = returning < 0 ? -1 : lastTopLevelWord(masked, "INTO", returning + 9, end);
        if (returning < 0 || into < 0)
            throw unsupported("Expected RETURNING expressions followed by INTO output binds");

        List<Range> expressions = split(masked, returning + 9, into);
        List<Range> targets = split(masked, into + 4, end);
        if (expressions.isEmpty() || expressions.size() != targets.size()) {
            throw unsupported("RETURNING expression count must match INTO output-bind count");
        }

        List<ReturningPlan.Output> outputs = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < expressions.size(); i++) {
            String expression =
                    compact(masked.substring(expressions.get(i).from(), expressions.get(i).to()));
            if (expression.isEmpty() || expression.equals("*") || expression.endsWith(".*")) {
                throw unsupported("RETURNING expressions must be scalar");
            }
            int parameter =
                    markerIndex(
                            compact(masked.substring(targets.get(i).from(), targets.get(i).to())));
            if (parameter < 1 || parameter > bindCount || !seen.add(parameter)) {
                throw unsupported(
                        "INTO targets must be distinct positional JDBC output parameters");
            }
            outputs.add(new ReturningPlan.Output(parameter, i + 1));
        }

        String normalized = sql.substring(0, into).stripTrailing() + sql.substring(end);
        if (update) normalized = quoteReturningIdentifiers(normalized, masked, returning);
        return new Result(normalized, new ReturningPlan(outputs));
    }

    /**
     * Oracle permits RETURNING as an unquoted column name; quote only uses before the real clause.
     */
    private static String quoteReturningIdentifiers(String sql, String masked, int clauseAt) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < clauseAt; ) {
            int found = indexOfWord(masked, "RETURNING", i, clauseAt);
            if (found < 0) break;
            int next = SqlGate.skipWhitespace(masked, found + 9);
            if (next < clauseAt && masked.charAt(next) == '=') positions.add(found);
            i = found + 9;
        }
        if (positions.isEmpty()) return sql;
        StringBuilder out = new StringBuilder(sql);
        for (int i = positions.size() - 1; i >= 0; i--) {
            int at = positions.get(i);
            out.replace(at, at + 9, "\"returning\"");
        }
        return out.toString();
    }

    private static int indexOfWord(String sql, String word, int from, int to) {
        for (int i = from; i + word.length() <= to; i++) if (SqlGate.wordAt(sql, word, i)) return i;
        return -1;
    }

    private static List<Range> split(String sql, int from, int to) throws TranslationException {
        List<Range> result = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                result.add(new Range(start, i));
                start = i + 1;
            }
            if (depth < 0) throw unsupported("Unbalanced RETURNING expression");
        }
        if (depth != 0) throw unsupported("Unbalanced RETURNING expression");
        result.add(new Range(start, to));
        return result;
    }

    private static int lastTopLevelWord(String sql, String word, int from, int to) {
        int depth = 0;
        int found = -1;
        for (int i = from; i < to; i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && SqlGate.wordAt(sql, word, i)) {
                found = i;
                i += word.length() - 1;
            }
        }
        return found;
    }

    private static int statementEnd(String sql) {
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) return i;
        }
        return sql.length();
    }

    private static int markerIndex(String text) {
        if (!text.regionMatches(true, 0, MARKER, 0, MARKER.length())) return -1;
        if (text.length() == MARKER.length()) return -1;
        try {
            return Integer.parseInt(text.substring(MARKER.length()));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String compact(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
            if (!Character.isWhitespace(text.charAt(i))) out.append(text.charAt(i));
        return out.toString();
    }

    private static TranslationException unsupported(String message) {
        return new TranslationException("RETURNING_INTO", message);
    }

    public record Result(String sql, ReturningPlan plan) {}

    private record Range(int from, int to) {}
}
