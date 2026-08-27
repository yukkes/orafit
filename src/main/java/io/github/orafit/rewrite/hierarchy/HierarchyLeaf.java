package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;

/** Replaces direct CONNECT_BY_ISLEAF projections with a correlated child-existence probe. */
final class HierarchyLeaf {
    private final List<Integer> itemIndexes;

    private HierarchyLeaf(List<Integer> itemIndexes) {
        this.itemIndexes = List.copyOf(itemIndexes);
    }

    static HierarchyLeaf resolve(PlainSelect select) throws TranslationException {
        List<Integer> indexes = new ArrayList<>();
        List<SelectItem<?>> items = select.getSelectItems();
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            List<Column> columns = ParserAdapter.nodes(item.getExpression(), Column.class);
            List<Column> leafColumns =
                    columns.stream()
                            .filter(column -> isPseudo(column, "CONNECT_BY_ISLEAF"))
                            .toList();
            List<Column> cycleColumns =
                    columns.stream()
                            .filter(column -> isPseudo(column, "CONNECT_BY_ISCYCLE"))
                            .toList();
            if (!cycleColumns.isEmpty()) {
                throw new TranslationException(
                        "CONNECT_BY_ISCYCLE",
                        "CONNECT_BY_ISCYCLE is deferred until NOCYCLE cycle-row semantics are"
                                + " differential-tested");
            }
            if (leafColumns.isEmpty()) continue;
            if (!(item.getExpression() instanceof Column column)
                    || !isPseudo(column, "CONNECT_BY_ISLEAF")
                    || leafColumns.size() != 1) {
                throw new TranslationException(
                        "CONNECT_BY_ISLEAF",
                        "CONNECT_BY_ISLEAF is initially supported only as a direct SELECT item");
            }
            indexes.add(i);
        }
        return new HierarchyLeaf(indexes);
    }

    boolean present() {
        return !itemIndexes.isEmpty();
    }

    void rewriteProjection(
            PlainSelect output,
            Table probeSource,
            Table probe,
            Table current,
            Expression childMatch,
            Expression probeIdentity,
            String pathSlot) {
        if (!present()) return;
        PlainSelect childProbe = new PlainSelect();
        childProbe.addSelectItem(new LongValue(1));
        childProbe.setFromItem(probeSource);
        Function position =
                new Function("array_position", new Column(current, pathSlot), probeIdentity);
        Expression notAncestor = new IsNullExpression(position);
        childProbe.setWhere(new AndExpression(childMatch, notAncestor));
        ExistsExpression exists =
                new ExistsExpression()
                        .withRightExpression(new ParenthesedSelect().withSelect(childProbe));
        CaseExpression leaf = new CaseExpression();
        leaf.setWhenClauses(List.of(new WhenClause(exists, new LongValue(0))));
        leaf.setElseExpression(new LongValue(1));

        List<SelectItem<?>> items = output.getSelectItems();
        for (int index : itemIndexes) {
            SelectItem<?> original = items.get(index);
            Alias alias =
                    original.getAlias() == null
                            ? new Alias("CONNECT_BY_ISLEAF", true)
                            : original.getAlias();
            alias.setUseAs(true);
            items.set(index, SelectItem.from(leaf, alias));
        }
    }

    static boolean isLeaf(Column column) {
        return isPseudo(column, "CONNECT_BY_ISLEAF");
    }

    static boolean isCycle(Column column) {
        return isPseudo(column, "CONNECT_BY_ISCYCLE");
    }

    private static boolean isPseudo(Column column, String name) {
        return HierarchySupport.unqualified(column, name);
    }
}
