package io.github.orafit.rewrite;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import org.junit.jupiter.api.Test;

/** Contracts for Oracle 18c LISTAGG ordering and overflow forms. */
public final class ListaggOverflowTest {
    @Test
    void contract() throws Exception {
        String withCount =
                rewrite(
                        "SELECT LISTAGG(v, ',' ON OVERFLOW TRUNCATE '...' WITH COUNT) "
                                + "WITHIN GROUP (ORDER BY v) FROM t");
        check(
                withCount.contains("orafit.listagg_truncate(pg_catalog.array_agg("),
                "TRUNCATE must use the extension helper");
        check(
                withCount.contains("ORDER BY v), ',', '...', true, 4000)"),
                "WITH COUNT must preserve ordering, delimiter and indicator");

        String withoutCount =
                rewrite(
                        "SELECT LISTAGG(v, '|' ON OVERFLOW TRUNCATE '[cut]' WITHOUT COUNT) "
                                + "WITHIN GROUP (ORDER BY v) FROM t");
        check(
                withoutCount.contains("'|', '[cut]', false, 4000)"),
                "WITHOUT COUNT and custom indicator must be preserved");

        String defaults =
                rewrite(
                        "SELECT LISTAGG(v, ',' ON OVERFLOW TRUNCATE) "
                                + "WITHIN GROUP (ORDER BY v) FROM t");
        check(
                defaults.contains("',', '...', true, 4000)"),
                "Oracle default indicator and WITH COUNT must be applied");

        String basic = rewrite("SELECT LISTAGG(v, ',') WITHIN GROUP (ORDER BY v) FROM t");
        check(
                basic.contains("orafit.listagg_check(pg_catalog.string_agg("),
                "non-truncating LISTAGG must retain the existing byte-limit path");

        String analytic =
                rewrite(
                        "SELECT id, LISTAGG(v, '|') WITHIN GROUP (ORDER BY id) OVER () AS all_v "
                                + "FROM t ORDER BY id");
        check(
                analytic.contains("orafit.listagg_check(pg_catalog.string_agg("),
                "analytic LISTAGG must reuse the existing byte-limit helper");
        check(
                analytic.contains(
                        "OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED"
                                + " FOLLOWING)"),
                "analytic LISTAGG must aggregate the whole ordered partition");

        unsupported(
                "SELECT LISTAGG(DISTINCT v, ',' ON OVERFLOW TRUNCATE '...' WITH COUNT) "
                        + "WITHIN GROUP (ORDER BY v) FROM t",
                "LISTAGG_DISTINCT_ORDER");
        unsupported(
                "SELECT LISTAGG(v, ',') WITHIN GROUP (ORDER BY id) OVER (PARTITION BY deptno) "
                        + "FROM t",
                "LISTAGG");
        System.out.println("ListaggOverflowTest OK");
    }

    private static String rewrite(String sql) throws Exception {
        var statement = CCJSqlParserUtil.parse(sql);
        check(new ListaggRule().rewrite(statement), "LISTAGG rule must rewrite");
        return statement.toString();
    }

    private static void unsupported(String sql, String code) throws Exception {
        try {
            rewrite(sql);
            throw new AssertionError("expected translation failure " + code);
        } catch (TranslationException ex) {
            equal(code, ex.code(), "translation failure code");
        }
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
