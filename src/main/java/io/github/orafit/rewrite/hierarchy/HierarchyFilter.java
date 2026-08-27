package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

/** Keeps Oracle residual WHERE predicates outside recursion so descendants are not pruned. */
final class HierarchyFilter {
    private HierarchyFilter() {}

    static Expression output(
            Expression where,
            Expression cycleFilter,
            Table source,
            String aliasName,
            String levelSlot)
            throws TranslationException {
        if (where == null) return cycleFilter;
        Table output = new Table(aliasName);
        for (Column column : ParserAdapter.nodes(where, Column.class)) {
            if (HierarchySupport.unqualified(column, "ROWNUM")) {
                throw unsupported(
                        "ROWNUM with CONNECT BY requires a separately verified evaluation-order"
                                + " plan");
            }
            if (HierarchySupport.unqualified(column, "LEVEL")) {
                column.setTable(output);
                column.setColumnName(levelSlot);
                continue;
            }
            String name = column.getUnquotedColumnName();
            if (name != null
                    && name.regionMatches(true, 0, "CONNECT_BY_", 0, "CONNECT_BY_".length())) {
                throw unsupported(
                        "hierarchy pseudo-column in WHERE is outside the current bounded subset: "
                                + name);
            }
            validateQualifier(column, source, aliasName);
            column.setTable(output);
        }
        return new AndExpression(where, cycleFilter);
    }

    private static void validateQualifier(Column column, Table source, String aliasName)
            throws TranslationException {
        String qualifier = HierarchySupport.invalidQualifier(column, source, aliasName);
        if (qualifier != null)
            throw unsupported("WHERE column is outside the CONNECT BY source: " + qualifier);
    }

    private static TranslationException unsupported(String message) {
        return HierarchySupport.fail("CONNECT_BY_WHERE", message);
    }
}
