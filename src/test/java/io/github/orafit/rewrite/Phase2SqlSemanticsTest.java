package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

/** Focused contracts for daily Oracle expression semantics added in Phase 2. */
public final class Phase2SqlSemanticsTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void literalCharacterNumberCoercionIsExplicit() throws Exception {
        Translation arithmetic = engine.translate("SELECT '2' + 3 AS value FROM dual");
        check(
                arithmetic.sql().toLowerCase().contains("orafit.to_number('2')"),
                "character arithmetic operand must use Oracle NUMBER conversion");

        Translation reversed =
                engine.translate("SELECT CASE WHEN 10 = '10' THEN 1 ELSE 0 END AS value FROM dual");
        check(
                reversed.sql().toLowerCase().contains("orafit.to_number('10')"),
                "reversed character comparison operand must use Oracle NUMBER conversion");

        Translation invalid = engine.translate("SELECT 'not-a-number' + 1 AS value FROM dual");
        check(
                invalid.sql().toLowerCase().contains("orafit.to_number('not-a-number')"),
                "invalid character arithmetic must reach the Oracle error-producing conversion");
    }

    @Test
    void decodeNumericResultKeepsNumberMetadata() throws Exception {
        ResultMetadataPlan.Column column =
                engine.translate("SELECT DECODE(2, 1, 10, 2, 20, 30) AS value FROM dual")
                        .metadata()
                        .column(1);
        equal(ResultMetadataPlan.Kind.NUMBER, column.kind(), "DECODE result kind");
        equal(0, column.precision(), "DECODE NUMBER precision");
        equal(-127, column.scale(), "DECODE NUMBER scale");
    }

    @Test
    void schemaDependentCoercionIsNotGuessed() throws Exception {
        Translation translation = engine.translate("SELECT amount FROM t WHERE amount = '10'");
        check(!translation.rewritten(), "column datatype must not be guessed during translation");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
