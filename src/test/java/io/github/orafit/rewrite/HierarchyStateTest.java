package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Contracts for value-path, root-expression, and leaf hierarchy state. */
public final class HierarchyStateTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        Translation path =
                engine.translate(
                        "SELECT t.id, SYS_CONNECT_BY_PATH(t.name, '/') path FROM tree t "
                                + "START WITH t.id = 1 CONNECT BY NOCYCLE PRIOR t.id = t.mgr");
        String pathSql = path.sql().toUpperCase();
        check(
                pathSql.contains("__ORAFIT_VALUE_PATH_1"),
                "SYS_CONNECT_BY_PATH must use one hidden recursive state column");
        check(
                pathSql.contains("ORAFIT.CONCAT_VARCHAR2"),
                "path append must use Oracle empty-string/null concatenation semantics");
        check(
                pathSql.contains("ORAFIT.TO_VARCHAR2"),
                "path values must delegate Oracle text conversion to the extension");
        check(
                pathSql.contains("__ORAFIT_VALUE_PATH_1 AS PATH"),
                "path output alias must be preserved");
        check(
                !pathSql.contains("SYS_CONNECT_BY_PATH"),
                "Oracle path function must not reach PostgreSQL");

        Translation boundPath =
                engine.translate(
                        "SELECT t.id, SYS_CONNECT_BY_PATH(t.name, ?) path FROM tree t "
                                + "START WITH t.id = ? CONNECT BY NOCYCLE PRIOR t.id = t.mgr");
        equal(
                List.of(1, 2, 1),
                boundPath.binds().outputToInput(),
                "path delimiter bind must be reused by anchor and recursive members while START"
                        + " WITH keeps its input position");

        Translation rootFunction =
                engine.translate(
                        "SELECT t.id, CONNECT_BY_ROOT UPPER(t.name) root_name FROM tree t "
                                + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr");
        String rootFunctionSql = rootFunction.sql().toUpperCase();
        check(
                rootFunctionSql.contains("UPPER(__ORAFIT_HC.NAME)"),
                "CONNECT_BY_ROOT function must be evaluated once on the anchor source");
        check(
                rootFunction.metadata().column(2).source() != null
                        && "name"
                                .equalsIgnoreCase(
                                        rootFunction.metadata().column(2).source().column()),
                "CONNECT_BY_ROOT UPPER must retain source metadata lineage");

        Translation rootArithmetic =
                engine.translate(
                        "SELECT t.id, CONNECT_BY_ROOT (t.id + 1) root_id FROM tree t "
                                + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr");
        check(
                rootArithmetic.sql().toUpperCase().contains("__ORAFIT_HC.ID + 1"),
                "CONNECT_BY_ROOT arithmetic must be evaluated on the anchor source");
        check(
                Integer.valueOf(0).equals(rootArithmetic.metadata().column(2).scale()),
                "CONNECT_BY_ROOT integer arithmetic must preserve Oracle NUMBER scale 0");

        Translation leaf =
                engine.translate(
                        "SELECT t.id, CONNECT_BY_ISLEAF leaf, LEVEL FROM tree t "
                                + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr");
        String leafSql = leaf.sql().toUpperCase();
        check(
                leafSql.contains("CASE WHEN EXISTS"),
                "CONNECT_BY_ISLEAF must probe for a traversable child");
        check(
                leafSql.contains("ARRAY_POSITION"),
                "leaf probe must exclude ancestor-cycle children");
        check(
                leafSql.contains("THEN 0 ELSE 1 END AS LEAF"),
                "leaf output must use Oracle 0/1 convention and preserve alias");
        check(
                !leafSql.contains("CONNECT_BY_ISLEAF"),
                "Oracle leaf pseudo-column must not reach PostgreSQL");

        unsupported(
                engine,
                "SELECT t.id, CONNECT_BY_ISCYCLE cycle FROM tree t "
                        + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr",
                "CONNECT_BY_ISCYCLE");
        unsupported(
                engine,
                "SELECT SYS_CONNECT_BY_PATH(t.name, '/') FROM tree t "
                        + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr",
                "CONNECT_BY_PATH");
        unsupported(
                engine,
                "SELECT COALESCE(CONNECT_BY_ISLEAF, 0) leaf FROM tree t "
                        + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr",
                "CONNECT_BY_ISLEAF");

        unsupported(
                engine,
                "SELECT t.id, CONNECT_BY_ROOT ABS(t.id) root_id FROM tree t "
                        + "START WITH t.mgr IS NULL CONNECT BY NOCYCLE PRIOR t.id = t.mgr",
                "CONNECT_BY_ROOT");

        System.out.println("HierarchyStateTest OK");
    }

    private static void unsupported(OrafitEngine engine, String sql, String code) throws Exception {
        try {
            engine.translate(sql);
            throw new AssertionError("expected translation failure " + code + ": " + sql);
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
