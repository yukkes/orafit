package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Bounded Oracle DECODE lowering and set-operation result type reconciliation. */
final class DecodeLowering {
    private DecodeLowering() {}

    static Set<Function> databaseResolved(Statement statement) {
        Set<Function> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SetOperationList set : ParserAdapter.nodes(statement, SetOperationList.class)) {
            if (set.getSelects() == null || set.getSelects().size() < 2) continue;
            for (int branchIndex = 0; branchIndex < set.getSelects().size(); branchIndex++) {
                if (!(set.getSelects().get(branchIndex) instanceof PlainSelect branch)) continue;
                for (int columnIndex = 0;
                        columnIndex < branch.getSelectItems().size();
                        columnIndex++) {
                    Expression expression =
                            branch.getSelectItems().get(columnIndex).getExpression();
                    if (!(expression instanceof Function function)
                            || !OracleCoercion.unqualified(function, "DECODE")
                            || function.getParameters() == null
                            || function.getParameters().size() < 3
                            || !(function.getParameters().get(2) instanceof Column firstResult)
                            || !databaseSafeResults(function)) continue;
                    for (int siblingIndex = 0;
                            siblingIndex < set.getSelects().size();
                            siblingIndex++) {
                        if (siblingIndex == branchIndex
                                || !(set.getSelects().get(siblingIndex)
                                        instanceof PlainSelect sibling)
                                || sibling.getSelectItems().size() <= columnIndex
                                || !(sibling.getSelectItems().get(columnIndex).getExpression()
                                        instanceof Column siblingColumn)) continue;
                        if (sameSourceColumn(branch, firstResult, sibling, siblingColumn)) {
                            result.add(function);
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    static Expression lower(Function function, boolean databaseResolvedResult)
            throws TranslationException {
        ExpressionList<?> args = function.getParameters();
        int size = args == null ? 0 : args.size();
        if (size < 3 || size > 255) throw unsupported("DECODE requires 3..255 components");
        Expression source = oracleNull(args.get(0));
        if (!decodeSource(source))
            throw unsupported("DECODE source must be a column, JDBC bind, or literal");

        Kind searchKind = literalKind(oracleNull(args.get(1)));
        if (searchKind == Kind.NULL && source instanceof NullValue) searchKind = Kind.TEXT;
        if (searchKind != Kind.TEXT && searchKind != Kind.NUMBER) {
            throw unsupported(
                    "DECODE first search must be a text or numeric literal; a NULL first search is"
                            + " supported only for a literal NULL source");
        }
        Kind resultKind = resultKind(oracleNull(args.get(2)), databaseResolvedResult);
        int pairCount = (size - 1) / 2;
        List<WhenClause> clauses = new ArrayList<>(pairCount);
        Expression comparableSource = convert(source, searchKind);
        for (int pair = 0; pair < pairCount; pair++) {
            Expression search = oracleNull(args.get(1 + pair * 2));
            Expression result = oracleNull(args.get(2 + pair * 2));
            if (!compatible(search, searchKind))
                throw unsupported("DECODE search literals must share the first search type");
            if (pair > 0 && !compatible(result, resultKind))
                throw unsupported("DECODE results must share the first result type");
            IsDistinctExpression match = new IsDistinctExpression();
            match.setNot(true);
            match.setLeftExpression(comparableSource);
            match.setRightExpression(convert(search, searchKind));
            clauses.add(new WhenClause(match, convertResult(result, resultKind)));
        }
        Expression defaultValue = size % 2 == 0 ? oracleNull(args.get(size - 1)) : new NullValue();
        if (!compatible(defaultValue, resultKind))
            throw unsupported("DECODE default must share the result type");
        CaseExpression decoded = new CaseExpression();
        decoded.setWhenClauses(clauses);
        decoded.setElseExpression(convertResult(defaultValue, resultKind));
        return decoded;
    }

    private static boolean databaseSafeResults(Function function) {
        ExpressionList<?> args = function.getParameters();
        for (int i = 4; i < args.size(); i += 2) {
            Expression value = oracleNull(args.get(i));
            if (!(value instanceof NullValue || numericLiteral(value) || value instanceof Column))
                return false;
        }
        if (args.size() % 2 == 0) {
            Expression fallback = oracleNull(args.get(args.size() - 1));
            return fallback instanceof NullValue
                    || numericLiteral(fallback)
                    || fallback instanceof Column;
        }
        return true;
    }

    private static boolean sameSourceColumn(
            PlainSelect left, Column leftColumn, PlainSelect right, Column rightColumn) {
        if (!leftColumn
                .getUnquotedColumnName()
                .equalsIgnoreCase(rightColumn.getUnquotedColumnName())) return false;
        if (!(left.getFromItem() instanceof net.sf.jsqlparser.schema.Table leftTable)
                || !(right.getFromItem() instanceof net.sf.jsqlparser.schema.Table rightTable))
            return false;
        return leftTable.getUnquotedName().equalsIgnoreCase(rightTable.getUnquotedName());
    }

    private static Kind resultKind(Expression firstResult, boolean databaseResolved)
            throws TranslationException {
        Kind kind = literalKind(firstResult);
        if (kind == Kind.NULL || kind == Kind.TEXT) return Kind.TEXT;
        if (kind == Kind.NUMBER || numericArithmetic(firstResult)) return Kind.NUMBER;
        if (databaseResolved && firstResult instanceof Column) return Kind.DATABASE;
        throw unsupported(
                "DECODE first result must be a text, numeric, NULL literal, or bounded numeric"
                        + " arithmetic expression");
    }

    private static boolean compatible(Expression value, Kind expected) {
        Kind kind = literalKind(value);
        if (kind == Kind.NULL) return true;
        if (expected == Kind.NUMBER) return kind == Kind.NUMBER || value instanceof Column;
        if (expected == Kind.DATABASE)
            return kind == Kind.NUMBER || kind == Kind.NULL || value instanceof Column;
        return kind == expected;
    }

    private static boolean numericArithmetic(Expression value) {
        if (value instanceof Function function
                && "orafit.number_value".equalsIgnoreCase(function.getName())
                && function.getParameters() != null
                && function.getParameters().size() == 1) return true;
        if (!(value instanceof BinaryExpression binary)
                || !(binary instanceof Addition
                        || binary instanceof Subtraction
                        || binary instanceof Multiplication
                        || binary instanceof Division)) return false;
        return numericOperand(binary.getLeftExpression())
                && numericOperand(binary.getRightExpression());
    }

    private static boolean numericOperand(Expression value) {
        return value instanceof Column || numericLiteral(value) || numericArithmetic(value);
    }

    private static boolean numericLiteral(Expression value) {
        return value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression;
    }

    private static Kind literalKind(Expression value) {
        if (value instanceof NullValue) return Kind.NULL;
        if (value instanceof StringValue) return Kind.TEXT;
        if (numericLiteral(value)) return Kind.NUMBER;
        return Kind.OTHER;
    }

    private static boolean decodeSource(Expression source) {
        return source instanceof Column
                || source instanceof JdbcNamedParameter
                || literalKind(source) != Kind.OTHER;
    }

    private static Expression convert(Expression value, Kind kind) {
        if (value instanceof NullValue) return value;
        if (kind == Kind.TEXT) return OracleCoercion.text(value);
        return number(value);
    }

    private static Expression convertResult(Expression value, Kind kind) {
        return value instanceof NullValue || kind != Kind.NUMBER ? value : number(value);
    }

    private static Expression number(Expression value) {
        if (literalKind(value) == Kind.NUMBER) return value;
        if (numericArithmetic(value) && value instanceof BinaryExpression binary) {
            binary.setLeftExpression(number(binary.getLeftExpression()));
            binary.setRightExpression(number(binary.getRightExpression()));
            return binary;
        }
        return new Function("orafit.to_number_bind", value);
    }

    private static Expression oracleNull(Expression value) {
        return value instanceof StringValue literal && literal.getValue().isEmpty()
                ? new NullValue()
                : value;
    }

    private static TranslationException unsupported(String message) {
        return new TranslationException("DECODE", message);
    }

    private enum Kind {
        TEXT,
        NUMBER,
        NULL,
        DATABASE,
        OTHER
    }
}
