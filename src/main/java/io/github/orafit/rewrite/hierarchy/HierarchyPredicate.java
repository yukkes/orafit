package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.ConnectByPriorOperator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.SupportsOldOracleJoinSyntax;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

/** Immutable bounded CONNECT BY predicate that can be rendered for any parent/child aliases. */
final class HierarchyPredicate {
    private final SourceExpression left;
    private final SourceExpression right;
    private final int priorSide;

    private HierarchyPredicate(SourceExpression left, SourceExpression right, int priorSide) {
        this.left = left;
        this.right = right;
        this.priorSide = priorSide;
    }

    static HierarchyPredicate resolve(Expression expression, Table source, String aliasName)
            throws TranslationException {
        if (!(expression instanceof EqualsTo equals)) {
            throw unsupported("CONNECT BY requires one PRIOR equality");
        }
        int priorSide = priorSide(equals);
        if (priorSide == 0) throw unsupported("CONNECT BY requires exactly one PRIOR operand");

        Expression left = unwrapPrior(equals.getLeftExpression());
        Expression right = unwrapPrior(equals.getRightExpression());
        return new HierarchyPredicate(
                SourceExpression.resolve(left, source, aliasName),
                SourceExpression.resolve(right, source, aliasName),
                priorSide);
    }

    Expression render(Table child, Table parent) {
        return new EqualsTo(
                left.render(priorSide == 1 ? parent : child),
                right.render(priorSide == 2 ? parent : child));
    }

    String childLinkColumn() {
        SourceExpression child = priorSide == 1 ? right : left;
        return child instanceof SourceColumn column ? column.name() : null;
    }

    private static Expression unwrapPrior(Expression expression) {
        return expression instanceof ConnectByPriorOperator prior
                ? prior.getExpression()
                : expression;
    }

    private static int priorSide(EqualsTo equals) {
        boolean left = equals.getLeftExpression() instanceof ConnectByPriorOperator;
        boolean right = equals.getRightExpression() instanceof ConnectByPriorOperator;
        if (left == right) {
            int old = equals.getOraclePriorPosition();
            if (old == SupportsOldOracleJoinSyntax.ORACLE_PRIOR_START) return 1;
            if (old == SupportsOldOracleJoinSyntax.ORACLE_PRIOR_END) return 2;
            return 0;
        }
        return left ? 1 : 2;
    }

    private sealed interface SourceExpression
            permits SourceColumn, SourceLong, SourceAddition, SourceAbs {
        Expression render(Table table);

        static SourceExpression resolve(Expression expression, Table source, String aliasName)
                throws TranslationException {
            if (expression instanceof ParenthesedExpressionList<?> parenthesized) {
                if (parenthesized.size() != 1) {
                    throw unsupported("PRIOR parenthesized expression must contain one value");
                }
                return resolve(parenthesized.get(0), source, aliasName);
            }
            if (expression instanceof Column column) {
                String invalid = HierarchySupport.invalidQualifier(column, source, aliasName);
                if (invalid != null) {
                    throw unsupported("PRIOR expression references outside source: " + invalid);
                }
                return new SourceColumn(column.getColumnName());
            }
            if (expression instanceof LongValue value)
                return new SourceLong(value.getStringValue());
            if (expression instanceof Addition addition) {
                return new SourceAddition(
                        resolve(addition.getLeftExpression(), source, aliasName),
                        resolve(addition.getRightExpression(), source, aliasName));
            }
            if (expression instanceof Function function
                    && function.getName() != null
                    && function.getName().equalsIgnoreCase("ABS")
                    && function.getParameters() != null
                    && function.getParameters().size() == 1) {
                return new SourceAbs(resolve(function.getParameters().get(0), source, aliasName));
            }
            throw unsupported(
                    "PRIOR equality expression is outside the bounded Column/+ literal/ABS subset: "
                            + expression.getClass().getSimpleName());
        }
    }

    private record SourceColumn(String name) implements SourceExpression {
        @Override
        public Expression render(Table table) {
            return new Column(new Table(table.getName()), name);
        }
    }

    private record SourceLong(String value) implements SourceExpression {
        @Override
        public Expression render(Table table) {
            return new LongValue(value);
        }
    }

    private record SourceAddition(SourceExpression left, SourceExpression right)
            implements SourceExpression {
        @Override
        public Expression render(Table table) {
            return new Addition(left.render(table), right.render(table));
        }
    }

    private record SourceAbs(SourceExpression value) implements SourceExpression {
        @Override
        public Expression render(Table table) {
            return new Function("ABS", value.render(table));
        }
    }

    private static TranslationException unsupported(String message) {
        return HierarchySupport.fail("CONNECT_BY_PRIOR", message);
    }
}
