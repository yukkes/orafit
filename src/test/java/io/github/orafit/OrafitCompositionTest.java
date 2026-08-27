package io.github.orafit;

import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

/** Public-boundary regression tests for cross-feature semantic composition. */
public final class OrafitCompositionTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        assertConcatLowered(
                engine, "SELECT WHERE", "SELECT empno FROM bs_emp WHERE ename || NULL = ename");
        assertConcatLowered(
                engine,
                "JOIN ON",
                "SELECT e.empno FROM bs_emp e JOIN bs_emp x ON e.ename || NULL = x.ename");
        assertConcatLowered(
                engine,
                "HAVING",
                "SELECT deptno FROM bs_emp GROUP BY deptno HAVING TO_CHAR(deptno) || NULL ="
                        + " TO_CHAR(deptno)");
        assertConcatLowered(
                engine, "ORDER BY", "SELECT empno FROM bs_emp ORDER BY ename || NULL, empno");
        assertConcatLowered(
                engine,
                "GROUP BY",
                "SELECT ename || NULL AS k, COUNT(*) FROM bs_emp GROUP BY ename || NULL ORDER BY"
                        + " 1");
        assertConcatLowered(
                engine,
                "MERGE ON",
                "MERGE INTO bs_dml t USING bs_merge_src s ON (TO_CHAR(t.id) || NULL ="
                        + " TO_CHAR(s.id)) WHEN MATCHED THEN UPDATE SET t.amount = s.amount");
        assertConcatLowered(
                engine,
                "MERGE UPDATE SET",
                "MERGE INTO bs_dml t USING bs_merge_src s ON (t.id = s.id) "
                        + "WHEN MATCHED THEN UPDATE SET t.note = s.note || NULL");
        assertConcatLowered(
                engine,
                "MERGE UPDATE WHERE",
                "MERGE INTO bs_dml t USING bs_merge_src s ON (t.id = s.id) "
                        + "WHEN MATCHED THEN UPDATE SET t.amount = s.amount "
                        + "WHERE TO_CHAR(s.amount) || NULL = TO_CHAR(s.amount)");
        assertConcatLowered(
                engine,
                "MERGE INSERT VALUES",
                "MERGE INTO bs_dml t USING bs_merge_src s ON (t.id = s.id) "
                        + "WHEN NOT MATCHED THEN INSERT (id, code, amount, note) "
                        + "VALUES (s.id, 'X', s.amount, s.note || NULL)");
        assertConcatLowered(
                engine,
                "MERGE INSERT WHERE",
                "MERGE INTO bs_dml t USING bs_merge_src s ON (t.id = s.id) "
                        + "WHEN NOT MATCHED THEN INSERT (id, code, amount, note) "
                        + "VALUES (s.id, 'X', s.amount, s.note) "
                        + "WHERE TO_CHAR(s.amount) || NULL = TO_CHAR(s.amount)");
        assertConcatLowered(
                engine, "UPDATE SET", "UPDATE bs_emp SET ename = ename || NULL WHERE empno = 1");
        assertConcatLowered(
                engine,
                "UPDATE WHERE",
                "UPDATE bs_emp SET ename = ename WHERE ename || NULL = ename");
        assertConcatLowered(
                engine, "DELETE WHERE", "DELETE FROM bs_emp WHERE ename || NULL = ename");

        System.out.println("OrafitCompositionTest OK");
    }

    private static void assertConcatLowered(OrafitEngine engine, String location, String sql)
            throws Exception {
        Translation translation = engine.translate(sql);
        String rendered = translation.sql().toLowerCase();
        check(translation.rewritten(), location + " must be rewritten");
        check(
                rendered.contains("orafit.concat_varchar2"),
                location + " must use Oracle VARCHAR2 concatenation semantics");
        check(
                !rendered.contains(" || "),
                "Oracle concat operator must not reach PostgreSQL from " + location);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
