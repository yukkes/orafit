package io.github.orafit;

import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

/** High-risk recognized Oracle forms must fail closed with deterministic product error codes. */
public final class OrafitFailClosedMatrixTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void unsupportedStructuralFamiliesNeverPassThrough() throws Exception {
        assertCode(
                "SELECT * FROM bs_sales PIVOT (SUM(sal) FOR job IN ('DEV', 'OPS'))",
                "UNSUPPORTED_PIVOT");
        assertCode(
                "SELECT * FROM bs_wide UNPIVOT (sal FOR job IN (dev_sal AS 'DEV', ops_sal AS 'OPS'))",
                "UNSUPPORTED_UNPIVOT");
        assertCode(
                "INSERT ALL INTO bs_dml(id, code, amount) VALUES (91, 'A', 1) "
                        + "INTO bs_dml(id, code, amount) VALUES (92, 'B', 2) SELECT 1 FROM dual",
                "UNSUPPORTED_MULTI_INSERT");
    }

    @Test
    void planSensitiveAndVersionSpecificFormsNeverGuess() throws Exception {
        assertCode(
                "SELECT empno FROM bs_emp WHERE ROWNUM <= 3 ORDER BY empno DESC",
                "ROWNUM_ORDER_BY");
        assertCode(
                "SELECT LISTAGG(DISTINCT ename, ',') WITHIN GROUP (ORDER BY empno) FROM bs_emp",
                "LISTAGG_DISTINCT_ORDER");
        assertCode(
                "SELECT id, CONNECT_BY_ISCYCLE FROM bs_cycle START WITH id = 1 "
                        + "CONNECT BY NOCYCLE PRIOR id = parent_id",
                "CONNECT_BY_ISCYCLE");
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
}
