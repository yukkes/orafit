package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

/** Verifies Oracle zero-length literals before the ordinary-SQL fast path. */
public final class EmptyStringLiteralTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        Translation insert = engine.translate("INSERT INTO t(id, value) VALUES (1, '')");
        equal(
                "INSERT INTO t(id, value) VALUES (1, NULL)",
                insert.sql(),
                "empty INSERT literal must normalize without requiring the parser");
        check(insert.rewritten(), "empty INSERT literal must mark translation changed");

        Translation update = engine.translate("UPDATE t SET value = '' WHERE id = 1");
        equal(
                "UPDATE t SET value = NULL WHERE id = 1",
                update.sql(),
                "empty UPDATE literal must normalize without requiring the parser");
        check(update.rewritten(), "empty UPDATE literal must mark translation changed");

        Translation quotedApostrophe = engine.translate("INSERT INTO t(value) VALUES ('''')");
        equal(
                "INSERT INTO t(value) VALUES ('''')",
                quotedApostrophe.sql(),
                "a one-apostrophe string literal must not be mistaken for empty text");

        System.out.println("EmptyStringLiteralTest OK");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
