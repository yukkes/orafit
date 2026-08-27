package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnalyticType;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WindowElement;
import net.sf.jsqlparser.expression.WindowOffset;
import net.sf.jsqlparser.expression.WindowRange;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Lowers deterministic Oracle LISTAGG forms directly on the existing AST. */
final class ListaggRule {
    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        boolean seen = false;
        Set<AnalyticExpression> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            List<SelectItem<?>> items = select.getSelectItems();
            if (items == null) continue;
            for (int i = 0; i < items.size(); i++) {
                SelectItem<?> item = items.get(i);
                if (!(item.getExpression() instanceof AnalyticExpression listagg)
                        || !named(listagg, "LISTAGG")) continue;
                if (listagg.getType() == AnalyticType.WITHIN_GROUP_OVER) {
                    seen = true;
                    validateAnalytic(listagg);
                    items.set(i, SelectItem.from(analytic(listagg), item.getAlias()));
                    replaced.add(listagg);
                    changed = true;
                    continue;
                }
                if (!truncate(listagg.getOnOverflowTruncate())) continue;
                seen = true;
                validate(listagg);
                Overflow overflow = overflow(listagg);
                Function values = new Function("pg_catalog.array_agg", value(listagg));
                values.setOrderByElements(listagg.getOrderByElements());
                items.set(
                        i,
                        SelectItem.from(
                                new Function(
                                        "orafit.listagg_truncate",
                                        values,
                                        separator(listagg.getOffset()),
                                        new StringValue(overflow.indicator()),
                                        new BooleanValue(overflow.withCount()),
                                        new LongValue(4000)),
                                item.getAlias()));
                replaced.add(listagg);
                changed = true;
            }
        }
        for (AnalyticExpression listagg :
                ParserAdapter.nodes(statement, AnalyticExpression.class)) {
            if (replaced.contains(listagg) || !named(listagg, "LISTAGG")) continue;
            seen = true;
            validate(listagg);
            String overflow = listagg.getOnOverflowTruncate();
            if (truncate(overflow))
                throw unsupported(
                        "LISTAGG_OVERFLOW",
                        "LISTAGG TRUNCATE is supported only as a direct SELECT item");
            if (overflow != null && !overflow.isBlank() && !overflow.equalsIgnoreCase("ERROR")) {
                throw unsupported("LISTAGG_OVERFLOW", "unsupported LISTAGG overflow clause");
            }
            Function aggregate =
                    new Function(
                            "pg_catalog.string_agg",
                            value(listagg),
                            separator(listagg.getOffset()));
            aggregate.setOrderByElements(listagg.getOrderByElements());
            listagg.setName("orafit.listagg_check");
            listagg.setExpression(aggregate);
            listagg.setOffset(new LongValue(4000));
            listagg.setDefaultValue(null);
            listagg.setType(AnalyticType.FILTER_ONLY);
            listagg.setDistinct(false);
            listagg.setOnOverflowTruncate(null);
            changed = true;
        }
        if (!seen)
            for (Function function : ParserAdapter.nodes(statement, Function.class)) {
                if (OracleCoercion.unqualified(function, "LISTAGG"))
                    throw unsupported(
                            function.isDistinct() ? "LISTAGG_DISTINCT_ORDER" : "LISTAGG",
                            "LISTAGG requires WITHIN GROUP ORDER BY");
            }
        return changed;
    }

    private static void validate(AnalyticExpression listagg) throws TranslationException {
        if (listagg.getType() != AnalyticType.WITHIN_GROUP
                || listagg.getOrderByElements() == null
                || listagg.getOrderByElements().isEmpty()) {
            throw unsupported("LISTAGG", "LISTAGG core requires WITHIN GROUP ORDER BY");
        }
        validateArguments(listagg);
    }

    private static void validateAnalytic(AnalyticExpression listagg) throws TranslationException {
        if (listagg.getOrderByElements() == null || listagg.getOrderByElements().isEmpty()) {
            throw unsupported("LISTAGG", "analytic LISTAGG requires WITHIN GROUP ORDER BY");
        }
        if (listagg.getPartitionExpressionList() != null
                && !listagg.getPartitionExpressionList().isEmpty()) {
            throw unsupported(
                    "LISTAGG", "analytic LISTAGG is initially limited to the full query partition");
        }
        if (listagg.getWindowName() != null || listagg.getWindowElement() != null) {
            throw unsupported(
                    "LISTAGG", "analytic LISTAGG named windows and explicit frames are not owned");
        }
        String overflow = listagg.getOnOverflowTruncate();
        if (overflow != null && !overflow.isBlank() && !overflow.equalsIgnoreCase("ERROR")) {
            throw unsupported(
                    "LISTAGG_OVERFLOW", "analytic LISTAGG supports only the default overflow mode");
        }
        validateArguments(listagg);
    }

    private static void validateArguments(AnalyticExpression listagg) throws TranslationException {
        if (listagg.isDistinct())
            throw unsupported(
                    "LISTAGG_DISTINCT_ORDER",
                    "LISTAGG DISTINCT is not implemented for the Oracle 18c target");
        Expression delimiter = listagg.getOffset();
        if (delimiter != null && !constantDelimiter(delimiter))
            throw unsupported(
                    "LISTAGG_CONSTANT",
                    "LISTAGG delimiter must be a string literal, NULL, or JDBC bind");
    }

    private static Function analytic(AnalyticExpression listagg) {
        AnalyticExpression aggregate = new AnalyticExpression();
        aggregate.setName("pg_catalog.string_agg");
        aggregate.setExpression(value(listagg));
        aggregate.setOffset(separator(listagg.getOffset()));
        aggregate.setType(AnalyticType.OVER);
        aggregate.setOrderByElements(List.copyOf(listagg.getOrderByElements()));
        aggregate.setWindowElement(
                new WindowElement()
                        .withType(WindowElement.Type.ROWS)
                        .withRange(
                                new WindowRange()
                                        .withStart(
                                                new WindowOffset()
                                                        .withType(WindowOffset.Type.PRECEDING))
                                        .withEnd(
                                                new WindowOffset()
                                                        .withType(WindowOffset.Type.FOLLOWING))));
        return new Function("orafit.listagg_check", aggregate, new LongValue(4000));
    }

    private static Overflow overflow(AnalyticExpression listagg) throws TranslationException {
        String rest = listagg.getOnOverflowTruncate().trim().substring("TRUNCATE".length()).trim();
        String indicator = "...";
        if (rest.startsWith("'")) {
            StringBuilder value = new StringBuilder();
            int i = 1;
            for (; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c != '\'') {
                    value.append(c);
                    continue;
                }
                if (i + 1 < rest.length() && rest.charAt(i + 1) == '\'') {
                    value.append('\'');
                    i++;
                    continue;
                }
                break;
            }
            if (i >= rest.length())
                throw unsupported("LISTAGG_OVERFLOW", "unterminated LISTAGG truncation indicator");
            indicator = value.toString();
            rest = rest.substring(i + 1).trim();
        }
        if (rest.isEmpty() || rest.equalsIgnoreCase("WITH COUNT"))
            return new Overflow(indicator, true);
        if (rest.equalsIgnoreCase("WITHOUT COUNT")) return new Overflow(indicator, false);
        throw unsupported("LISTAGG_OVERFLOW", "unsupported LISTAGG TRUNCATE suffix: " + rest);
    }

    private static Function value(AnalyticExpression listagg) {
        return new Function("NULLIF", text(listagg.getExpression()), new StringValue(""));
    }

    private static Expression separator(Expression delimiter) {
        return delimiter == null || delimiter instanceof NullValue
                ? new StringValue("")
                : delimiter instanceof StringValue
                        ? delimiter
                        : new Function("COALESCE", text(delimiter), new StringValue(""));
    }

    private static Expression text(Expression value) {
        if (value instanceof StringValue || value instanceof NullValue) return value;
        return new Function(
                "orafit.to_varchar2",
                value,
                new StringValue("YYYY-MM-DD"),
                new StringValue("YYYY-MM-DD HH24:MI:SS"),
                new StringValue("YYYY-MM-DD HH24:MI:SS TZH:TZM"),
                new StringValue(".,"));
    }

    private static boolean constantDelimiter(Expression value) {
        return value instanceof StringValue
                || value instanceof NullValue
                || value instanceof JdbcNamedParameter;
    }

    private static boolean truncate(String value) {
        return value != null && value.stripLeading().regionMatches(true, 0, "TRUNCATE", 0, 8);
    }

    private static boolean named(AnalyticExpression expression, String name) {
        return expression.getName() != null && expression.getName().equalsIgnoreCase(name);
    }

    private static TranslationException unsupported(String code, String message) {
        return new TranslationException(code, message);
    }

    private record Overflow(String indicator, boolean withCount) {}
}
