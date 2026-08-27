package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.ConnectByRootOperator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;

/** Carries bounded CONNECT_BY_ROOT source expressions through the recursive CTE. */
final class HierarchyRoot {
    static final String PREFIX = "__orafit_root_";
    private static final HierarchyRoot NONE = new HierarchyRoot(List.of(), List.of());

    private final List<State> states;
    private final List<Projection> projections;

    private HierarchyRoot(List<State> states, List<Projection> projections) {
        this.states = List.copyOf(states);
        this.projections = List.copyOf(projections);
    }

    static HierarchyRoot resolve(PlainSelect select, Table source, String aliasName)
            throws TranslationException {
        List<State> states = new ArrayList<>();
        List<Projection> projections = new ArrayList<>();
        List<SelectItem<?>> items = select.getSelectItems();
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            List<ConnectByRootOperator> roots =
                    ParserAdapter.nodes(item.getExpression(), ConnectByRootOperator.class);
            if (roots.isEmpty()) continue;
            if (!(item.getExpression() instanceof ConnectByRootOperator root)
                    || roots.size() != 1) {
                throw unsupported(
                        "CONNECT_BY_ROOT is initially supported only as a direct SELECT item");
            }
            if (item.getAlias() == null) {
                throw unsupported(
                        "CONNECT_BY_ROOT requires an explicit alias to preserve JDBC column"
                                + " labels");
            }
            Expression expression = unwrap(root.getExpression());
            if (!supportedExpression(expression)) {
                throw unsupported(
                        "CONNECT_BY_ROOT expression is outside the bounded source-expression"
                                + " subset");
            }
            List<Column> columns = ParserAdapter.nodes(expression, Column.class);
            if (columns.isEmpty()) {
                throw unsupported("CONNECT_BY_ROOT expression must reference the hierarchy source");
            }
            for (Column column : columns) {
                if (hierarchyState(column)) {
                    throw unsupported(
                            "CONNECT_BY_ROOT expression cannot depend on hierarchy output state");
                }
                validateQualifier(column, source, aliasName);
            }
            State state = new State(expression, PREFIX + (states.size() + 1));
            states.add(state);
            projections.add(new Projection(i, state.slot()));
            items.set(i, SelectItem.from(new NullValue(), item.getAlias()));
        }
        return states.isEmpty() ? NONE : new HierarchyRoot(states, projections);
    }

    boolean present() {
        return !states.isEmpty();
    }

    void appendAnchor(PlainSelect select, Table child) {
        for (State state : states) {
            for (Column column : ParserAdapter.nodes(state.expression(), Column.class)) {
                column.setTable(new Table(child.getName()));
            }
            select.addSelectItem(state.expression());
        }
    }

    void appendRecursive(PlainSelect select, Table parent) {
        for (State state : states) select.addSelectItem(new Column(parent, state.slot()));
    }

    void appendCteColumns(List<SelectItem<?>> columns) {
        for (State state : states) columns.add(SelectItem.from(new Column(state.slot())));
    }

    void rewriteProjection(PlainSelect select, String aliasName) {
        Table output = new Table(aliasName);
        List<SelectItem<?>> items = select.getSelectItems();
        for (Projection projection : projections) {
            SelectItem<?> original = items.get(projection.itemIndex());
            items.set(
                    projection.itemIndex(),
                    SelectItem.from(new Column(output, projection.slot()), original.getAlias()));
        }
    }

    private static Expression unwrap(Expression expression) throws TranslationException {
        if (!(expression instanceof ParenthesedExpressionList<?> parenthesized)) return expression;
        if (parenthesized.size() != 1) {
            throw unsupported("CONNECT_BY_ROOT parenthesized expression must contain one value");
        }
        return parenthesized.get(0);
    }

    private static boolean supportedExpression(Expression expression) {
        if (expression instanceof Column) return true;
        if (expression instanceof Addition addition) {
            return (addition.getLeftExpression() instanceof Column
                            && addition.getRightExpression() instanceof LongValue)
                    || (addition.getLeftExpression() instanceof LongValue
                            && addition.getRightExpression() instanceof Column);
        }
        if (!(expression instanceof Function function)
                || function.getName() == null
                || !(function.getName().equalsIgnoreCase("UPPER")
                        || function.getName().equalsIgnoreCase("LOWER"))
                || function.getParameters() == null
                || function.getParameters().size() != 1) return false;
        return function.getParameters().get(0) instanceof Column;
    }

    private static boolean hierarchyState(Column column) {
        if (column.getTableName() != null && !column.getTableName().isBlank()) return false;
        String name = column.getUnquotedColumnName();
        return "LEVEL".equalsIgnoreCase(name)
                || "ROWNUM".equalsIgnoreCase(name)
                || name.toUpperCase(java.util.Locale.ROOT).startsWith("CONNECT_BY_");
    }

    private static void validateQualifier(Column column, Table source, String aliasName)
            throws TranslationException {
        String qualifier = HierarchySupport.invalidQualifier(column, source, aliasName);
        if (qualifier != null)
            throw unsupported(
                    "CONNECT_BY_ROOT column is outside the CONNECT BY source: " + qualifier);
    }

    private static TranslationException unsupported(String message) {
        return HierarchySupport.fail("CONNECT_BY_ROOT", message);
    }

    private record State(Expression expression, String slot) {}

    private record Projection(int itemIndex, String slot) {}
}
