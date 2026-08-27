package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;

/** Carries direct SYS_CONNECT_BY_PATH values through the recursive CTE. */
final class HierarchyPath {
    static final String PREFIX = "__orafit_value_path_";
    private static final HierarchyPath NONE = new HierarchyPath(List.of());

    private final List<State> states;

    private HierarchyPath(List<State> states) {
        this.states = List.copyOf(states);
    }

    static HierarchyPath resolve(PlainSelect select, Table source, String aliasName)
            throws TranslationException {
        List<State> states = new ArrayList<>();
        List<SelectItem<?>> items = select.getSelectItems();
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            List<Function> paths =
                    ParserAdapter.nodes(item.getExpression(), Function.class).stream()
                            .filter(HierarchyPath::isPath)
                            .toList();
            if (paths.isEmpty()) continue;
            if (!(item.getExpression() instanceof Function function)
                    || !isPath(function)
                    || paths.size() != 1) {
                throw unsupported(
                        "SYS_CONNECT_BY_PATH is initially supported only as a direct SELECT item");
            }
            if (item.getAlias() == null) {
                throw unsupported(
                        "SYS_CONNECT_BY_PATH requires an explicit alias to preserve JDBC column"
                                + " labels");
            }
            if (function.getParameters() == null || function.getParameters().size() != 2) {
                throw unsupported("SYS_CONNECT_BY_PATH requires value and delimiter");
            }
            Expression value = function.getParameters().get(0);
            Expression delimiter = function.getParameters().get(1);
            if (!(value instanceof Column column)) {
                throw unsupported(
                        "SYS_CONNECT_BY_PATH value must be one source column in the bounded CONNECT"
                                + " BY contract");
            }
            validateQualifier(column, source, aliasName);
            if (!(delimiter instanceof StringValue || delimiter instanceof JdbcNamedParameter)) {
                throw unsupported(
                        "SYS_CONNECT_BY_PATH delimiter must be a string literal or JDBC bind");
            }
            states.add(
                    new State(
                            i,
                            column.getColumnName(),
                            copyDelimiter(delimiter),
                            PREFIX + (states.size() + 1)));
        }
        return states.isEmpty() ? NONE : new HierarchyPath(states);
    }

    boolean present() {
        return !states.isEmpty();
    }

    void appendAnchor(PlainSelect select, Table child) {
        for (State state : states) {
            select.addSelectItem(
                    append(state.delimiter(), varchar(new Column(child, state.columnName()))));
        }
    }

    void appendRecursive(PlainSelect select, Table child, Table parent) {
        for (State state : states) {
            Expression segment =
                    append(
                            copyDelimiter(state.delimiter()),
                            varchar(new Column(child, state.columnName())));
            select.addSelectItem(append(new Column(parent, state.slot()), segment));
        }
    }

    void appendCteColumns(List<SelectItem<?>> columns) {
        for (State state : states) columns.add(SelectItem.from(new Column(state.slot())));
    }

    void rewriteProjection(PlainSelect select, String aliasName) {
        Table output = new Table(aliasName);
        List<SelectItem<?>> items = select.getSelectItems();
        for (State state : states) {
            SelectItem<?> original = items.get(state.itemIndex());
            original.getAlias().setUseAs(true);
            items.set(
                    state.itemIndex(),
                    SelectItem.from(new Column(output, state.slot()), original.getAlias()));
        }
    }

    private static Expression append(Expression left, Expression right) {
        return new Function("orafit.concat_varchar2", left, right);
    }

    private static Expression varchar(Expression value) {
        if (value instanceof StringValue || value instanceof NullValue) return value;
        return new Function(
                "orafit.to_varchar2",
                value,
                new StringValue("YYYY-MM-DD"),
                new StringValue("YYYY-MM-DD HH24:MI:SS"),
                new StringValue("YYYY-MM-DD HH24:MI:SS TZH:TZM"),
                new StringValue(".,"));
    }

    private static Expression copyDelimiter(Expression delimiter) {
        if (delimiter instanceof StringValue literal) return new StringValue(literal.getValue());
        JdbcNamedParameter named = (JdbcNamedParameter) delimiter;
        return new JdbcNamedParameter(named.getName())
                .setParameterCharacter(named.getParameterCharacter());
    }

    private static boolean isPath(Function function) {
        return function.getName() != null
                && function.getName().equalsIgnoreCase("SYS_CONNECT_BY_PATH");
    }

    private static void validateQualifier(Column column, Table source, String aliasName)
            throws TranslationException {
        String qualifier = HierarchySupport.invalidQualifier(column, source, aliasName);
        if (qualifier != null)
            throw unsupported(
                    "SYS_CONNECT_BY_PATH column is outside the CONNECT BY source: " + qualifier);
    }

    private static TranslationException unsupported(String message) {
        return HierarchySupport.fail("CONNECT_BY_PATH", message);
    }

    private record State(int itemIndex, String columnName, Expression delimiter, String slot) {}
}
