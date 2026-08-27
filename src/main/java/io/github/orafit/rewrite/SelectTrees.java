package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Explicit query-tree traversal for rules that intentionally operate inside CTEs and views. */
public final class SelectTrees {
    private static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");
    private static final Set<String> AGGREGATES_OR_LISTAGG =
            Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "LISTAGG");

    private SelectTrees() {}

    static ReturningClause returning(Statement statement) {
        if (statement instanceof Insert insert) return insert.getReturningClause();
        if (statement instanceof Update update) return update.getReturningClause();
        if (statement instanceof Delete delete) return delete.getReturningClause();
        return null;
    }

    static boolean aggregateProjection(PlainSelect select) {
        if (select.getSelectItems() == null || select.getSelectItems().isEmpty()) return false;
        for (SelectItem<?> item : select.getSelectItems()) {
            if (!(item.getExpression() instanceof Function function)
                    || !AGGREGATES.contains(aggregateName(function))) return false;
        }
        return true;
    }

    public static boolean containsAggregateOrWindow(PlainSelect select) {
        final boolean[] found = {false};
        ExpressionVisitorAdapter<Void> visitor =
                new ExpressionVisitorAdapter<>() {
                    @Override
                    public <S> Void visit(Function function, S context) {
                        if (AGGREGATES_OR_LISTAGG.contains(aggregateName(function)))
                            found[0] = true;
                        return super.visit(function, context);
                    }

                    @Override
                    public <S> Void visit(AnalyticExpression expression, S context) {
                        found[0] = true;
                        return super.visit(expression, context);
                    }
                };
        for (SelectItem<?> item : select.getSelectItems())
            item.getExpression().accept(visitor, null);
        return found[0];
    }

    private static String aggregateName(Function function) {
        return function.getName() == null ? "" : function.getName().toUpperCase(Locale.ROOT);
    }

    static List<Expression> andTerms(Expression expression) {
        List<Expression> result = new ArrayList<>();
        flattenAnd(expression, result);
        return result;
    }

    private static void flattenAnd(Expression expression, List<Expression> result) {
        if (expression instanceof AndExpression and) {
            flattenAnd(and.getLeftExpression(), result);
            flattenAnd(and.getRightExpression(), result);
        } else if (expression != null) result.add(expression);
    }

    static Expression and(List<Expression> expressions) {
        if (expressions.isEmpty()) return null;
        Expression result = expressions.get(0);
        for (int i = 1; i < expressions.size(); i++)
            result = new AndExpression(result, expressions.get(i));
        return result;
    }

    static List<PlainSelect> plain(Statement statement) {
        List<PlainSelect> result =
                new ArrayList<>(ParserAdapter.nodes(statement, PlainSelect.class));
        walk(
                statement,
                plain -> {
                    if (!result.contains(plain)) result.add(plain);
                },
                set -> {});
        return result;
    }

    static List<SetOperationList> sets(Statement statement) {
        List<SetOperationList> result =
                new ArrayList<>(ParserAdapter.nodes(statement, SetOperationList.class));
        walk(
                statement,
                plain -> {},
                set -> {
                    if (!result.contains(set)) result.add(set);
                });
        return result;
    }

    private static void walk(
            Statement statement, Consumer<PlainSelect> plains, Consumer<SetOperationList> sets) {
        if (statement instanceof Select select)
            walk(select, Collections.newSetFromMap(new IdentityHashMap<>()), plains, sets);
    }

    private static void walk(
            Select select,
            Set<Select> seen,
            Consumer<PlainSelect> plains,
            Consumer<SetOperationList> sets) {
        if (select == null || !seen.add(select)) return;
        if (select.getWithItemsList() != null)
            for (WithItem<?> item : select.getWithItemsList())
                if (item.getSelect() != null) walk(item.getSelect(), seen, plains, sets);
        if (select instanceof ParenthesedSelect nested) {
            walk(nested.getSelect(), seen, plains, sets);
        } else if (select instanceof SetOperationList set && set.getSelects() != null) {
            sets.accept(set);
            for (Select branch : set.getSelects()) walk(branch, seen, plains, sets);
        } else if (select instanceof PlainSelect plain) {
            plains.accept(plain);
            walk(plain.getFromItem(), seen, plains, sets);
            if (plain.getJoins() != null)
                for (Join join : plain.getJoins()) walk(join.getFromItem(), seen, plains, sets);
        }
    }

    private static void walk(
            FromItem item,
            Set<Select> seen,
            Consumer<PlainSelect> plains,
            Consumer<SetOperationList> sets) {
        if (item instanceof ParenthesedSelect nested) walk(nested.getSelect(), seen, plains, sets);
    }
}
