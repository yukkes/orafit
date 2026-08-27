package io.github.orafit.metadata;

/**
 * Conservative constant folding used only when Oracle JDBC metadata is provable from literals.
 *
 * <p>Methods return {@code null} when the expression is outside the deliberately small provable
 * subset. Callers must then delegate metadata instead of guessing.
 */
final class LiteralMetadataInference {
    private LiteralMetadataInference() {}

    /**
     * Computes the output length of a simple literal REGEXP_SUBSTR expression when provable.
     *
     * @return output length, zero for no match, or {@code null} when folding is unsafe
     */
    static Integer regexpSubstr(String source, String pattern, long start, long occurrence) {
        if (source == null
                || pattern == null
                || start < 1
                || occurrence < 1
                || !simpleRegex(pattern)) return null;
        try {
            int from = (int) Math.min(source.length(), start - 1);
            var match = java.util.regex.Pattern.compile(pattern).matcher(source.substring(from));
            for (long i = 1; i < occurrence; i++) if (!match.find()) return 0;
            return match.find() ? match.group().length() : 0;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Computes the output length of a simple literal REGEXP_REPLACE expression when provable.
     *
     * @return output length, or {@code null} when folding is unsafe
     */
    static Integer regexpReplace(String source, String pattern, String replacement) {
        if (source == null || pattern == null || replacement == null || !simpleRegex(pattern))
            return null;
        String javaReplacement = javaReplacement(replacement);
        if (javaReplacement == null) return null;
        try {
            return java.util.regex.Pattern.compile(pattern)
                    .matcher(source)
                    .replaceAll(javaReplacement)
                    .length();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean simpleRegex(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!Character.isLetterOrDigit(c) && "[]()+*.^$-".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static String javaReplacement(String replacement) {
        StringBuilder out = new StringBuilder(replacement.length());
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (++i >= replacement.length() || !Character.isDigit(replacement.charAt(i)))
                    return null;
                out.append('$').append(replacement.charAt(i));
            } else if (c == '$') {
                return null;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
