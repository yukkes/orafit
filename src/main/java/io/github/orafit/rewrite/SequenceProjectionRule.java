package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A volatile inner projection evaluates each sequence once per qualifying row. */
final class SequenceProjectionRule {
    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        for (PlainSelect select : SelectTrees.plain(statement)) {
            List<Column> columns = new ArrayList<>();
            for (SelectItem<?> item : select.getSelectItems())
                columns.addAll(ParserAdapter.columns(item.getExpression()));
            Map<String, List<Column>> sequences = new LinkedHashMap<>();
            for (Column column : columns) {
                if (sequence(column))
                    sequences
                            .computeIfAbsent(sequenceKey(column), key -> new ArrayList<>())
                            .add(column);
            }
            if (!sequences.isEmpty()
                    && select.getSelectItems().stream()
                            .anyMatch(
                                    item ->
                                            item.getExpression() instanceof Select
                                                    || !ParserAdapter.nodes(
                                                                    item.getExpression(),
                                                                    Select.class)
                                                            .isEmpty()))
                throw new TranslationException(
                        "SEQUENCE_PROJECTION",
                        "Sequence projections containing scalar subqueries require a separate scope design");
            if (sequences.values().stream()
                    .noneMatch(
                            refs ->
                                    refs.size() > 1
                                            && refs.stream()
                                                    .anyMatch(SequenceProjectionRule::next)))
                continue;
            if (!(statement instanceof Select)
                    || select.getDistinct() != null
                    || select.getGroupBy() != null
                    || select.getHaving() != null
                    || select.getOrderByElements() != null
                    || select.getFetch() != null
                    || select.getOffset() != null
                    || select.getLimit() != null
                    || SelectTrees.aggregateProjection(select)
                    || select.getSelectItems().stream()
                            .anyMatch(
                                    item ->
                                            item.getExpression()
                                                            instanceof
                                                            net.sf.jsqlparser.statement.select
                                                                    .AllColumns
                                                    || !ParserAdapter.nodes(
                                                                    item.getExpression(),
                                                                    Select.class)
                                                            .isEmpty())
                    || (select.getWhere() != null
                            && ParserAdapter.columns(select.getWhere()).stream()
                                    .anyMatch(SequenceProjectionRule::sequence))) {
                throw new TranslationException(
                        "SEQUENCE_PROJECTION",
                        "Repeated sequence references require an ordinary SELECT projection without aggregation or row limiting");
            }
            PlainSelect source = new PlainSelect();
            source.setFromItem(select.getFromItem());
            source.setJoins(select.getJoins());
            source.setWhere(select.getWhere());
            List<SelectItem<?>> inner = new ArrayList<>();
            for (var entry : sequences.entrySet()) {
                String alias = "s" + inner.size();
                String operation =
                        entry.getValue().stream().anyMatch(SequenceProjectionRule::next)
                                ? "nextval"
                                : "currval";
                inner.add(
                        SelectItem.from(
                                new Function(
                                        "pg_catalog." + operation, new StringValue(entry.getKey())),
                                new Alias(alias, true)));
                for (Column column : entry.getValue()) replace(column, alias);
            }
            for (Column column : columns) {
                if ("__orafit_seqrow".equals(column.getTableName())) continue;
                String alias = "c" + inner.size();
                inner.add(
                        SelectItem.from(
                                new Column(column.getTable(), column.getColumnName()),
                                new Alias(alias, true)));
                replace(column, alias);
            }
            source.setSelectItems(inner);
            ParenthesedSelect nested = new ParenthesedSelect();
            nested.setSelect(source);
            nested.setAlias(new Alias("__orafit_seqrow", true));
            select.setFromItem(nested);
            select.setJoins(null);
            select.setWhere(null);
            changed = true;
        }
        return changed;
    }

    private static void replace(Column column, String name) {
        column.setTable(new Table("__orafit_seqrow"));
        column.setColumnName(name);
    }

    static String sequenceKey(Column column) {
        String name = column.getTable().getFullyQualifiedName();
        return name.indexOf('"') >= 0 ? name : name.toLowerCase(Locale.ROOT);
    }

    private static boolean next(Column column) {
        return "NEXTVAL".equalsIgnoreCase(column.getUnquotedColumnName());
    }

    private static boolean sequence(Column column) {
        return column.getTable() != null
                && column.getTableName() != null
                && List.of("NEXTVAL", "CURRVAL")
                        .contains(column.getUnquotedColumnName().toUpperCase(Locale.ROOT));
    }
}
