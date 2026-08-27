package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;

import org.junit.jupiter.api.Test;

/** Guards Oracle scalar-function composition inside DML value expressions. */
public final class DmlFunctionCompositionTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        String insert =
                engine.translate("INSERT INTO t(id, value) VALUES (?, NVL(?, 'fallback'))")
                        .sql()
                        .toLowerCase();
        check(insert.contains("orafit.nvl"), "INSERT VALUES NVL must be routed");
        String update =
                engine.translate("UPDATE t SET value = NVL(?, 'fallback') WHERE id = ?")
                        .sql()
                        .toLowerCase();
        check(update.contains("orafit.nvl"), "UPDATE SET NVL must be routed");
        System.out.println("DmlFunctionCompositionTest OK");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
