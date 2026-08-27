package io.github.orafit;

import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Public-boundary regression test for deterministic fail-closed feature precedence. */
public final class OrafitFailureTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        assertCode(
                engine,
                "SELECT * FROM t PIVOT (SUM(v) FOR k IN (1)) UNPIVOT (v FOR k IN (c1))",
                "UNSUPPORTED_PIVOT");
        assertCode(
                engine,
                "SELECT * FROM t UNPIVOT (v FOR k IN (c1)) PIVOT (SUM(v) FOR k IN (1))",
                "UNSUPPORTED_PIVOT");
        assertCode(
                engine,
                "SELECT /*+ ORDERED */ a.id FROM account a"
                        + " JOIN account_history h ON h.account_id = a.account_id",
                "UNSUPPORTED_ORACLE");
        engine.translate(inList(1000));
        assertCode(engine, inList(1001), "IN_LIST_LIMIT");
        engine.translate(parenthesizedInList(1000));
        assertCode(engine, parenthesizedInList(1001), "IN_LIST_LIMIT");
        engine.translate(tupleInList(1001));
        assertCode(engine, "ALTER SEQUENCE item_seq RESTART CACHE 20", "SEQUENCE_RESTART_OPTIONS");
        System.out.println("OrafitFailureTest OK");
    }

    private static String inList(int size) {
        String expressions =
                IntStream.range(0, size).mapToObj(ignored -> "?").collect(Collectors.joining(","));
        return "SELECT id FROM items WHERE id IN (" + expressions + ")";
    }

    private static String tupleInList(int size) {
        String expressions =
                IntStream.range(0, size)
                        .mapToObj(value -> "(" + value + "," + value + ")")
                        .collect(Collectors.joining(","));
        return "SELECT id FROM items WHERE (id, kind) IN (" + expressions + ")";
    }

    private static String parenthesizedInList(int size) {
        String expressions =
                IntStream.range(0, size)
                        .mapToObj(ignored -> "(?)")
                        .collect(Collectors.joining(","));
        return "SELECT id FROM items WHERE id IN (" + expressions + ")";
    }

    private static void assertCode(OrafitEngine engine, String sql, String expected)
            throws Exception {
        try {
            engine.translate(sql);
            throw new AssertionError("expected fail-closed rejection " + expected);
        } catch (TranslationException ex) {
            if (!expected.equals(ex.code())) {
                throw new AssertionError("expected=" + expected + ", actual=" + ex.code());
            }
        }
    }
}
