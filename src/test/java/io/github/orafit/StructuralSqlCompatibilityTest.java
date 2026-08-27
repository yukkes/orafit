package io.github.orafit;

import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

/** Regression coverage for structural SQL compatibility behavior. */
public final class StructuralSqlCompatibilityTest {
    @Test
    void insertSelectTraversesOracleExpressions() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        Translation translated =
                engine.translate(
                        "INSERT INTO sample_time(date_text, timestamp_text) "
                                + "SELECT TO_CHAR(SYSDATE, 'YYYYMMDD'), "
                                + "TO_CHAR(SYSTIMESTAMP, 'HH24MISSFF6') FROM DUAL");
        String sql = translated.sql().toLowerCase();
        check(sql.contains("orafit.sysdate"), "SYSDATE in INSERT SELECT must be lowered");
        check(sql.contains("orafit.systimestamp"), "SYSTIMESTAMP in INSERT SELECT must be lowered");
    }

    @Test
    void ordinaryCommentsDoNotBreakStructuralParsing() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        Translation translated =
                engine.translate(
                        "SELECT COUNT(*) FROM ("
                                + "SELECT id /* column comment */, NVL(amount, 0) "
                                + "FROM sample_table /* table comment */) q");
        String sql = translated.sql().toLowerCase();
        check(!sql.contains("/*"), "ordinary comments must be removed before rendering");
        check(sql.contains("orafit.nvl"), "nested NVL must still be lowered");
    }

    @Test
    void unaliasedExpressionLabelsComeFromOracleSideAst() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        Translation count = engine.translate("SELECT COUNT(*) FROM sample_table");
        equal("COUNT(*)", count.metadata().column(1).label(), "COUNT label");

        Translation nvl = engine.translate("SELECT NVL(SUM(amount), 0) FROM sample_table");
        String label = nvl.metadata().column(1).label();
        check(label != null && label.contains("NVL") && label.contains("SUM"), "expression label");
        check(
                label.contains("AMOUNT"),
                "unquoted identifiers in expression labels use Oracle case");
        check(!label.contains("ORAFIT"), "metadata must not leak rewritten function names");
    }

    @Test
    void qualifiedForUpdateTargetsResolveToRelations() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        Translation translated =
                engine.translate(
                        "SELECT a.id FROM sample_table a WHERE a.id = 1 FOR UPDATE OF a.id");
        String sql = translated.sql().toLowerCase();
        check(
                sql.contains("for update of a"),
                "qualified column target must lower to relation alias");
        check(!sql.contains("for update of a.id"), "column target must not reach PostgreSQL");

        try {
            engine.translate("SELECT id FROM sample_table FOR UPDATE OF id");
            throw new AssertionError("unqualified FOR UPDATE target must fail closed");
        } catch (TranslationException expected) {
            equal("FOR_UPDATE_TARGET", expected.code(), "failure code");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
