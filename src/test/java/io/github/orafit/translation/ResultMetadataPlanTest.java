package io.github.orafit.translation;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.ResultMetadataPlan.Column;
import io.github.orafit.translation.ResultMetadataPlan.Kind;

import org.junit.jupiter.api.Test;

/** Database-free contracts for Oracle-facing result metadata planning. */
public final class ResultMetadataPlanTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        Translation basic =
                engine.translate(
                        "SELECT CAST(12.34 AS NUMBER(10,2)) AS n, DATE '2024-02-29' AS d, TIMESTAMP"
                                + " '2024-02-29 12:34:56.123456' AS ts FROM dual");
        column(basic, 1, "N", Kind.NUMBER, null, null);
        column(basic, 2, "D", Kind.DATE, 7, 0);
        column(basic, 3, "TS", Kind.TIMESTAMP, 0, 9);
        column(engine.translate("SELECT q'[A'B]' AS value FROM dual"), 1, "VALUE", Kind.CHAR, 3, 0);
        column(
                engine.translate("SELECT CAST('３' AS CHAR(15)) AS value FROM dual"),
                1,
                "VALUE",
                Kind.CHAR,
                15,
                0);
        column(engine.translate("SELECT 1 AS value FROM dual"), 1, "VALUE", Kind.NUMBER, 0, -127);
        column(
                engine.translate("SELECT CASE WHEN 1 = 1 THEN 1 ELSE 0 END AS value FROM dual"),
                1,
                "VALUE",
                Kind.NUMBER,
                0,
                -127);
        column(
                engine.translate("SELECT SUBSTR('abcdef', 2, 3) AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                3,
                0);
        column(
                engine.translate("SELECT SUBSTR('abcdef', 2, ?) AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                5,
                0);
        column(
                engine.translate("SELECT SUBSTR('abcdef', 1, -1) AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                0,
                0);
        column(
                engine.translate("SELECT NVL2(NULL, 'nonnull', 'null') AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                4,
                0);
        column(
                engine.translate("SELECT NVL(amount, ' ') AS amount_value FROM bs_dml"),
                1,
                "AMOUNT_VALUE",
                Kind.AUTO,
                null,
                0);
        column(
                engine.translate(
                        "SELECT DECODE(2, 1, 'one', 2, 'two', 'other') AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                3,
                0);
        column(
                engine.translate("SELECT REGEXP_SUBSTR(NULL, '[0-9]+') AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                0,
                0);
        column(
                engine.translate(
                        "SELECT REGEXP_SUBSTR('abc123def456', '[0-9]+') AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                3,
                0);
        column(
                engine.translate(
                        "SELECT REGEXP_SUBSTR('abc123def456', '[0-9]+', 1, 2) AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                3,
                0);
        column(
                engine.translate(
                        "SELECT REGEXP_REPLACE('abc123', '[0-9]+', 'X') AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                4,
                0);
        column(
                engine.translate(
                        "SELECT REGEXP_REPLACE('abc123', '([a-z]+)([0-9]+)', '\\2-\\1') AS value"
                                + " FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                7,
                0);
        column(
                engine.translate(
                        "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY empno) AS names FROM"
                                + " bs_emp"),
                1,
                "NAMES",
                Kind.VARCHAR2,
                4000,
                0);
        column(
                engine.translate("SELECT TO_CHAR(amount, '9990.00') AS formatted FROM bs_dml"),
                1,
                "FORMATTED",
                Kind.VARCHAR2,
                8,
                0);
        column(
                engine.translate("SELECT ROWNUM AS rn, empno FROM bs_emp WHERE ROWNUM <= 4"),
                1,
                "RN",
                Kind.NUMBER,
                0,
                0);
        column(
                engine.translate("SELECT TO_DATE('2024-02-29') AS value FROM dual"),
                1,
                "VALUE",
                Kind.DATE,
                7,
                0);
        column(
                engine.translate("SELECT LAST_DAY(DATE '2024-02-10') AS value FROM dual"),
                1,
                "VALUE",
                Kind.DATE,
                7,
                0);
        column(
                engine.translate("SELECT ADD_MONTHS(DATE '2024-01-31', 1) AS value FROM dual"),
                1,
                "VALUE",
                Kind.DATE,
                7,
                0);
        column(
                engine.translate(
                        "SELECT TO_TIMESTAMP('2024-02-29 12:34:56.123456', 'YYYY-MM-DD"
                                + " HH24:MI:SS.FF6') AS value FROM dual"),
                1,
                "VALUE",
                Kind.TIMESTAMP,
                0,
                9);
        column(
                engine.translate(
                        "SELECT id, CONNECT_BY_ROOT id AS root_id FROM bs_hierarchy START WITH"
                                + " parent_id IS NULL CONNECT BY PRIOR id = parent_id"),
                2,
                "ROOT_ID",
                Kind.AUTO,
                0,
                0);
        column(
                engine.translate(
                        "SELECT empno, rn FROM (SELECT q.empno, ROWNUM AS rn FROM (SELECT empno"
                                + " FROM bs_emp ORDER BY empno) q WHERE ROWNUM <= ?) WHERE rn > ? ORDER"
                                + " BY rn"),
                2,
                "RN",
                Kind.NUMBER,
                0,
                -127);
        nullable(
                engine.translate(
                        "SELECT d.deptno, e.empno FROM bs_dept d LEFT JOIN bs_emp e ON e.deptno ="
                                + " d.deptno"),
                2,
                1,
                "LEFT JOIN right side");
        nullable(
                engine.translate(
                        "SELECT d.deptno, e.empno FROM bs_dept d RIGHT JOIN bs_emp e ON e.deptno ="
                                + " d.deptno"),
                1,
                1,
                "RIGHT JOIN left side");
        Translation full =
                engine.translate(
                        "SELECT d.deptno, e.empno FROM bs_dept d FULL OUTER JOIN bs_emp e ON"
                                + " e.deptno = d.deptno");
        nullable(full, 1, 1, "FULL JOIN left side");
        nullable(full, 2, 1, "FULL JOIN right side");
        Column concat = engine.translate("SELECT ? || 'X' AS value FROM dual").metadata().column(1);
        equal("VALUE", concat.label(), "bind concat label");
        equal(Kind.VARCHAR2, concat.kind(), "bind concat kind");
        equal(1, concat.precision(), "bind concat fixed precision");
        equal(0, concat.scale(), "bind concat scale");
        equal(1, concat.precisionBind(), "bind concat source bind");
        Column concatFunction =
                engine.translate("SELECT CONCAT(?, '%') AS value FROM dual").metadata().column(1);
        equal(Kind.VARCHAR2, concatFunction.kind(), "CONCAT function kind");
        equal(1, concatFunction.precision(), "CONCAT function fixed precision");
        equal(1, concatFunction.precisionBind(), "CONCAT function source bind");
        column(
                engine.translate("SELECT TO_CHAR(CAST(10 AS NUMBER(6,3))) AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                40,
                0);
        column(
                engine.translate("SELECT TO_CHAR(123.45) AS value FROM dual"),
                1,
                "VALUE",
                Kind.VARCHAR2,
                6,
                0);
        System.out.println("ResultMetadataPlanTest OK");
    }

    private static void column(
            Translation translation,
            int ordinal,
            String label,
            Kind kind,
            Integer precision,
            Integer scale) {
        Column column = translation.metadata().column(ordinal);
        equal(label, column.label(), "label");
        equal(kind, column.kind(), "kind");
        equal(precision, column.precision(), "precision");
        equal(scale, column.scale(), "scale");
    }

    private static void nullable(
            Translation translation, int ordinal, Integer expected, String message) {
        equal(expected, translation.metadata().column(ordinal).nullable(), message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
