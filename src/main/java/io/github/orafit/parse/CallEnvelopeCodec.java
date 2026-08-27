package io.github.orafit.parse;

import io.github.orafit.translation.CallPlan;
import io.github.orafit.translation.TranslationException;

import java.util.ArrayList;
import java.util.List;

/** Extracts only the safe single-call anonymous PL/SQL envelope; argument SQL stays opaque. */
public final class CallEnvelopeCodec {
    private static final String BIND_PREFIX = ":__orafit_b";

    public Result normalize(String sql, int bindCount) throws TranslationException {
        String masked = SqlGate.mask(sql);
        int p = SqlGate.skipWhitespace(masked, 0);
        p = word(masked, p, "BEGIN");
        p = SqlGate.skipWhitespace(masked, p);

        int returnBind = 0;
        Marker marker = marker(masked, p, bindCount);
        if (marker != null) {
            int after = SqlGate.skipWhitespace(masked, marker.end());
            if (masked.startsWith(":=", after)) {
                returnBind = marker.index();
                p = SqlGate.skipWhitespace(masked, after + 2);
            }
        }

        Name routine = routine(sql, masked, p);
        p = SqlGate.skipWhitespace(masked, routine.end());
        String arguments = "";
        List<CallPlan.Argument> argumentPlans = List.of();
        if (p < masked.length() && masked.charAt(p) == '(') {
            int close = matchingClose(masked, p);
            if (close < 0) throw unsupported("Unbalanced routine argument parentheses");
            arguments = sql.substring(p + 1, close);
            argumentPlans = arguments(sql, masked, p + 1, close, bindCount);
            p = SqlGate.skipWhitespace(masked, close + 1);
        }
        if (p >= masked.length() || masked.charAt(p) != ';') {
            throw unsupported("Routine call must end with a semicolon");
        }
        p = SqlGate.skipWhitespace(masked, p + 1);
        p = word(masked, p, "END");
        p = SqlGate.skipWhitespace(masked, p);
        if (p < masked.length() && masked.charAt(p) == ';')
            p = SqlGate.skipWhitespace(masked, p + 1);
        if (p != masked.length())
            throw unsupported("Only one routine call is allowed in the anonymous block");

        String mapped = mapRoutine(routine.parts());
        CallPlan.Kind kind = returnBind > 0 ? CallPlan.Kind.FUNCTION : CallPlan.Kind.PROCEDURE;
        CallPlan plan = new CallPlan(kind, mapped, returnBind, argumentPlans);
        String invocation =
                kind == CallPlan.Kind.FUNCTION
                        ? "SELECT " + mapped + "(" + arguments + ")"
                        : "CALL " + mapped + "(" + arguments + ")";
        return new Result(invocation, plan);
    }

    private static List<CallPlan.Argument> arguments(
            String sql, String masked, int from, int to, int bindCount)
            throws TranslationException {
        if (sql.substring(from, to).trim().isEmpty()) return List.of();
        List<CallPlan.Argument> result = new ArrayList<>();
        int start = from;
        int depth = 0;
        boolean namedSeen = false;
        for (int i = from; i <= to; i++) {
            char c = i == to ? ',' : masked.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                CallPlan.Argument argument = argument(sql, masked, start, i, bindCount);
                if (!argument.named() && namedSeen) {
                    throw new TranslationException(
                            "PLSQL_NAMED_ARGUMENT",
                            "Positional arguments cannot follow named arguments");
                }
                namedSeen |= argument.named();
                result.add(argument);
                start = i + 1;
            }
        }
        return List.copyOf(result);
    }

    private static CallPlan.Argument argument(
            String sql, String masked, int from, int to, int bindCount)
            throws TranslationException {
        int start = SqlGate.skipWhitespace(sql, from);
        int end = trimEnd(sql, start, to);
        if (start >= end) throw unsupported("Routine argument must not be empty");
        int arrow = topLevelArrow(masked, start, end);
        String name = "";
        int expressionStart = start;
        if (arrow >= 0) {
            int nameStart = SqlGate.skipWhitespace(masked, start);
            int nameEnd = trimEnd(masked, nameStart, arrow);
            if (!plainIdentifier(masked, nameStart, nameEnd)) {
                throw new TranslationException(
                        "PLSQL_NAMED_ARGUMENT", "Named argument must use one identifier");
            }
            name = sql.substring(nameStart, nameEnd);
            expressionStart = SqlGate.skipWhitespace(sql, arrow + 2);
            if (expressionStart >= end) {
                throw new TranslationException(
                        "PLSQL_NAMED_ARGUMENT", "Named argument expression must not be empty");
            }
        }
        List<Integer> binds = markers(masked, expressionStart, end, bindCount);
        int maskedExpressionStart = SqlGate.skipWhitespace(masked, expressionStart);
        Marker direct = marker(masked, maskedExpressionStart, bindCount);
        int directIndex =
                direct != null && SqlGate.skipWhitespace(masked, direct.end()) == end
                        ? direct.index()
                        : 0;
        return new CallPlan.Argument(name, binds, directIndex);
    }

    private static List<Integer> markers(String masked, int from, int to, int bindCount)
            throws TranslationException {
        List<Integer> result = new ArrayList<>();
        for (int p = from; p < to; ) {
            int found = masked.indexOf(BIND_PREFIX, p);
            if (found < 0 || found >= to) break;
            Marker marker = marker(masked, found, bindCount);
            if (marker == null || marker.end() > to) {
                p = found + BIND_PREFIX.length();
                continue;
            }
            if (!result.contains(marker.index())) result.add(marker.index());
            p = marker.end();
        }
        return List.copyOf(result);
    }

    private static Marker marker(String masked, int at, int bindCount) throws TranslationException {
        if (at < 0 || at >= masked.length() || !masked.startsWith(BIND_PREFIX, at)) return null;
        int p = at + BIND_PREFIX.length();
        int start = p;
        while (p < masked.length() && Character.isDigit(masked.charAt(p))) p++;
        if (p == start) return null;
        int index;
        try {
            index = Integer.parseInt(masked.substring(start, p));
        } catch (NumberFormatException ex) {
            throw unsupported("Invalid internal JDBC bind marker");
        }
        if (index < 1 || index > bindCount) throw unsupported("Unknown internal JDBC bind marker");
        return new Marker(index, p);
    }

    private static Name routine(String sql, String masked, int at) throws TranslationException {
        List<String> parts = new ArrayList<>();
        int p = at;
        while (true) {
            p = SqlGate.skipWhitespace(masked, p);
            int start = p;
            while (p < masked.length() && SqlGate.identifier(masked.charAt(p))) p++;
            if (start == p) throw unsupported("Expected routine identifier");
            parts.add(sql.substring(start, p));
            p = SqlGate.skipWhitespace(masked, p);
            if (p >= masked.length() || masked.charAt(p) != '.') break;
            if (parts.size() == 3)
                throw unsupported("Routine name may contain at most schema.package.member");
            p++;
        }
        return new Name(List.copyOf(parts), p);
    }

    private static String mapRoutine(List<String> parts) {
        if (parts.size() <= 2) return String.join(".", parts);
        return parts.get(0) + "." + parts.get(1) + "__" + parts.get(2);
    }

    private static int topLevelArrow(String masked, int from, int to) throws TranslationException {
        int depth = 0;
        int found = -1;
        for (int i = from; i + 1 < to; i++) {
            char c = masked.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '=' && masked.charAt(i + 1) == '>') {
                if (found >= 0) {
                    throw new TranslationException(
                            "PLSQL_NAMED_ARGUMENT",
                            "Argument contains more than one top-level => operator");
                }
                found = i;
                i++;
            }
        }
        return found;
    }

    private static int matchingClose(String masked, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int word(String masked, int at, String expected) throws TranslationException {
        int end = at + expected.length();
        if (end > masked.length()
                || !masked.regionMatches(true, at, expected, 0, expected.length())
                || at > 0 && SqlGate.identifier(masked.charAt(at - 1))
                || end < masked.length() && SqlGate.identifier(masked.charAt(end))) {
            throw unsupported("Expected " + expected);
        }
        return end;
    }

    private static boolean plainIdentifier(String masked, int from, int to) {
        if (from >= to || !identifierStart(masked.charAt(from))) return false;
        for (int i = from + 1; i < to; i++) if (!SqlGate.identifier(masked.charAt(i))) return false;
        return true;
    }

    private static int trimEnd(String text, int from, int to) {
        int p = to;
        while (p > from && Character.isWhitespace(text.charAt(p - 1))) p--;
        return p;
    }

    private static boolean identifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '#';
    }

    private static TranslationException unsupported(String message) {
        return new TranslationException("PLSQL_CALL", message);
    }

    public record Result(String sql, CallPlan plan) {}

    private record Marker(int index, int end) {}

    private record Name(List<String> parts, int end) {}
}
