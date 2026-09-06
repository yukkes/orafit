package io.github.orafit.parse;

import io.github.orafit.translation.Feature;
import io.github.orafit.translation.TranslationException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One-pass ordinary-SQL lexical normalization and feature gate. */
public final class SqlGate {
    private static final String[] ORACLE_FUNCTIONS = {
        "SQRT",
        "REMAINDER",
        "NEXT_DAY",
        "STDDEV",
        "EXTRACT",
        "LENGTH",
        "NULLIF",
        "COALESCE",
        "NVL",
        "NVL2",
        "CONCAT",
        "LNNVL",
        "DECODE",
        "ADD_MONTHS",
        "MONTHS_BETWEEN",
        "LAST_DAY",
        "TO_NUMBER",
        "TO_DATE",
        "TO_TIMESTAMP",
        "TO_TIMESTAMP_TZ",
        "TO_CHAR",
        "SUBSTR",
        "INSTR",
        "LPAD",
        "RPAD",
        "TRIM",
        "LTRIM",
        "RTRIM",
        "TRUNC",
        "ROUND",
        "REGEXP_LIKE",
        "REGEXP_COUNT",
        "REGEXP_INSTR",
        "REGEXP_SUBSTR",
        "REGEXP_REPLACE",
        "GREATEST",
        "LEAST",
        "LISTAGG"
    };
    private static final String[] METADATA_FUNCTIONS = {"COUNT", "SUM", "AVG", "MIN", "MAX"};
    private static final String[] BIND_FOLLOWING_KEYWORDS = {"AND", "OR"};
    private static final Set<String> IGNORABLE_HINTS =
            Set.of(
                    "INDEX",
                    "GATHER_PLAN_STATISTICS",
                    "LEADING",
                    "USE_HASH",
                    "USE_MERGE",
                    "USE_NL",
                    "USE_NL_WITH_INDEX",
                    "FIRST_ROWS",
                    "PARALLEL_INDEX",
                    "NO_PARALLEL_INDEX",
                    "CURSOR_SHARING_EXACT",
                    "MODEL_MIN_ANALYSIS",
                    "MONITOR",
                    "NO_MONITOR",
                    "PX_JOIN_FILTER",
                    "NO_PX_JOIN_FILTER",
                    "OPTIMIZER_FEATURES_ENABLE",
                    "NOREWRITE",
                    "NO_XML_QUERY_REWRITE",
                    "STATEMENT_QUEUING",
                    "NO_STATEMENT_QUEUING",
                    "USE_BAND",
                    "NO_USE_CUBE",
                    "PQ_FILTER",
                    "NO_PQ_SKEW");

    public Scan scan(String sql) throws TranslationException {
        if (sql == null) throw new IllegalArgumentException("sql must not be null");
        Lexical lexical = lexical(sql, true, true);
        DmlBlock block = unwrapDmlBlock(lexical.sql(), lexical.masked());
        String preprocessed = block.sql();
        if (!preprocessed.equals(lexical.sql())) lexical = lexical(preprocessed, true, true);
        String masked = lexical.masked();
        EnumSet<Feature> features = EnumSet.noneOf(Feature.class);

        if (wordSequence(masked, "SELECT", "UNIQUE")) features.add(Feature.SELECT_UNIQUE);
        if (word(masked, "MINUS")) features.add(Feature.MINUS);
        if (word(masked, "ROWNUM")) features.add(Feature.ROWNUM);
        if (word(masked, "DUAL")) features.add(Feature.DUAL);
        if (masked.contains("||")) features.add(Feature.CONCAT);
        if (memberWord(masked, "NEXTVAL") || memberWord(masked, "CURRVAL"))
            features.add(Feature.SEQUENCE);
        if (wordSequence(masked, "CROSS", "APPLY") || wordSequence(masked, "OUTER", "APPLY"))
            features.add(Feature.APPLY);
        if (containsFunction(masked, ORACLE_FUNCTIONS)
                || word(masked, "SYSDATE")
                || word(masked, "SYSTIMESTAMP")) features.add(Feature.ORACLE_FUNCTION);
        if (wordSequence(masked, "CONNECT", "BY")) features.add(Feature.CONNECT_BY);
        if (masked.contains("(+)")
                || wordSequence(masked, "LEFT", "JOIN")
                || wordSequence(masked, "LEFT", "OUTER")
                || wordSequence(masked, "RIGHT", "JOIN")
                || wordSequence(masked, "RIGHT", "OUTER")
                || wordSequence(masked, "FULL", "JOIN")
                || wordSequence(masked, "FULL", "OUTER")) features.add(Feature.OUTER_JOIN);
        if (word(masked, "PIVOT")) features.add(Feature.PIVOT);
        if (word(masked, "UNPIVOT")) features.add(Feature.UNPIVOT);
        if (word(masked, "MERGE")) features.add(Feature.MERGE);
        if (wordSequence(masked, "INSERT", "ALL") || wordSequence(masked, "INSERT", "FIRST"))
            features.add(Feature.MULTI_INSERT);
        if (orderedWords(masked, "RETURNING", "INTO")) features.add(Feature.RETURNING_INTO);
        if (firstWord(masked, "BEGIN") || firstWord(masked, "DECLARE")) features.add(Feature.PLSQL);

        if (lexical.oracleHint()
                || functionCall(masked, "MATCH_RECOGNIZE")
                || modelClause(masked)
                || wordSequence(masked, "VERSIONS", "BETWEEN")
                || flashbackAsOf(masked)
                || indexOfIgnoreCase(masked, "__orafit_b", 0) >= 0
                || indexOfIgnoreCase(masked, "__ocp_", 0) >= 0) {
            features.add(Feature.UNSUPPORTED_ORACLE);
        }
        return new Scan(
                lexical.sql(),
                masked,
                features,
                lexical.bindOffsets(),
                !preprocessed.equals(sql) || lexical.changed(),
                block.statementCount() > 1);
    }

    private static boolean requiresStructuralParser(String masked) {
        return masked.indexOf('/') >= 0
                || word(masked, "IN")
                || word(masked, "OFFSET")
                || word(masked, "FETCH")
                || word(masked, "CAST")
                || masked.indexOf('*') >= 0
                || wordSequence(masked, "ALTER", "SEQUENCE")
                || qualifiedUpdateTarget(masked)
                || containsFunction(masked, METADATA_FUNCTIONS)
                || wordSequence(masked, "FOR", "UPDATE", "OF")
                || wordSequence(masked, "ORDER", "BY", "NULL")
                || word(masked, "LIKE");
    }

    private static boolean requiresDatabaseCoercion(String masked) {
        for (int i = 0; i < masked.length(); i++)
            if ("=<>+-".indexOf(masked.charAt(i)) >= 0) return true;
        return word(masked, "INSERT")
                || word(masked, "UPDATE")
                || word(masked, "UNION")
                || word(masked, "SUM")
                || word(masked, "AVG");
    }

    public static String parserSql(String sql) throws TranslationException {
        return compactWhitespace(lexical(sql, false, false).parserSql());
    }

    private static String compactWhitespace(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean whitespace = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (!singleQuoted && !doubleQuoted) {
                int prefix = qPrefix(sql, i);
                if (prefix > 0) {
                    int delimiter = i + prefix;
                    int end = findQEnd(sql, delimiter + 1, close(sql.charAt(delimiter)));
                    if (end >= 0) {
                        result.append(sql, i, end + 2);
                        i = end + 1;
                        whitespace = false;
                        continue;
                    }
                }
            }
            if (current == '\'' && !doubleQuoted) {
                result.append(current);
                if (singleQuoted && i + 1 < sql.length() && sql.charAt(i + 1) == '\'')
                    result.append(sql.charAt(++i));
                else singleQuoted = !singleQuoted;
                whitespace = false;
            } else if (current == '"' && !singleQuoted) {
                result.append(current);
                if (doubleQuoted && i + 1 < sql.length() && sql.charAt(i + 1) == '"')
                    result.append(sql.charAt(++i));
                else doubleQuoted = !doubleQuoted;
                whitespace = false;
            } else if (!singleQuoted && !doubleQuoted && sqlWhitespace(current)) {
                if (!whitespace) result.append(' ');
                whitespace = true;
            } else {
                result.append(current);
                whitespace = false;
            }
        }
        return result.toString().strip();
    }

    public static String mask(String sql) throws TranslationException {
        return lexical(sql, false, false).masked();
    }

    /**
     * Removes the anonymous PL/SQL wrapper around a block made exclusively of SQL DML statements.
     * Oracle mappers commonly use this shape for a foreach/bulk operation. Orafit lowers the body
     * to an ordered statement plan so JDBC can preserve bind ownership and statement-level
     * atomicity. A block containing a routine call or any other PL/SQL is deliberately left
     * untouched for the narrow call-envelope codec to handle (or reject).
     */
    private static DmlBlock unwrapDmlBlock(String sql, String masked) {
        int p = skipWhitespace(masked, 0);
        if (!wordAt(masked, "BEGIN", p)) return new DmlBlock(sql, 0);
        p = skipWhitespace(masked, p + "BEGIN".length());
        int start = p;
        int statements = 0;
        int lastSemicolon = -1;
        while (true) {
            p = skipWhitespace(masked, p);
            if (wordAt(masked, "END", p)) {
                if (statements == 0) return new DmlBlock(sql, 0);
                int afterEnd = skipWhitespace(masked, p + "END".length());
                if (afterEnd < masked.length() && masked.charAt(afterEnd) == ';') {
                    afterEnd = skipWhitespace(masked, afterEnd + 1);
                }
                return afterEnd == masked.length()
                        ? new DmlBlock(sql.substring(start, lastSemicolon).strip(), statements)
                        : new DmlBlock(sql, 0);
            }
            if (!(wordAt(masked, "INSERT", p)
                    || wordAt(masked, "UPDATE", p)
                    || wordAt(masked, "DELETE", p)
                    || wordAt(masked, "MERGE", p))) return new DmlBlock(sql, 0);

            int semicolon = topLevelSemicolon(masked, p);
            if (semicolon < 0) return new DmlBlock(sql, 0);
            statements++;
            lastSemicolon = semicolon;
            p = semicolon + 1;
        }
    }

    private static int topLevelSemicolon(String masked, int from) {
        int depth = 0;
        for (int i = from; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            else if (c == ';' && depth == 0) return i;
        }
        return -1;
    }

    private static Lexical lexical(String sql, boolean collectBinds, boolean normalize)
            throws TranslationException {
        char[] masked = sql.toCharArray();
        char[] parserSql = collectBinds ? null : sql.toCharArray();
        List<Integer> binds = collectBinds ? new ArrayList<>() : List.of();
        StringBuilder out = null;
        int copyFrom = 0;
        boolean oracleHint = false;

        for (int i = 0; i < sql.length(); ) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int start = i;
                i = quotedEnd(sql, i, c);
                boolean bareIn = normalize && c == '\'' && bareInBefore(sql, start);
                boolean empty = normalize && c == '\'' && i == start + 2;
                if (bareIn || empty) {
                    if (out == null) out = new StringBuilder(sql.length() + 16);
                    out.append(sql, copyFrom, start);
                    if (bareIn) out.append('(');
                    if (empty) out.append("NULL");
                    else out.append(sql, start, i);
                    if (bareIn) out.append(')');
                    copyFrom = i;
                }
                blank(masked, start, i);
                continue;
            }

            int prefix = qPrefix(sql, i);
            if (prefix > 0) {
                int delimiter = i + prefix;
                char close = close(sql.charAt(delimiter));
                int end = findQEnd(sql, delimiter + 1, close);
                if (end < 0)
                    throw new TranslationException(
                            "ORACLE_QUOTE", "Unterminated Oracle q-quoted literal");
                if (normalize) {
                    if (out == null) out = new StringBuilder(sql.length() + 16);
                    out.append(sql, copyFrom, i).append('\'');
                    for (int p = delimiter + 1; p < end; p++) {
                        char body = sql.charAt(p);
                        out.append(body);
                        if (body == '\'') out.append('\'');
                    }
                    out.append('\'');
                    copyFrom = end + 2;
                }
                blank(masked, i, end + 2);
                i = end + 2;
                continue;
            }

            if (starts(sql, i, '-', '-')) {
                int start = i;
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                blank(masked, start, i);
                if (parserSql != null) blank(parserSql, start, i);
                continue;
            }

            if (starts(sql, i, '/', '*')) {
                int start = i;
                boolean hint = i + 2 < sql.length() && sql.charAt(i + 2) == '+';
                i += 2;
                while (i + 1 < sql.length() && !starts(sql, i, '*', '/')) i++;
                int bodyEnd = i;
                i = Math.min(sql.length(), i + 2);
                if (hint && normalize && ignorableHint(sql, start + 3, bodyEnd)) {
                    if (out == null) out = new StringBuilder(sql.length() + 16);
                    out.append(sql, copyFrom, start).append(' ');
                    copyFrom = i;
                } else if (hint) {
                    oracleHint = true;
                } else {
                    if (parserSql != null) blank(parserSql, start, i);
                }
                blank(masked, start, i);
                continue;
            }

            if (normalize
                    && starts(sql, i, '(', '+')
                    && i + 2 < sql.length()
                    && sql.charAt(i + 2) == ')'
                    && ansiJoinOnBefore(sql, i)) {
                if (out == null) out = new StringBuilder(sql.length() + 16);
                out.append(sql, copyFrom, i);
                copyFrom = i + 3;
                i += 3;
                continue;
            }

            if (normalize) {
                if (sqlWhitespace(c) && c > 0x7f) {
                    if (out == null) out = new StringBuilder(sql.length() + 16);
                    out.append(sql, copyFrom, i).append(' ');
                    copyFrom = i + 1;
                    i++;
                    continue;
                }
                char replacement =
                        switch (c) {
                            case '＝' -> '=';
                            case '（' -> '(';
                            case '）' -> ')';
                            default -> c;
                        };
                if (replacement != c) {
                    if (out == null) out = new StringBuilder(sql.length() + 16);
                    out.append(sql, copyFrom, i).append(replacement);
                    copyFrom = i + 1;
                    i++;
                    continue;
                }
                if ((c == '>' || c == '<' || c == '!') && i + 1 < sql.length()) {
                    int next = i + 1;
                    while (next < sql.length() && sqlWhitespace(sql.charAt(next))) next++;
                    if (next > i + 1 && next < sql.length() && sql.charAt(next) == '=') {
                        if (out == null) out = new StringBuilder(sql.length() + 16);
                        out.append(sql, copyFrom, i).append(c).append('=');
                        copyFrom = next + 1;
                        i = next + 1;
                        continue;
                    }
                }
            }

            if (normalize && c == '?' && bareInBefore(sql, i)) {
                if (out == null) out = new StringBuilder(sql.length() + 16);
                out.append(sql, copyFrom, i).append("(?)");
                copyFrom = i + 1;
                i++;
                continue;
            }

            if (normalize && c == '?' && bindFollowingKeyword(sql, i + 1)) {
                if (out == null) out = new StringBuilder(sql.length() + 16);
                out.append(sql, copyFrom, i + 1).append(' ');
                copyFrom = i + 1;
                i++;
                continue;
            }

            if (collectBinds && c == '?') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '?') {
                    i += 2;
                    continue;
                }
                binds.add(i);
            }
            i++;
        }

        if (out != null) {
            out.append(sql, copyFrom, sql.length());
            String normalized = out.toString();
            Lexical rescanned = lexical(normalized, collectBinds, false);
            return new Lexical(
                    normalized,
                    rescanned.masked(),
                    rescanned.parserSql(),
                    rescanned.bindOffsets(),
                    rescanned.oracleHint(),
                    true);
        }
        return new Lexical(
                sql,
                new String(masked),
                parserSql == null ? null : new String(parserSql),
                collectBinds ? List.copyOf(binds) : List.of(),
                oracleHint,
                false);
    }

    private static boolean bindFollowingKeyword(String sql, int start) {
        for (String keyword : BIND_FOLLOWING_KEYWORDS) if (wordAt(sql, keyword, start)) return true;
        return false;
    }

    private static boolean ignorableHint(String sql, int from, int to) {
        int i = from;
        while (i < to) {
            while (i < to && sqlWhitespace(sql.charAt(i))) i++;
            if (i >= to) return true;
            int start = i;
            while (i < to && identifier(sql.charAt(i))) i++;
            if (start == i
                    || !IGNORABLE_HINTS.contains(sql.substring(start, i).toUpperCase(Locale.ROOT)))
                return false;
            while (i < to && sqlWhitespace(sql.charAt(i))) i++;
            if (i < to && sql.charAt(i) == '(') i = balancedEnd(sql, i, to);
            if (i < 0) return false;
        }
        return true;
    }

    private static int balancedEnd(String sql, int at, int limit) {
        int depth = 0;
        boolean quoted = false;
        for (int i = at; i < limit; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < limit && sql.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted && c == '(') depth++;
            else if (!quoted && c == ')' && --depth == 0) return i + 1;
        }
        return -1;
    }

    private static int qPrefix(String sql, int at) {
        if (at > 0 && identifier(sql.charAt(at - 1))) return 0;
        if (at + 2 < sql.length()
                && (sql.charAt(at) == 'q' || sql.charAt(at) == 'Q')
                && sql.charAt(at + 1) == '\'') return 2;
        if (at + 3 < sql.length()
                && (sql.charAt(at) == 'n' || sql.charAt(at) == 'N')
                && (sql.charAt(at + 1) == 'q' || sql.charAt(at + 1) == 'Q')
                && sql.charAt(at + 2) == '\'') return 3;
        return 0;
    }

    private static int findQEnd(String sql, int from, char close) {
        for (int i = from; i + 1 < sql.length(); i++)
            if (sql.charAt(i) == close && sql.charAt(i + 1) == '\'') return i;
        return -1;
    }

    private static boolean ansiJoinOnBefore(String sql, int at) {
        int on = lastWordBefore(sql, "ON", at);
        int join = lastWordBefore(sql, "JOIN", at);
        if (on < 0 || join < 0 || on < join) return false;
        for (String boundary : new String[] {"WHERE", "GROUP", "HAVING", "ORDER"}) {
            if (lastWordBefore(sql, boundary, at) > on) return false;
        }
        return true;
    }

    private static int lastWordBefore(String sql, String word, int limit) {
        int start = Math.min(limit - 1, sql.length() - word.length());
        for (int i = start; i >= 0; i--) if (wordAt(sql, word, i)) return i;
        return -1;
    }

    private static boolean bareInBefore(String sql, int at) {
        int end = at;
        int i = end - 1;
        while (i >= 0 && sqlWhitespace(sql.charAt(i))) i--;
        int wordEnd = i + 1;
        while (i >= 0 && identifier(sql.charAt(i))) i--;
        int wordStart = i + 1;
        if (wordStart == wordEnd || !sql.regionMatches(true, wordStart, "IN", 0, 2)) return false;
        if (wordEnd - wordStart != 2) return false;
        return i < 0 || !identifier(sql.charAt(i));
    }

    private static int quotedEnd(String sql, int at, char quote) {
        for (int i = at + 1; i < sql.length(); i++) {
            if (sql.charAt(i) != quote) continue;
            if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
            else return i + 1;
        }
        return sql.length();
    }

    private static char close(char open) {
        return switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
    }

    private static boolean starts(String sql, int at, char first, char second) {
        return at + 1 < sql.length() && sql.charAt(at) == first && sql.charAt(at + 1) == second;
    }

    private static void blank(char[] chars, int start, int end) {
        for (int i = start; i < end && i < chars.length; i++)
            if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
    }

    private static boolean containsFunction(String sql, String[] names) {
        if (sql.indexOf('(') < 0) return false;
        for (String name : names) if (functionCall(sql, name)) return true;
        return false;
    }

    private static boolean modelClause(String sql) {
        if (!word(sql, "MODEL")) return false;
        return wordSequence(sql, "MODEL", "RETURN")
                || wordSequence(sql, "MODEL", "REFERENCE")
                || wordSequence(sql, "MODEL", "MAIN")
                || (orderedWords(sql, "MODEL", "DIMENSION")
                        && orderedWords(sql, "MODEL", "MEASURES")
                        && orderedWords(sql, "MODEL", "RULES"));
    }

    private static boolean flashbackAsOf(String sql) {
        return wordSequence(sql, "AS", "OF") && (word(sql, "SCN") || word(sql, "TIMESTAMP"));
    }

    private static boolean qualifiedUpdateTarget(String sql) {
        if (!firstWord(sql, "UPDATE")) return false;
        int set = -1;
        for (int from = "UPDATE".length(); from < sql.length(); ) {
            int found = indexOfIgnoreCase(sql, "SET", from);
            if (found < 0) return false;
            if (wordAt(sql, "SET", found)) {
                set = found + "SET".length();
                break;
            }
            from = found + 1;
        }
        if (set < 0) return false;

        int depth = 0;
        boolean target = true;
        boolean qualified = false;
        for (int i = set; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')' && depth > 0) {
                depth--;
                continue;
            }
            if (depth != 0) continue;
            if (wordAt(sql, "WHERE", i) || wordAt(sql, "RETURNING", i)) return false;
            if (target) {
                if (c == '.') qualified = true;
                else if (c == '=') {
                    if (qualified) return true;
                    target = false;
                }
            } else if (c == ',') {
                target = true;
                qualified = false;
            }
        }
        return false;
    }

    private static boolean word(String sql, String word) {
        return nextWord(sql, word, 0) >= 0;
    }

    private static boolean memberWord(String sql, String name) {
        if (sql.indexOf('.') < 0) return false;
        for (int from = 0; ; ) {
            int found = nextWord(sql, name, from);
            if (found < 0) return false;
            int i = found - 1;
            while (i >= 0 && sqlWhitespace(sql.charAt(i))) i--;
            if (i >= 0 && sql.charAt(i) == '.') return true;
            from = found + 1;
        }
    }

    private static boolean functionCall(String sql, String name) {
        for (int from = 0; ; ) {
            int found = nextWord(sql, name, from);
            if (found < 0) return false;
            if (!qualified(sql, found)) {
                int i = skipWhitespace(sql, found + name.length());
                if (i < sql.length() && sql.charAt(i) == '(') return true;
            }
            from = found + 1;
        }
    }

    private static int nextWord(String sql, String word, int from) {
        for (int i = from; i < sql.length(); ) {
            int found = indexOfIgnoreCase(sql, word, i);
            if (found < 0) return -1;
            if (wordAt(sql, word, found)) return found;
            i = found + 1;
        }
        return -1;
    }

    private static boolean qualified(String sql, int start) {
        int i = start - 1;
        while (i >= 0 && sqlWhitespace(sql.charAt(i))) i--;
        return i >= 0 && sql.charAt(i) == '.';
    }

    private static boolean wordSequence(String sql, String... words) {
        if (words.length == 0) return false;
        for (int from = 0; ; ) {
            int found = nextWord(sql, words[0], from);
            if (found < 0) return false;
            int at = found + words[0].length();
            boolean match = true;
            for (int i = 1; i < words.length; i++) {
                at = skipWhitespace(sql, at);
                if (!wordAt(sql, words[i], at)) {
                    match = false;
                    break;
                }
                at += words[i].length();
            }
            if (match) return true;
            from = found + 1;
        }
    }

    private static boolean orderedWords(String sql, String first, String second) {
        for (int from = 0; ; ) {
            int found = nextWord(sql, first, from);
            if (found < 0) return false;
            if (nextWord(sql, second, found + first.length()) >= 0) return true;
            from = found + 1;
        }
    }

    public static boolean firstWord(String sql, String expected) {
        return wordAt(sql, expected, skipWhitespace(sql, 0));
    }

    static int skipWhitespace(String sql, int start) {
        int i = start;
        while (i < sql.length() && sqlWhitespace(sql.charAt(i))) i++;
        return i;
    }

    private static boolean sqlWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    static boolean wordAt(String sql, String word, int start) {
        if (start < 0
                || start + word.length() > sql.length()
                || !sql.regionMatches(true, start, word, 0, word.length())) return false;
        int end = start + word.length();
        return (start == 0 || !identifier(sql.charAt(start - 1)))
                && (end == sql.length() || !identifier(sql.charAt(end)));
    }

    private static int indexOfIgnoreCase(String source, String target, int from) {
        int max = source.length() - target.length();
        char first = target.charAt(0);
        char upper = Character.toUpperCase(first);
        char lower = Character.toLowerCase(first);
        for (int i = Math.max(0, from); i <= max; i++) {
            char candidate = source.charAt(i);
            if ((candidate == upper || candidate == lower || candidate > 127)
                    && source.regionMatches(true, i, target, 0, target.length())) return i;
        }
        return -1;
    }

    static boolean identifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    public record Scan(
            String sql,
            String masked,
            Set<Feature> features,
            List<Integer> bindOffsets,
            boolean changed,
            boolean script) {
        public Scan {
            features = Set.copyOf(features);
            bindOffsets = List.copyOf(bindOffsets);
        }

        public boolean requiresParser() {
            return !features.isEmpty();
        }

        public boolean requiresStructuralParser() {
            return SqlGate.requiresStructuralParser(masked);
        }

        public boolean requiresDatabaseCoercion() {
            return SqlGate.requiresDatabaseCoercion(masked);
        }

        public int bindCount() {
            return bindOffsets.size();
        }
    }

    private record Lexical(
            String sql,
            String masked,
            String parserSql,
            List<Integer> bindOffsets,
            boolean oracleHint,
            boolean changed) {}

    private record DmlBlock(String sql, int statementCount) {}
}
