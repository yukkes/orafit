package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative ROWNUM rewrite for plain query-block prefix limits. */
final class RownumRule {
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    Result rewrite(Statement statement) throws TranslationException {
        boolean changed = rewriteProjectedPagination(statement);

        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class))
            changed |= rewriteBlock(select);
        return new Result(statement, changed);
    }

    private static boolean rewriteProjectedPagination(Statement statement)
            throws TranslationException {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            int itemIndex = projectedRownum(select);
            if (itemIndex < 0) continue;
            SelectItem<?> item = select.getSelectItems().get(itemIndex);
            if (item.getAlias() == null && select.getFromItem() instanceof ParenthesedSelect)
                throw nestedUnsupported("ROWNUM projection requires an alias for pagination");
            if (select.getLimit() != null
                    || select.getOffset() != null
                    || select.getFetch() != null) {
                throw nestedUnsupported(
                        "ROWNUM pagination cannot be combined with LIMIT/OFFSET/FETCH");
            }
            if (select.getJoins() != null && !select.getJoins().isEmpty()
                    || select.getDistinct() != null
                    || select.getGroupBy() != null
                    || select.getHaving() != null
                    || hasOrderBy(select)
                    || select.getForMode() != null) {
                throw nestedUnsupported(
                        "projected ROWNUM pagination requires one cardinality-preserving middle"
                                + " block");
            }
            Bound bound = parseBound(select.getWhere());
            if (bound == null || !(bound.operator().equals("<") || bound.operator().equals("<="))) {
                throw nestedUnsupported("projected ROWNUM requires one direct < or <= upper bound");
            }
            AnalyticExpression rowNumber = new AnalyticExpression();
            rowNumber.setName("ROW_NUMBER");
            Alias alias = item.getAlias() == null ? new Alias("ROWNUM", true) : item.getAlias();
            select.getSelectItems().set(itemIndex, SelectItem.from(rowNumber, alias));
            List<OrderByElement> order = sourceOrder(select);
            if (!order.isEmpty()) rowNumber.setOrderByElements(order);
            select.setWhere(null);
            select.setFetch(
                    fetch(
                            bound.bind() == null
                                    ? new LongValue(limit(bound))
                                    : dynamicLimit(bound)));
            changed = true;
        }
        return changed;
    }

    private static int projectedRownum(PlainSelect select) throws TranslationException {
        int found = -1;
        List<SelectItem<?>> items = select.getSelectItems();
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i).getExpression() instanceof Column column) || !isRownum(column))
                continue;
            if (found >= 0)
                throw nestedUnsupported(
                        "multiple projected ROWNUM values are outside classic pagination");
            found = i;
        }
        return found;
    }

    private static List<OrderByElement> sourceOrder(PlainSelect select)
            throws TranslationException {
        if (!(select.getFromItem() instanceof ParenthesedSelect parenthesed)
                || !(parenthesed.getSelect() instanceof PlainSelect source)
                || source.getOrderByElements() == null) return List.of();
        int hidden = 0;
        for (OrderByElement element : source.getOrderByElements()) {
            Expression expression = element.getExpression();
            if (expression instanceof LongValue) continue;
            if (!(expression instanceof Column column)) {
                throw nestedUnsupported(
                        "ordered pagination source requires direct output columns or ordinals");
            }
            if (column.getTableName() == null || column.getTableName().isBlank()) continue;
            String output = projectedName(source, column);
            if (output == null) {
                expandQualifiedWildcard(select, parenthesed, source);
                do {
                    output = "__orafit_order_" + (++hidden);
                } while (hasOutputName(source, output));
                source.getSelectItems().add(SelectItem.from(column, new Alias(output, true)));
            }
            element.setExpression(new Column(output));
        }
        return List.copyOf(source.getOrderByElements());
    }

    private static String projectedName(PlainSelect source, Column column) {
        boolean joined = source.getJoins() != null && !source.getJoins().isEmpty();
        for (SelectItem<?> item : source.getSelectItems()) {
            if (!(item.getExpression() instanceof Column output)
                    || !column.getUnquotedColumnName()
                            .equalsIgnoreCase(output.getUnquotedColumnName())) continue;
            if (column.getUnquotedTableName() != null
                    && (output.getUnquotedTableName() == null && joined
                            || output.getUnquotedTableName() != null
                                    && !column.getUnquotedTableName()
                                            .equalsIgnoreCase(output.getUnquotedTableName()))) {
                continue;
            }
            return item.getAlias() == null
                    ? output.getUnquotedColumnName()
                    : item.getAlias().getUnquotedName();
        }
        return null;
    }

    private static void expandQualifiedWildcard(
            PlainSelect select, ParenthesedSelect parenthesed, PlainSelect source)
            throws TranslationException {
        if (parenthesed.getAlias() == null) return;
        String alias = parenthesed.getAlias().getUnquotedName();
        List<SelectItem<?>> items = select.getSelectItems();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i).getExpression() instanceof AllTableColumns all)
                    || all.getTable() == null
                    || !alias.equalsIgnoreCase(all.getTable().getUnquotedName())) continue;
            List<SelectItem<?>> expanded =
                    new ArrayList<>(items.size() + source.getSelectItems().size());
            expanded.addAll(items.subList(0, i));
            List<String> names = new ArrayList<>();
            for (SelectItem<?> sourceItem : source.getSelectItems()) {
                String name = outputName(sourceItem);
                if (name == null || names.stream().anyMatch(name::equalsIgnoreCase)) {
                    throw nestedUnsupported(
                            "pagination wildcard requires uniquely named source columns");
                }
                names.add(name);
                expanded.add(SelectItem.from(new Column(new Table(alias), name)));
            }
            expanded.addAll(items.subList(i + 1, items.size()));
            select.setSelectItems(expanded);
            return;
        }
    }

    private static boolean hasOutputName(PlainSelect select, String name) {
        for (SelectItem<?> item : select.getSelectItems())
            if (name.equalsIgnoreCase(outputName(item))) return true;
        return false;
    }

    private static String outputName(SelectItem<?> item) {
        if (item.getAlias() != null) return item.getAlias().getUnquotedName();
        return item.getExpression() instanceof Column column
                ? column.getUnquotedColumnName()
                : null;
    }

    private static boolean rewriteBlock(PlainSelect select) throws TranslationException {
        Expression where = select.getWhere();
        if (!containsRownum(where)) {
            ensureNoRownumOutsideWhere(select);
            return false;
        }
        if (select.getLimit() != null || select.getOffset() != null || select.getFetch() != null) {
            throw unsupported(
                    "ROWNUM_PAGINATION", "ROWNUM cannot be combined with LIMIT/OFFSET/FETCH");
        }
        if (hasOrderBy(select)) {
            throw unsupported(
                    "ROWNUM_ORDER_BY", "Nested ROWNUM with ORDER BY is not safely lowerable yet");
        }
        if (rewriteAggregateFirstRow(select)) return true;
        if (select.getDistinct() != null
                || select.getGroupBy() != null
                || select.getHaving() != null
                || SelectTrees.containsAggregateOrWindow(select)) {
            throw unsupported(
                    "ROWNUM_CARDINALITY",
                    "The bounded ROWNUM contract rejects cardinality-changing query blocks");
        }
        ensureNoRownumOutsideWhere(select);

        List<Expression> retained = new ArrayList<>();
        List<Bound> bounds = new ArrayList<>();
        for (Expression term : SelectTrees.andTerms(where)) {
            if (!containsRownum(term)) {
                retained.add(term);
                continue;
            }
            Bound bound = parseBound(term);
            if (bound == null) {
                throw unsupported(
                        "ROWNUM_EXPRESSION",
                        "The bounded ROWNUM contract supports atomic numeric-literal or JDBC-bind"
                                + " comparisons");
            }
            if (bound.operator().equals(">") || bound.operator().equals(">=")) {
                throw unsupported(
                        "ROWNUM_DIRECTION", "ROWNUM > and >= are outside the supported contract");
            }
            bounds.add(bound);
        }
        if (bounds.isEmpty()) return false;

        long literalLimit = Long.MAX_VALUE;
        List<Expression> dynamicLimits = new ArrayList<>();
        for (Bound bound : bounds) {
            if (bound.bind() == null) literalLimit = Math.min(literalLimit, limit(bound));
            else dynamicLimits.add(dynamicLimit(bound));
        }
        if (!dynamicLimits.isEmpty() && literalLimit != Long.MAX_VALUE)
            dynamicLimits.add(new LongValue(literalLimit));

        select.setWhere(SelectTrees.and(retained));
        Expression fetchValue =
                dynamicLimits.isEmpty()
                        ? new LongValue(Long.toString(literalLimit))
                        : dynamicLimits.size() == 1
                                ? dynamicLimits.get(0)
                                : new Function("LEAST", dynamicLimits.toArray(Expression[]::new));
        select.setFetch(fetch(fetchValue));
        return true;
    }

    private static boolean rewriteAggregateFirstRow(PlainSelect select) {
        if (!SelectTrees.aggregateProjection(select)
                || select.getDistinct() != null
                || select.getGroupBy() != null
                || select.getHaving() != null
                || select.getJoins() != null && !select.getJoins().isEmpty()
                || !(select.getFromItem() instanceof ParenthesedSelect parenthesed)
                || !(parenthesed.getSelect() instanceof PlainSelect source)) return false;
        List<Expression> terms = SelectTrees.andTerms(select.getWhere());
        if (terms.size() != 1) return false;
        Bound bound = parseBound(terms.get(0));
        if (bound == null
                || !bound.operator().equals("=")
                || bound.bind() != null
                || bound.literal().compareTo(BigDecimal.ONE) != 0
                || !hasOrderBy(source)
                || source.getLimit() != null
                || source.getOffset() != null
                || source.getFetch() != null
                || source.getForMode() != null) return false;
        source.setFetch(fetch(new LongValue(1)));
        select.setWhere(null);
        return true;
    }

    private static Fetch fetch(Expression expression) {
        Fetch fetch = new Fetch();
        fetch.setExpression(expression);
        fetch.setFetchParamFirst(true);
        fetch.addFetchParameter("ROWS");
        fetch.addFetchParameter("ONLY");
        return fetch;
    }

    private static boolean hasOrderBy(PlainSelect select) {
        return select.getOrderByElements() != null && !select.getOrderByElements().isEmpty();
    }

    private static Expression dynamicLimit(Bound bound) {
        Function number = new Function("orafit.to_number_bind", bound.bind());
        Expression value =
                switch (bound.operator()) {
                    case "<=" -> new Function("FLOOR", number);
                    case "<" -> new Subtraction(new Function("CEIL", number), new LongValue(1));
                    case "=" ->
                            new CaseExpression(
                                            new LongValue(0),
                                            new WhenClause(new LongValue(1), new LongValue(1)))
                                    .withSwitchExpression(number);
                    default -> throw new IllegalStateException(bound.operator());
                };
        if (!bound.operator().equals("=")) {
            value = new Function("GREATEST", value, new LongValue(0));
            value = new Function("LEAST", value, new LongValue(Long.MAX_VALUE));
        }
        return new CastExpression("CAST", value, "BIGINT");
    }

    private static long limit(Bound bound) {
        BigDecimal rows =
                switch (bound.operator()) {
                    case "<=" -> bound.literal().setScale(0, RoundingMode.FLOOR);
                    case "<" ->
                            bound.literal()
                                    .setScale(0, RoundingMode.CEILING)
                                    .subtract(BigDecimal.ONE);
                    case "=" ->
                            bound.literal().compareTo(BigDecimal.ONE) == 0
                                    ? BigDecimal.ONE
                                    : BigDecimal.ZERO;
                    default -> throw new IllegalStateException(bound.operator());
                };
        if (rows.signum() <= 0) return 0L;
        if (rows.compareTo(LONG_MAX) >= 0) return Long.MAX_VALUE;
        return rows.longValueExact();
    }

    private static Bound parseBound(Expression expression) {
        if (!(expression instanceof BinaryExpression binary)) return null;
        String operator;
        if (expression instanceof MinorThan) operator = "<";
        else if (expression instanceof MinorThanEquals) operator = "<=";
        else if (expression instanceof EqualsTo) operator = "=";
        else if (expression instanceof GreaterThan) operator = ">";
        else if (expression instanceof GreaterThanEquals) operator = ">=";
        else return null;

        boolean left = isRownum(binary.getLeftExpression());
        boolean right = isRownum(binary.getRightExpression());
        if (left == right) return null;
        Expression valueExpression =
                left ? binary.getRightExpression() : binary.getLeftExpression();
        BigDecimal literal = numeric(valueExpression);
        JdbcNamedParameter bind =
                valueExpression instanceof JdbcNamedParameter named
                                && named.getName() != null
                                && named.getName().startsWith("__orafit_b")
                        ? named
                        : null;
        if (literal == null && bind == null) return null;
        return new Bound(left ? operator : reverse(operator), literal, bind);
    }

    private static BigDecimal numeric(Expression expression) {
        if (expression instanceof NullValue) return BigDecimal.ZERO;
        if (!(expression instanceof LongValue
                || expression instanceof DoubleValue
                || expression instanceof SignedExpression)) return null;
        try {
            return new BigDecimal(expression.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void ensureNoRownumOutsideWhere(PlainSelect select) throws TranslationException {
        for (SelectItem<?> item : select.getSelectItems()) {
            if (containsRownum(item.getExpression()))
                throw unsupported(
                        "ROWNUM_CONTEXT", "ROWNUM projection is outside the supported contract");
        }
        if (select.getGroupBy() != null
                && select.getGroupBy().toString().toUpperCase(Locale.ROOT).contains("ROWNUM")) {
            throw unsupported(
                    "ROWNUM_CONTEXT", "ROWNUM in GROUP BY is outside the supported contract");
        }
        if (containsRownum(select.getHaving()))
            throw unsupported(
                    "ROWNUM_CONTEXT", "ROWNUM in HAVING is outside the supported contract");
        if (select.getJoins() != null
                && select.getJoins().toString().toUpperCase(Locale.ROOT).contains("ROWNUM")) {
            throw unsupported(
                    "ROWNUM_CONTEXT",
                    "ROWNUM in JOIN predicates is outside the supported contract");
        }
    }

    private static boolean containsRownum(Expression expression) {
        return ParserAdapter.columns(expression).stream().anyMatch(RownumRule::isRownum);
    }

    private static boolean isRownum(Expression expression) {
        return expression instanceof Column column && isRownum(column);
    }

    private static boolean isRownum(Column column) {
        return (column.getTableName() == null || column.getTableName().isBlank())
                && "ROWNUM".equalsIgnoreCase(column.getUnquotedColumnName());
    }

    private static String reverse(String operator) {
        return switch (operator) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> operator;
        };
    }

    private static TranslationException nestedUnsupported(String message) {
        return new TranslationException("ROWNUM_NESTED_UPPER", message);
    }

    private static TranslationException unsupported(String code, String message) {
        return new TranslationException(code, message);
    }

    record Result(Statement statement, boolean changed) {}

    private record Bound(String operator, BigDecimal literal, JdbcNamedParameter bind) {}
}
