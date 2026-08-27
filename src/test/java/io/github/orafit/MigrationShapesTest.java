package io.github.orafit;

import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Regression coverage for representative Mapper migration shapes. */
public final class MigrationShapesTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void contract() throws Exception {
        Translation outer =
                engine.translate(
                        "SELECT m.id, o1.name, o2.value"
                                + " FROM mandatory_row m, mandatory_group g, optional_one o1,"
                                + " optional_two o2"
                                + " WHERE m.id = o1.mandatory_id(+)"
                                + " AND g.id = o1.group_id(+)"
                                + " AND o1.id = o2.optional_one_id(+)"
                                + " AND o2.active_flag(+) = '1'");
        String outerSql = outer.sql().toUpperCase();
        check(outerSql.contains("CROSS JOIN MANDATORY_GROUP G"), "mandatory roots must be grouped");
        check(outerSql.contains("LEFT JOIN OPTIONAL_ONE O1"), "first optional relation must join");
        check(
                outerSql.contains("LEFT JOIN OPTIONAL_TWO O2"),
                "chained optional relation must join");
        check(!outerSql.contains("(+)"), "legacy join markers must be removed");

        Translation dateArithmetic =
                engine.translate(
                        "SELECT TO_CHAR(TO_DATE(?, 'YYYYMMDD') + 1, 'YYYYMMDD') FROM dual");
        String dateArithmeticSql = dateArithmetic.sql().toLowerCase();
        check(
                dateArithmeticSql.contains("orafit.to_char_format"),
                "DATE arithmetic must remain eligible for formatted TO_CHAR");
        check(
                dateArithmeticSql.contains("orafit.days_interval(1)"),
                "Oracle DATE + number must keep day arithmetic");
        equal(List.of(1), dateArithmetic.binds().outputToInput(), "DATE arithmetic bind lineage");
        equal(8, dateArithmetic.metadata().column(1).precision(), "DATE TO_CHAR precision");

        Translation typedColumn =
                engine.translate("SELECT TO_CHAR(date_value, 'YYYYMMDD') FROM probe_dates");
        check(
                typedColumn.sql().toLowerCase().contains("orafit.to_char_format(date_value"),
                "column TO_CHAR must delegate datatype dispatch to PostgreSQL");
        equal(8, typedColumn.metadata().column(1).precision(), "typed TO_CHAR precision");

        Translation aggregate =
                engine.translate(
                        "SELECT MAX(sort_key) FROM (SELECT sort_key FROM probe_order"
                                + " ORDER BY sort_key DESC) WHERE ROWNUM = 1");
        String aggregateSql = aggregate.sql().toUpperCase();
        check(!aggregateSql.contains("ROWNUM"), "aggregate ROWNUM predicate must be removed");
        check(
                aggregateSql.contains("ORDER BY SORT_KEY DESC FETCH FIRST 1 ROWS ONLY"),
                "ROWNUM = 1 must limit the ordered aggregate input");

        Translation projected =
                engine.translate(
                        "SELECT ROWNUM AS rn, q.* FROM (SELECT a.value AS value"
                                + " FROM probe_a a, probe_b b ORDER BY b.value) q"
                                + " WHERE ROWNUM <= ?");
        String projectedSql = projected.sql().toUpperCase();
        check(
                projectedSql.contains("ROW_NUMBER() OVER (ORDER BY __ORAFIT_ORDER_1)"),
                "unprojected qualified order must drive projected ROWNUM");
        check(
                projectedSql.contains("B.VALUE AS __ORAFIT_ORDER_1"),
                "qualified order column must be preserved as a hidden source output");
        check(projectedSql.contains("Q.VALUE"), "q.* must preserve the visible source output");
        check(
                !projectedSql.contains("Q.__ORAFIT_ORDER_1"),
                "hidden order output must not leak through q.*");
        check(projectedSql.contains("FETCH FIRST CAST("), "ROWNUM bind must become dynamic FETCH");
        equal(List.of(1), projected.binds().outputToInput(), "projected ROWNUM bind lineage");

        Translation decode =
                engine.translate(
                        "SELECT COALESCE(SUM(DECODE(class_code, '3', amount * -1, '4', amount)), 0)"
                                + " FROM probe_amount");
        String decodeSql = decode.sql().toUpperCase();
        check(decodeSql.contains("CASE"), "DECODE must still lower to CASE");
        check(
                decodeSql.contains("ORAFIT.TO_NUMBER_BIND(AMOUNT) * -1"),
                "numeric first result expression must coerce its column operand");
        check(
                decodeSql.contains("ORAFIT.TO_NUMBER_BIND(AMOUNT)"),
                "numeric column result must follow the anchor type");

        Translation hints =
                engine.translate(
                        "SELECT /*+ GATHER_PLAN_STATISTICS INDEX(a) LEADING(a b) USE_NL(b)"
                                + " USE_MERGE(a b) */ a.id"
                                + " FROM probe_a a JOIN probe_b b ON b.a_id = a.id");
        check(hints.rewritten(), "reviewed advisory hints must be normalized");
        check(!hints.sql().contains("/*+"), "advisory hints must not reach PostgreSQL");
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
