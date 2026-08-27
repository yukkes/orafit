package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Contracts for compact CONNECT BY to recursive CTE lowering. */
public final class HierarchyRewriteTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        Translation core =
                engine.translate(
                        "SELECT id, name, LEVEL FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id");
        String sql = core.sql().toUpperCase();
        check(
                sql.startsWith("WITH RECURSIVE __ORAFIT_H("),
                "CONNECT BY must become a recursive CTE");
        check(sql.contains("UNION ALL"), "hierarchy CTE must contain anchor and recursive members");
        check(
                sql.contains("ARRAY_POSITION"),
                "hierarchy must detect ancestor cycles by physical row identity");
        check(
                sql.contains("TABLEOID"),
                "physical row identity must distinguish partition relations");
        check(
                sql.contains("ORAFIT.CONNECT_BY_CYCLE_GUARD"),
                "non-NOCYCLE hierarchy must map cycles to ORA-01436 helper");
        check(sql.contains("__ORAFIT_LEVEL AS LEVEL"), "LEVEL output label must be preserved");
        check(!sql.contains("CONNECT BY"), "Oracle CONNECT BY syntax must not reach PostgreSQL");

        Translation bound =
                engine.translate(
                        "SELECT id, LEVEL FROM orafit_tree "
                                + "START WITH parent_id = ? CONNECT BY PRIOR id = parent_id");
        equal(
                List.of(1),
                bound.binds().outputToInput(),
                "START WITH bind must preserve JDBC lineage");

        Translation filtered =
                engine.translate(
                        "SELECT id, LEVEL FROM orafit_tree WHERE name = ? "
                                + "START WITH parent_id = ? CONNECT BY PRIOR id = parent_id");
        String filteredSql = filtered.sql().toUpperCase();
        check(
                filteredSql.contains("WHERE __ORAFIT_HC.PARENT_ID = ?"),
                "START WITH must stay in the anchor member");
        check(
                filteredSql.contains(
                        "WHERE ORAFIT_TREE.NAME = ? AND NOT ORAFIT_TREE.__ORAFIT_CYCLE"),
                "ordinary WHERE must run after hierarchy generation instead of pruning"
                        + " descendants");
        equal(
                List.of(2, 1),
                filtered.binds().outputToInput(),
                "generated SQL may reorder hierarchy binds but lineage must preserve original JDBC"
                        + " positions");

        Translation levelFilter =
                engine.translate(
                        "SELECT id, LEVEL FROM orafit_tree WHERE LEVEL <= ? "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id");
        check(
                levelFilter.sql().toUpperCase().contains("ORAFIT_TREE.__ORAFIT_LEVEL <= ?"),
                "post-hierarchy LEVEL predicates must use hidden hierarchy state");

        Translation nocycle =
                engine.translate(
                        "SELECT id, LEVEL FROM orafit_tree START WITH parent_id IS NULL CONNECT"
                                + " BY NOCYCLE PRIOR id = parent_id");
        String nocycleSql = nocycle.sql().toUpperCase();
        check(
                !nocycleSql.contains("CONNECT_BY_CYCLE_GUARD"),
                "NOCYCLE must suppress repeated ancestors without throwing");
        check(
                nocycleSql.contains("NOT ORAFIT_TREE.__ORAFIT_CYCLE")
                        || nocycleSql.contains("NOT ORAFIT_TREE . __ORAFIT_CYCLE"),
                "NOCYCLE final output must hide repeated ancestor rows");

        Translation siblings =
                engine.translate(
                        "SELECT id, name, LEVEL FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id "
                                + "ORDER SIBLINGS BY name");
        String siblingsSql = siblings.sql().toUpperCase();
        check(
                siblingsSql.contains("SEARCH DEPTH FIRST BY NAME SET __ORAFIT_SIBLING_ORDER"),
                "ascending direct-column ORDER SIBLINGS must use PostgreSQL SEARCH DEPTH FIRST");
        check(
                siblingsSql.endsWith("ORDER BY ORAFIT_TREE.__ORAFIT_SIBLING_ORDER"),
                "hierarchy output must sort by the generated depth-first sequence");
        check(
                !siblingsSql.contains("ORDER SIBLINGS"),
                "Oracle sibling syntax must not reach PostgreSQL");

        Translation outputOrder =
                engine.translate(
                        "SELECT id, name AS sort_name, LEVEL AS level_no FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id "
                                + "ORDER BY sort_name DESC NULLS LAST, 1 ASC");
        String outputOrderSql = outputOrder.sql().toUpperCase();
        check(
                outputOrderSql.contains("ORDER BY SORT_NAME DESC NULLS LAST, 1 ASC"),
                "ordinary hierarchy ORDER BY must preserve SELECT aliases, ordinals and direction");

        Translation levelOrder =
                engine.translate(
                        "SELECT id FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY PRIOR id = parent_id ORDER BY LEVEL DESC, name");
        String levelOrderSql = levelOrder.sql().toUpperCase();
        check(
                levelOrderSql.contains(
                        "ORDER BY ORAFIT_TREE.__ORAFIT_LEVEL DESC, ORAFIT_TREE.NAME"),
                "ordinary hierarchy ORDER BY must map LEVEL and source columns to output state");

        Translation root =
                engine.translate(
                        "SELECT CONNECT_BY_ROOT id AS root_id, id, LEVEL FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id");
        String rootSql = root.sql().toUpperCase();
        check(
                rootSql.contains("__ORAFIT_ROOT_1"),
                "CONNECT_BY_ROOT must carry one hidden root state column");
        check(
                rootSql.contains("ORAFIT_TREE.__ORAFIT_ROOT_1 AS ROOT_ID"),
                "CONNECT_BY_ROOT alias must be preserved in final projection");
        check(
                !rootSql.contains("CONNECT_BY_ROOT"),
                "CONNECT_BY_ROOT syntax must not reach PostgreSQL");

        Translation priorAddition =
                engine.translate(
                        "SELECT id, LEVEL FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY PRIOR (id + 1) = parent_id");
        String priorAdditionSql = priorAddition.sql().toUpperCase();
        check(
                priorAdditionSql.contains("__ORAFIT_HP.ID + 1")
                        && priorAdditionSql.contains("__ORAFIT_HC.PARENT_ID"),
                "PRIOR arithmetic must render against the parent and child aliases");

        Translation priorAbs =
                engine.translate(
                        "SELECT id, LEVEL FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY PRIOR ABS(id) = parent_id");
        String priorAbsSql = priorAbs.sql().toUpperCase();
        check(
                priorAbsSql.contains("ABS(__ORAFIT_HP.ID)")
                        && priorAbsSql.contains("__ORAFIT_HC.PARENT_ID"),
                "PRIOR ABS must render against the parent and child aliases");

        Translation physicalWildcard =
                engine.translate(
                        "SELECT * FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY NOCYCLE PRIOR id = parent_id");
        String physicalWildcardSql = physicalWildcard.sql().toUpperCase();
        check(
                physicalWildcardSql.startsWith("WITH RECURSIVE __ORAFIT_H("),
                "physical wildcard must keep the hierarchy in one recursive CTE");
        check(
                physicalWildcardSql.contains("SELECT __ORAFIT_HW.* FROM __ORAFIT_H ORAFIT_TREE"),
                "physical wildcard must project the original table scan for JDBC metadata");
        check(
                physicalWildcardSql.contains("JOIN ORAFIT_TREE __ORAFIT_HW ON")
                        && physicalWildcardSql.contains("ORAFIT_TREE.__ORAFIT_ROW_IDENTITY"),
                "physical wildcard must rejoin the current physical row by stable row identity");

        Translation qualifiedWildcard =
                engine.translate(
                        "SELECT t.* FROM orafit_tree t START WITH t.parent_id IS NULL "
                                + "CONNECT BY NOCYCLE PRIOR t.id = t.parent_id");
        check(
                qualifiedWildcard.sql().toUpperCase().contains("SELECT __ORAFIT_HW.*"),
                "qualified physical wildcard must use the same metadata-preserving rejoin");

        Translation derived =
                engine.translate(
                        "SELECT x.id, x.name, LEVEL FROM "
                                + "(SELECT id, parent_id, name FROM orafit_tree) x "
                                + "START WITH x.parent_id IS NULL "
                                + "CONNECT BY NOCYCLE PRIOR x.id = x.parent_id");
        String derivedSql = derived.sql().toUpperCase();
        check(
                derivedSql.contains("__ORAFIT_B_HSOURCE AS MATERIALIZED"),
                "derived hierarchy source must be materialized once");
        check(
                derivedSql.contains("ROW_NUMBER() OVER ()")
                        && derivedSql.contains("__ORAFIT_B_HROWID"),
                "derived hierarchy source must own one stable synthetic row identity");
        check(
                !derivedSql.contains("TABLEOID") && !derivedSql.contains("CTID"),
                "derived hierarchy source must not depend on physical row identity");

        Translation cte =
                engine.translate(
                        "WITH src(id, parent_id, name) AS "
                                + "(SELECT id, parent_id, name FROM orafit_tree) "
                                + "SELECT s.id, s.name, LEVEL FROM src s "
                                + "START WITH s.parent_id IS NULL "
                                + "CONNECT BY NOCYCLE PRIOR s.id = s.parent_id");
        String cteSql = cte.sql().toUpperCase();
        check(
                cteSql.startsWith("WITH RECURSIVE SRC"),
                "existing CTEs must stay before generated recursive hierarchy state");
        check(
                cteSql.contains("__ORAFIT_B_HSOURCE AS MATERIALIZED")
                        && !cteSql.contains(", RECURSIVE __ORAFIT_H"),
                "generated source and hierarchy CTEs must share one legal WITH RECURSIVE clause");

        unsupported(
                engine,
                "SELECT x.id FROM (SELECT * FROM orafit_tree) x "
                        + "START WITH x.parent_id IS NULL CONNECT BY PRIOR x.id = x.parent_id",
                "CONNECT_BY_SOURCE");
        unsupported(
                engine,
                "SELECT id FROM orafit_tree WHERE ROWNUM <= 3 "
                        + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id",
                "CONNECT_BY_WHERE");
        unsupported(
                engine,
                "SELECT id FROM orafit_tree START WITH parent_id IS NULL "
                        + "CONNECT BY PRIOR id = parent_id ORDER BY UPPER(name)",
                "CONNECT_BY_ORDER");
        unsupported(
                engine,
                "SELECT id FROM orafit_tree START WITH parent_id IS NULL "
                        + "CONNECT BY PRIOR UPPER(name) = name",
                "CONNECT_BY_PRIOR");
        unsupported(
                engine,
                "SELECT CONNECT_BY_ROOT id, id FROM orafit_tree "
                        + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id",
                "CONNECT_BY_ROOT");
        unsupported(
                engine,
                "SELECT COALESCE(CONNECT_BY_ROOT id, 0) AS root_id, id FROM orafit_tree "
                        + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id",
                "CONNECT_BY_ROOT");

        System.out.println("HierarchyRewriteTest OK");
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
