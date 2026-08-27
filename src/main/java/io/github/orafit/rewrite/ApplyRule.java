package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.TableFunction;

import java.util.List;
import java.util.Locale;

/** Lowers the maintainable APPLY subset directly on the already-parsed AST. */
final class ApplyRule {
    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        int alias = 1;
        String rendered = statement.toString();
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            if (select.getJoins() == null) continue;
            for (Join join : select.getJoins()) {
                if (!join.isApply()) continue;
                FromItem right = join.getFromItem();
                if (right instanceof ParenthesedSelect subquery) {
                    Alias existing = subquery.getAlias();
                    if (existing == null)
                        existing = new Alias(uniqueAlias(rendered, alias++), false);
                    join.setFromItem(
                            new LateralSubSelect("LATERAL", subquery.getSelect(), existing));
                } else if (right instanceof TableFunction tableFunction) {
                    unwrapDirectTableFunction(tableFunction);
                } else if (!(right instanceof Table)) {
                    throw new TranslationException(
                            "APPLY_SOURCE",
                            "the bounded APPLY contract supports a table, inline SELECT, or direct"
                                    + " TABLE(function) source");
                }

                boolean outer = join.isOuter();
                join.setApply(false);
                join.setOuter(false);
                join.setCross(!outer);
                join.setLeft(outer);
                if (outer) join.setOnExpressions(List.of(trueExpression()));
                changed = true;
            }
        }
        return changed;
    }

    private static void unwrapDirectTableFunction(TableFunction tableFunction)
            throws TranslationException {
        Function wrapper = tableFunction.getFunction();
        if (wrapper == null
                || wrapper.getName() == null
                || !wrapper.getName().equalsIgnoreCase("TABLE")
                || wrapper.getParameters() == null
                || wrapper.getParameters().size() != 1
                || !(wrapper.getParameters().get(0) instanceof Function function)) {
            throw new TranslationException(
                    "APPLY_SOURCE",
                    "TABLE(...) APPLY supports one direct set-returning function call");
        }
        tableFunction.setFunction(function);
    }

    private static EqualsTo trueExpression() {
        EqualsTo expression = new EqualsTo();
        expression.setLeftExpression(new LongValue(1));
        expression.setRightExpression(new LongValue(1));
        return expression;
    }

    private static String uniqueAlias(String sql, int start) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int index = start;
        String alias;
        do alias = "__orafit_apply_" + index++;
        while (lower.contains(alias.toLowerCase(Locale.ROOT)));
        return alias;
    }
}
