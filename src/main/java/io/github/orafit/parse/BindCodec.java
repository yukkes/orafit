package io.github.orafit.parse;

import io.github.orafit.translation.BindLineage;
import io.github.orafit.translation.TranslationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Preserves JDBC parameter identity across AST rewrites without a second SQL parse. Input '?'
 * markers become private named parameters before parsing, then are decoded after rendering while
 * producing output-to-input lineage.
 */
public final class BindCodec {
    private static final String PREFIX = ":__orafit_b";

    public Encoded encode(String sql, SqlGate.Scan scan) {
        List<Integer> offsets = scan.bindOffsets();
        if (offsets.isEmpty()) return new Encoded(sql, 0);

        StringBuilder out = new StringBuilder(sql.length() + offsets.size() * 20);
        int source = 0;
        for (int i = 0; i < offsets.size(); i++) {
            int offset = offsets.get(i);
            out.append(sql, source, offset);
            out.append(PREFIX).append(i + 1);
            source = offset + 1;
        }
        out.append(sql, source, sql.length());
        return new Encoded(out.toString(), offsets.size());
    }

    public Decoded decode(String rendered, int inputCount) throws TranslationException {
        return decode(rendered, inputCount, Set.of());
    }

    public Decoded decode(String rendered, int inputCount, Set<Integer> omitted)
            throws TranslationException {
        return decode(rendered, inputCount, omitted, true);
    }

    /** Decodes one fragment of a multi-statement script without requiring every input bind. */
    public Decoded decodeFragment(String rendered, int inputCount, Set<Integer> omitted)
            throws TranslationException {
        return decode(rendered, inputCount, omitted, false);
    }

    private Decoded decode(
            String rendered, int inputCount, Set<Integer> omitted, boolean requireAll)
            throws TranslationException {
        if (inputCount == 0) return new Decoded(rendered, BindLineage.identity(0));

        String masked = SqlGate.mask(rendered);
        StringBuilder out = new StringBuilder(rendered.length());
        List<Integer> lineage = new ArrayList<>();
        boolean[] seen = new boolean[inputCount + 1];
        int source = 0;
        int search = 0;

        while (true) {
            int marker = masked.indexOf(PREFIX, search);
            if (marker < 0) break;
            int digitStart = marker + PREFIX.length();
            int end = digitStart;
            while (end < masked.length() && Character.isDigit(masked.charAt(end))) end++;
            if (end == digitStart
                    || end < masked.length() && SqlGate.identifier(masked.charAt(end))) {
                search = marker + PREFIX.length();
                continue;
            }

            int input;
            try {
                input = Integer.parseInt(masked.substring(digitStart, end));
            } catch (NumberFormatException ex) {
                throw new TranslationException("BIND_LINEAGE", "Invalid internal bind marker", ex);
            }
            if (input < 1 || input > inputCount) {
                throw new TranslationException(
                        "BIND_LINEAGE", "Unknown internal bind marker: " + input);
            }
            if (omitted.contains(input)) {
                throw new TranslationException(
                        "BIND_LINEAGE",
                        "RETURNING output bind leaked into PostgreSQL SQL: " + input);
            }

            out.append(rendered, source, marker).append('?');
            lineage.add(input);
            seen[input] = true;
            source = end;
            search = end;
        }
        out.append(rendered, source, rendered.length());

        if (requireAll) {
            for (int i = 1; i <= inputCount; i++) {
                if (!seen[i] && !omitted.contains(i)) {
                    throw new TranslationException(
                            "BIND_LINEAGE", "Rewrite lost JDBC bind position " + i);
                }
            }
        }
        return new Decoded(out.toString(), new BindLineage(inputCount, lineage));
    }

    public record Encoded(String sql, int bindCount) {}

    public record Decoded(String sql, BindLineage lineage) {}
}
