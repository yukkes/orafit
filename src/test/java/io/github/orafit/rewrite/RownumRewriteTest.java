package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

/** Contracts for fail-closed same-block ROWNUM and ORDER BY semantics. */
public final class RownumRewriteTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        unsupported(
                engine,
                "SELECT id FROM app_user WHERE status = ? AND ROWNUM <= 3 ORDER BY id DESC",
                "ROWNUM_ORDER_BY");
        unsupported(
                engine,
                "SELECT id AS employee_id FROM app_user WHERE ROWNUM < ? ORDER BY employee_id",
                "ROWNUM_ORDER_BY");
        unsupported(
                engine, "SELECT * FROM app_user WHERE ROWNUM <= 2 ORDER BY id", "ROWNUM_ORDER_BY");
        unsupported(
                engine,
                "SELECT id FROM app_user WHERE ROWNUM <= 3 ORDER BY created_at",
                "ROWNUM_ORDER_BY");
        unsupported(
                engine,
                "SELECT a.id FROM app_user a WHERE ROWNUM <= 3 ORDER BY a.id",
                "ROWNUM_ORDER_BY");
        unsupported(
                engine,
                "SELECT * FROM a JOIN b ON a.id = b.id WHERE ROWNUM <= 3 ORDER BY id",
                "ROWNUM_ORDER_BY");

        System.out.println("RownumRewriteTest OK");
    }

    private static void unsupported(OrafitEngine engine, String sql, String code) throws Exception {
        try {
            engine.translate(sql);
            throw new AssertionError("expected translation failure " + code + ": " + sql);
        } catch (TranslationException ex) {
            equal(code, ex.code(), "translation failure code");
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
