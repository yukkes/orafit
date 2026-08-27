package io.github.orafit;

import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Risk-based regression tests for combinations of independently supported Oracle features. */
public final class OrafitRiskCompositionTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void decodeBindConcatAndNvlCompose() throws Exception {
        Translation translation =
                engine.translate(
                        "SELECT DECODE(?, 1, 'one', 'other') || ':' || NVL(NULL, 'x') AS value FROM DUAL");
        String sql = translation.sql().toLowerCase();
        check(sql.contains("case"), "DECODE must remain lowered inside concatenation");
        check(sql.contains("orafit.concat_varchar2"), "concat semantics must be preserved");
        check(sql.contains("orafit.nvl"), "NVL must remain lowered inside concatenation");
        check(
                translation.binds().outputToInput().stream().allMatch(index -> index == 1),
                "DECODE composition must preserve source bind lineage");
    }

    @Test
    void listaggNestedNvlAndTwoBindsCompose() throws Exception {
        Translation translation =
                engine.translate(
                        "SELECT LISTAGG(NVL(ename, 'missing'), ?) WITHIN GROUP (ORDER BY empno) AS names "
                                + "FROM bs_emp WHERE deptno = ?");
        String sql = translation.sql().toLowerCase();
        check(sql.contains("string_agg"), "LISTAGG must be lowered");
        check(sql.contains("orafit.nvl"), "NVL inside LISTAGG measure must be lowered");
        equal(List.of(1, 2), translation.binds().outputToInput(), "LISTAGG/WHERE bind lineage");
    }

    @Test
    void orderedInlineRownumAndScalarRewriteCompose() throws Exception {
        Translation translation =
                engine.translate(
                        "SELECT NVL(ename, 'missing') AS value "
                                + "FROM (SELECT ename, empno FROM bs_emp ORDER BY empno DESC) "
                                + "WHERE ROWNUM <= 3");
        String sql = translation.sql().toLowerCase();
        check(sql.contains("orafit.nvl"), "NVL must remain lowered outside inline view");
        check(!sql.contains("rownum"), "safe ROWNUM boundary must be fully lowered");
        check(
                sql.contains("fetch first") || sql.contains("limit"),
                "safe ROWNUM boundary must become a PostgreSQL row limit");
    }

    @Test
    void mergeConcatAndBindCompose() throws Exception {
        Translation translation =
                engine.translate(
                        "MERGE INTO bs_dml t USING bs_merge_src s "
                                + "ON (TO_CHAR(t.id) || NULL = TO_CHAR(s.id)) "
                                + "WHEN MATCHED THEN UPDATE SET t.note = ?");
        String sql = translation.sql().toLowerCase();
        check(sql.contains("merge into"), "MERGE must remain a MERGE statement");
        check(sql.contains("orafit.concat_varchar2"), "MERGE ON concat must be lowered");
        equal(List.of(1), translation.binds().outputToInput(), "MERGE bind lineage");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
