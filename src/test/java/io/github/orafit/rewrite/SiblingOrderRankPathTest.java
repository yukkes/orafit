package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

/** Focused contracts for bounded advanced ORDER SIBLINGS BY lowering. */
public final class SiblingOrderRankPathTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        Translation desc =
                engine.translate(
                        "SELECT id FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY PRIOR id = parent_id ORDER SIBLINGS BY name DESC");
        String descSql = desc.sql().toUpperCase();
        check(
                descSql.contains("ROW_NUMBER() OVER (ORDER BY __ORAFIT_HC.NAME DESC)"),
                "root siblings must receive a descending dense rank");
        check(
                descSql.contains("ROW_NUMBER() OVER (PARTITION BY __ORAFIT_HR.PARENT_ID")
                        && descSql.contains("ORDER BY __ORAFIT_HR.NAME DESC"),
                "recursive siblings must rank independently per direct parent link");
        check(
                descSql.contains("ARRAY_APPEND(__ORAFIT_HP.__ORAFIT_SIBLING_PATH")
                        && descSql.contains("__ORAFIT_HC.__ORAFIT_SIBLING_RANK"),
                "recursive rows must append sibling rank to the inherited rank path");
        check(
                descSql.endsWith("ORDER BY ORAFIT_TREE.__ORAFIT_SIBLING_PATH"),
                "advanced hierarchy output must sort by the generated sibling rank path");
        check(
                !descSql.contains("SEARCH DEPTH FIRST"),
                "advanced sibling ordering must not use the ASC-only SEARCH path");

        Translation multi =
                engine.translate(
                        "SELECT id, name FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY PRIOR id = parent_id "
                                + "ORDER SIBLINGS BY name DESC NULLS LAST, id ASC");
        String multiSql = multi.sql().toUpperCase();
        check(
                multiSql.contains("ORDER BY __ORAFIT_HR.NAME DESC NULLS LAST, __ORAFIT_HR.ID ASC"),
                "multiple sibling keys must preserve direction and explicit NULL ordering");

        Translation alias =
                engine.translate(
                        "SELECT id, UPPER(name) AS sort_name FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id "
                                + "ORDER SIBLINGS BY sort_name DESC");
        String aliasSql = alias.sql().toUpperCase();
        check(
                aliasSql.contains("ORDER BY UPPER(__ORAFIT_HR.NAME) DESC"),
                "metadata-preserving SELECT aliases must resolve back to their source expression");

        Translation ordinal =
                engine.translate(
                        "SELECT id, UPPER(name) AS sort_name FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id "
                                + "ORDER SIBLINGS BY 2 DESC");
        String ordinalSql = ordinal.sql().toUpperCase();
        check(
                ordinalSql.contains("ORDER BY UPPER(__ORAFIT_HR.NAME) DESC"),
                "SELECT ordinals must reuse the bounded sibling expression resolver");

        Translation lowerAlias =
                engine.translate(
                        "SELECT id, LOWER(name) AS sort_name FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id "
                                + "ORDER SIBLINGS BY sort_name DESC");
        check(
                lowerAlias.sql().toUpperCase().contains("ORDER BY LOWER(__ORAFIT_HR.NAME) DESC"),
                "LOWER aliases must use the same metadata-preserving sibling path");

        Translation wrappedAlias =
                engine.translate(
                        "SELECT name AS display_name FROM orafit_tree "
                                + "START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id "
                                + "ORDER SIBLINGS BY LOWER(display_name)");
        check(
                wrappedAlias.sql().toUpperCase().contains("LOWER(__ORAFIT_HR.NAME)"),
                "LOWER applied to a direct-column SELECT alias must resolve to the source column");
        Translation simple =
                engine.translate(
                        "SELECT id, name FROM orafit_tree START WITH parent_id IS NULL "
                                + "CONNECT BY PRIOR id = parent_id ORDER SIBLINGS BY name");
        check(
                simple.sql().toUpperCase().contains("SEARCH DEPTH FIRST BY NAME SET"),
                "existing one-key ascending sibling order must keep the SEARCH fast path");

        unsupported(
                engine,
                "SELECT id FROM orafit_tree START WITH parent_id IS NULL "
                        + "CONNECT BY PRIOR id = parent_id "
                        + "ORDER SIBLINGS BY CASE WHEN name = 'x' THEN 0 ELSE 1 END, id",
                "CONNECT_BY_ORDER");

        System.out.println("SiblingOrderRankPathTest OK");
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
