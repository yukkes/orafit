package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Pivot;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.UnPivot;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lowers only the deliberately small static PIVOT/UNPIVOT compatibility subset. */
final class PivotUnpivotRule {
    private static final String PIVOT_CODE = "UNSUPPORTED_PIVOT";
    private static final String UNPIVOT_CODE = "UNSUPPORTED_UNPIVOT";
    private static final String UNPIVOT_ALIAS = "__orafit_unpivot";

    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            changed |= lower(select.getFromItem());
            if (select.getJoins() != null) {
                for (Join join : select.getJoins()) changed |= lower(join.getFromItem());
            }
        }
        return changed;
    }

    private static boolean lower(FromItem item) throws TranslationException {
        if (item == null || (item.getPivot() == null && item.getUnPivot() == null)) return false;
        if (!(item instanceof ParenthesedSelect source)) {
            throw unsupported(item.getPivot() != null ? PIVOT_CODE : UNPIVOT_CODE);
        }
        if (source.getPivot() != null && source.getUnPivot() != null) throw unsupported(PIVOT_CODE);
        if (source.getPivot() != null) lowerPivot(source);
        else lowerUnpivot(source);
        return true;
    }

    private static void lowerPivot(ParenthesedSelect source) throws TranslationException {
        Pivot pivot = source.getPivot();
        PlainSelect input = simpleInput(source, PIVOT_CODE);
        if (pivot.getAlias() != null
                || pivot.getFunctionItems() == null
                || pivot.getFunctionItems().size() != 1
                || pivot.getForColumns() == null
                || pivot.getForColumns().size() != 1
                || pivot.getSingleInItems() == null
                || pivot.getSingleInItems().isEmpty()
                || (pivot.getMultiInItems() != null && !pivot.getMultiInItems().isEmpty())) {
            throw unsupported(PIVOT_CODE);
        }

        SelectItem<Function> aggregateItem = pivot.getFunctionItems().get(0);
        Function aggregate = aggregateItem.getExpression();
        if (aggregateItem.getAlias() != null
                || aggregate == null
                || aggregate.getName() == null
                || !"SUM".equalsIgnoreCase(aggregate.getName())
                || aggregate.isDistinct()
                || aggregate.isUnique()
                || aggregate.isAllColumns()
                || aggregate.getParameters() == null
                || aggregate.getParameters().size() != 1
                || !(aggregate.getParameters().get(0) instanceof Column aggregateColumn)) {
            throw unsupported(PIVOT_CODE);
        }

        Column pivotColumn = pivot.getForColumns().get(0);
        String pivotName = sourceColumnName(pivotColumn, source.getAlias(), PIVOT_CODE);
        String aggregateName = sourceColumnName(aggregateColumn, source.getAlias(), PIVOT_CODE);
        List<SelectItem<?>> inputItems = simpleColumns(input, PIVOT_CODE);
        requireSelected(inputItems, pivotName, PIVOT_CODE);
        requireSelected(inputItems, aggregateName, PIVOT_CODE);

        List<SelectItem<?>> output = new ArrayList<>();
        List<Expression> groups = new ArrayList<>();
        for (SelectItem<?> item : inputItems) {
            Column column = (Column) item.getExpression();
            String name = column.getUnquotedColumnName();
            if (!name.equalsIgnoreCase(pivotName) && !name.equalsIgnoreCase(aggregateName)) {
                output.add(item);
                groups.add(column);
            }
        }

        for (SelectItem<?> in : pivot.getSingleInItems()) {
            Expression value = in.getExpression();
            if (in.getAlias() == null
                    || in.getAlias().getName() == null
                    || !staticPivotValue(value)) {
                throw unsupported(PIVOT_CODE);
            }
            Column pivotRef = new Column(pivotName);
            Expression condition =
                    value instanceof NullValue
                            ? new IsNullExpression(pivotRef)
                            : new EqualsTo(pivotRef, value);
            CaseExpression selected =
                    new CaseExpression(new WhenClause(condition, new Column(aggregateName)));
            output.add(new SelectItem<>(new Function("SUM", selected), in.getAlias()));
        }

        input.setSelectItems(output);
        input.setGroupByElement(
                groups.isEmpty()
                        ? null
                        : new GroupByElement()
                                .withGroupByExpressions(new ExpressionList<>(groups)));
        source.setPivot(null);
    }

    private static void lowerUnpivot(ParenthesedSelect source) throws TranslationException {
        UnPivot unpivot = source.getUnPivot();
        PlainSelect input = simpleInput(source, UNPIVOT_CODE);
        if (unpivot.getAlias() != null
                || unpivot.getUnPivotClause() == null
                || unpivot.getUnPivotClause().size() != 1
                || unpivot.getUnPivotForClause() == null
                || unpivot.getUnPivotForClause().size() != 1
                || unpivot.getUnPivotInClause() == null
                || unpivot.getUnPivotInClause().isEmpty()) {
            throw unsupported(UNPIVOT_CODE);
        }

        String valueName = unqualified(unpivot.getUnPivotClause().get(0), UNPIVOT_CODE);
        String forName = unqualified(unpivot.getUnPivotForClause().get(0), UNPIVOT_CODE);
        List<SelectItem<?>> inputItems = simpleColumns(input, UNPIVOT_CODE);
        Set<String> unpivoted = new HashSet<>();
        List<Expression> rows = new ArrayList<>();

        for (SelectItem<?> in : unpivot.getUnPivotInClause()) {
            if (!(in.getExpression() instanceof Column sourceColumn))
                throw unsupported(UNPIVOT_CODE);
            String sourceName = unqualified(sourceColumn, UNPIVOT_CODE);
            requireSelected(inputItems, sourceName, UNPIVOT_CODE);
            if (!unpivoted.add(sourceName.toUpperCase(Locale.ROOT)))
                throw unsupported(UNPIVOT_CODE);
            String label = in.getAlias() == null ? sourceName : in.getAlias().getName();
            rows.add(
                    new ParenthesedExpressionList<>(
                            new StringValue(label), new Column(sourceName)));
        }

        List<SelectItem<?>> output = new ArrayList<>();
        for (SelectItem<?> item : inputItems) {
            Column column = (Column) item.getExpression();
            if (!unpivoted.contains(column.getUnquotedColumnName().toUpperCase(Locale.ROOT))) {
                if (column.getUnquotedColumnName().equalsIgnoreCase(valueName)
                        || column.getUnquotedColumnName().equalsIgnoreCase(forName)) {
                    throw unsupported(UNPIVOT_CODE);
                }
                output.add(item);
            }
        }

        Table lateralTable = new Table(UNPIVOT_ALIAS);
        output.add(new SelectItem<>(new Column(lateralTable, forName)));
        output.add(new SelectItem<>(new Column(lateralTable, valueName)));
        input.setSelectItems(output);

        Values values = new Values(new ExpressionList<>(rows));
        Alias alias = new Alias(UNPIVOT_ALIAS, false).addAliasColumns(forName, valueName);
        Join lateral = new Join().withCross(true);
        lateral.setFromItem(new LateralSubSelect(values, alias));
        input.addJoins(lateral);

        if (!unpivot.getIncludeNulls()) {
            IsNullExpression present =
                    new IsNullExpression(new Column(lateralTable, valueName)).withNot(true);
            input.setWhere(
                    input.getWhere() == null
                            ? present
                            : new AndExpression(input.getWhere(), present));
        }
        source.setUnPivot(null);
    }

    private static PlainSelect simpleInput(ParenthesedSelect source, String code)
            throws TranslationException {
        if (!(source.getSelect() instanceof PlainSelect input)
                || input.getDistinct() != null
                || input.getGroupBy() != null
                || input.getHaving() != null
                || (input.getOrderByElements() != null && !input.getOrderByElements().isEmpty())) {
            throw unsupported(code);
        }
        return input;
    }

    private static List<SelectItem<?>> simpleColumns(PlainSelect input, String code)
            throws TranslationException {
        if (input.getSelectItems() == null || input.getSelectItems().isEmpty())
            throw unsupported(code);
        for (SelectItem<?> item : input.getSelectItems()) {
            if (item.getAlias() != null || !(item.getExpression() instanceof Column)) {
                throw unsupported(code);
            }
        }
        return input.getSelectItems();
    }

    private static void requireSelected(List<SelectItem<?>> items, String name, String code)
            throws TranslationException {
        int matches = 0;
        for (SelectItem<?> item : items) {
            Column column = (Column) item.getExpression();
            if (column.getUnquotedColumnName().equalsIgnoreCase(name)) matches++;
        }
        if (matches != 1) throw unsupported(code);
    }

    private static String sourceColumnName(Column column, Alias sourceAlias, String code)
            throws TranslationException {
        String qualifier = column.getUnquotedTableName();
        if (qualifier != null
                && !qualifier.isBlank()
                && (sourceAlias == null
                        || !qualifier.equalsIgnoreCase(sourceAlias.getUnquotedName()))) {
            throw unsupported(code);
        }
        return column.getUnquotedColumnName();
    }

    private static String unqualified(Column column, String code) throws TranslationException {
        String qualifier = column.getUnquotedTableName();
        if (qualifier != null && !qualifier.isBlank()) throw unsupported(code);
        return column.getUnquotedColumnName();
    }

    private static boolean staticPivotValue(Expression value) {
        return value instanceof StringValue
                || value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof NullValue;
    }

    private static TranslationException unsupported(String code) {
        return new TranslationException(
                code, "PIVOT/UNPIVOT form is outside the supported basic subset");
    }
}
