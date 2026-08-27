package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

public final class PivotUnpivotRuleTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void lowersStaticSingleAggregatePivot() throws Exception {
        Translation result =
                engine.translate(
                        "SELECT deptno, clerk, manager FROM (SELECT deptno, job, sal FROM emp) "
                                + "PIVOT (SUM(sal) FOR job IN ('CLERK' AS clerk, 'MANAGER' AS manager)) "
                                + "ORDER BY deptno");
        String sql = result.sql().toUpperCase();
        check(result.rewritten(), "basic PIVOT must be rewritten");
        check(!sql.contains(" PIVOT "), "rewritten SQL must not retain PIVOT");
        check(sql.contains("SUM(CASE WHEN JOB = 'CLERK' THEN SAL END) AS CLERK"), "CLERK bucket");
        check(
                sql.contains("SUM(CASE WHEN JOB = 'MANAGER' THEN SAL END) AS MANAGER"),
                "MANAGER bucket");
        check(sql.contains("GROUP BY DEPTNO"), "PIVOT must preserve implicit grouping");
    }

    @Test
    void lowersStaticSingleValueUnpivot() throws Exception {
        Translation result =
                engine.translate(
                        "SELECT id, quarter, amount FROM (SELECT id, q1, q2 FROM sales) "
                                + "UNPIVOT (amount FOR quarter IN (q1 AS 'Q1', q2 AS 'Q2')) "
                                + "ORDER BY id, quarter");
        String sql = result.sql().toUpperCase();
        check(result.rewritten(), "basic UNPIVOT must be rewritten");
        check(!sql.contains(" UNPIVOT "), "rewritten SQL must not retain UNPIVOT");
        check(sql.contains("CROSS JOIN LATERAL"), "UNPIVOT must use one lateral VALUES relation");
        check(sql.contains("VALUES ('Q1', Q1), ('Q2', Q2)"), "UNPIVOT values");
        check(sql.contains("AMOUNT IS NOT NULL"), "default UNPIVOT must exclude null values");
    }

    @Test
    void includeNullsDoesNotAddFilter() throws Exception {
        Translation result =
                engine.translate(
                        "SELECT * FROM (SELECT id, q1, q2 FROM sales) "
                                + "UNPIVOT INCLUDE NULLS (amount FOR quarter IN (q1, q2))");
        String sql = result.sql().toUpperCase();
        check(!sql.contains(" IS NOT NULL"), "INCLUDE NULLS must retain null measures");
    }

    @Test
    void complexFormsRemainFailClosed() throws Exception {
        assertCode(
                "SELECT * FROM (SELECT deptno, job, sal FROM emp) "
                        + "PIVOT (SUM(sal), COUNT(*) AS cnt FOR job IN ('CLERK' AS clerk))",
                "UNSUPPORTED_PIVOT");
        assertCode(
                "SELECT * FROM (SELECT deptno, job, sal, other_job FROM emp) "
                        + "PIVOT (SUM(sal) FOR job IN (other_job AS dynamic_job))",
                "UNSUPPORTED_PIVOT");
        assertCode(
                "SELECT * FROM (SELECT id, q1_qty, q1_amt, q2_qty, q2_amt FROM sales) "
                        + "UNPIVOT ((qty, amount) FOR quarter IN "
                        + "((q1_qty, q1_amt) AS 'Q1', (q2_qty, q2_amt) AS 'Q2'))",
                "UNSUPPORTED_UNPIVOT");
    }

    private void assertCode(String sql, String expected) throws Exception {
        try {
            engine.translate(sql);
            throw new AssertionError("expected fail-closed rejection " + expected);
        } catch (TranslationException ex) {
            if (!expected.equals(ex.code())) {
                throw new AssertionError("expected=" + expected + ", actual=" + ex.code());
            }
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
