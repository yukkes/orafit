package io.github.orafit.metadata;

import io.github.orafit.translation.ResultMetadataPlan.Column;
import io.github.orafit.translation.ResultMetadataPlan.Kind;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.ConnectByRootOperator;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;

import java.util.List;
import java.util.Locale;

/** Conservative Oracle type, precision, scale, and width inference for SELECT expressions. */
final class ExpressionMetadata {
    private static final String BIND = "__orafit_b";

    private ExpressionMetadata() {}

    static Column type(Expression expression) {
        if (expression instanceof StringValue value && !value.getValue().isEmpty())
            return meta(Kind.CHAR, literalWidth(value), 0);
        if (expression instanceof DateTimeLiteralExpression value) {
            if (value.getType() == DateTimeLiteralExpression.DateTime.DATE)
                return meta(Kind.DATE, 7, 0);
            if (value.getType() == DateTimeLiteralExpression.DateTime.TIMESTAMP)
                return meta(Kind.TIMESTAMP, 0, 9);
        }
        if (expression instanceof CastExpression cast && cast.getColDataType() != null) {
            String name =
                    cast.getColDataType().getDataType().stripLeading().toUpperCase(Locale.ROOT);
            if (name.startsWith("NUMBER")) return meta(Kind.NUMBER, null, null);
            if (name.startsWith("DATE")) return meta(Kind.DATE, 7, 0);
            if (name.startsWith("TIMESTAMP")) return meta(Kind.TIMESTAMP, 0, 9);
            if (name.startsWith("CHAR")) return meta(Kind.CHAR, castWidth(cast), 0);
            if (name.startsWith("VARCHAR")) return meta(Kind.VARCHAR2, castWidth(cast), 0);
        }
        if (expression instanceof AnalyticExpression analytic
                && "LISTAGG".equalsIgnoreCase(analytic.getName()))
            return meta(Kind.VARCHAR2, 4000, 0);
        if (expression instanceof Concat concat) return concat(concat);
        if (expression instanceof CaseExpression value && numericCase(value)) return number(-127);
        if (dateAddition(expression)) return meta(Kind.DATE, 7, 0);
        if (expression instanceof Addition
                || expression instanceof Subtraction
                || expression instanceof Multiplication
                || expression instanceof Division) return number(-127);
        if (expression instanceof LongValue
                || expression instanceof DoubleValue
                || expression instanceof SignedExpression) return number(-127);
        if (expression instanceof ConnectByRootOperator root) {
            Expression value = root.getExpression();
            if (value
                            instanceof
                            net.sf.jsqlparser.expression.operators.relational
                                                    .ParenthesedExpressionList<
                                            ?>
                                    p
                    && p.size() == 1) value = p.get(0);
            if (value instanceof Addition) return number(0);
            return value instanceof net.sf.jsqlparser.schema.Column
                    ? meta(Kind.AUTO, 0, 0)
                    : type(value);
        }
        if (expression instanceof TrimFunction trim) {
            return meta(Kind.VARCHAR2, trimWidth(trim), 0);
        }
        if (expression instanceof net.sf.jsqlparser.schema.Column column) {
            String name = column.getUnquotedColumnName().toUpperCase(Locale.ROOT);
            boolean unqualified = column.getTableName() == null || column.getTableName().isBlank();
            if (unqualified && name.equals("SYSDATE")) return meta(Kind.DATE, 7, 0);
            if (unqualified && name.equals("SYSTIMESTAMP")) return meta(Kind.TIMESTAMP, 0, 9);
            if (name.equals("ROWNUM")) return number(0);
            if (List.of("NEXTVAL", "CURRVAL", "LEVEL", "CONNECT_BY_ISLEAF").contains(name))
                return number(0);
            return auto();
        }
        if (!(expression instanceof Function function) || function.getName() == null) return auto();

        String name = function.getName().toUpperCase(Locale.ROOT);
        if (name.equals("MOD")
                && function.getParameters() != null
                && function.getParameters().stream()
                        .map(v -> v instanceof SignedExpression signed ? signed.getExpression() : v)
                        .allMatch(
                                v ->
                                        v instanceof LongValue
                                                || v instanceof DoubleValue
                                                || v instanceof StringValue)) return number(-127);
        if (List.of("TO_DATE", "LAST_DAY", "ADD_MONTHS").contains(name))
            return meta(Kind.DATE, 7, 0);
        if (name.equals("TO_TIMESTAMP")) return meta(Kind.TIMESTAMP, 0, 9);
        if (name.equals("LISTAGG") || name.equals("SYS_CONNECT_BY_PATH"))
            return meta(Kind.VARCHAR2, 4000, 0);
        if (name.equals("SUBSTR")) return meta(Kind.VARCHAR2, substrWidth(function), 0);
        if (name.equals("LPAD") || name.equals("RPAD"))
            return meta(Kind.VARCHAR2, padWidth(function), 0);
        if (name.equals("LTRIM") || name.equals("RTRIM"))
            return meta(Kind.VARCHAR2, trimWidth(function, name.equals("LTRIM")), 0);
        if (name.equals("CONCAT") && function.getParameters() != null) {
            return function.getParameters().size() == 2
                    ? concat(function.getParameters().get(0), function.getParameters().get(1))
                    : meta(Kind.VARCHAR2, null, 0);
        }
        if (name.equals("TO_CHAR")) return meta(Kind.VARCHAR2, toCharWidth(function), 0);
        if (name.equals("NVL2")) return meta(Kind.VARCHAR2, nvl2Width(function), 0);
        if (name.equals("DECODE")) return decode(function);
        if (name.equals("NVL")) return meta(Kind.VARCHAR2, maxStringWidth(function), 0);
        if (name.equals("REGEXP_SUBSTR"))
            return meta(Kind.VARCHAR2, regexpSubstrWidth(function), 0);
        if (name.equals("REGEXP_REPLACE"))
            return meta(Kind.VARCHAR2, regexpReplaceWidth(function), 0);
        if (name.equals("TRUNC") || name.equals("ROUND")) {
            Column first = first(function);
            return first.kind() == Kind.DATE || first.kind() == Kind.TIMESTAMP
                    ? meta(Kind.DATE, 7, 0)
                    : number(-127);
        }
        if (List.of(
                        "TO_NUMBER",
                        "MONTHS_BETWEEN",
                        "INSTR",
                        "REGEXP_COUNT",
                        "REGEXP_INSTR",
                        "GREATEST",
                        "LEAST")
                .contains(name)) return number(hasBindOrNull(function) ? 0 : -127);
        return auto();
    }

    private static Column concat(Concat expression) {
        return concat(expression.getLeftExpression(), expression.getRightExpression());
    }

    private static Column concat(Expression left, Expression right) {
        Integer fixed = sum(width(left), width(right));
        if (fixed != null) return meta(Kind.VARCHAR2, fixed, 0);
        JdbcNamedParameter bind =
                left instanceof JdbcNamedParameter leftBind
                        ? leftBind
                        : right instanceof JdbcNamedParameter rightBind ? rightBind : null;
        Expression other = bind == left ? right : left;
        Integer index = bind == null ? null : bindIndex(bind);
        Integer otherWidth = bind == null ? null : width(other);
        return index != null && otherWidth != null
                ? new Column(null, Kind.VARCHAR2, otherWidth, 0, index)
                : meta(Kind.VARCHAR2, null, 0);
    }

    private static Integer bindIndex(JdbcNamedParameter bind) {
        String name = bind.getName();
        if (name == null || !name.startsWith(BIND)) return null;
        try {
            return Integer.valueOf(name.substring(BIND.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Column first(Function function) {
        return function.getParameters() == null || function.getParameters().isEmpty()
                ? auto()
                : type(function.getParameters().get(0));
    }

    private static Expression firstExpression(Function function) {
        return function.getParameters() == null || function.getParameters().isEmpty()
                ? null
                : function.getParameters().get(0);
    }

    private static Integer padWidth(Function function) {
        Long width =
                function.getParameters() == null || function.getParameters().size() < 2
                        ? null
                        : integer(function.getParameters().get(1));
        return width == null ? 4000 : Math.toIntExact(Math.max(0, width));
    }

    private static Integer trimWidth(TrimFunction trim) {
        Expression source =
                trim.getFromExpression() == null ? trim.getExpression() : trim.getFromExpression();
        String value = text(source);
        if (value == null) return width(source);
        String characters = text(trim.getFromExpression() == null ? null : trim.getExpression());
        char character = characters == null || characters.isEmpty() ? ' ' : characters.charAt(0);
        int start = 0, end = value.length();
        String mode =
                trim.getTrimSpecification() == null ? "BOTH" : trim.getTrimSpecification().name();
        if (!mode.equals("TRAILING"))
            while (start < end && value.charAt(start) == character) start++;
        if (!mode.equals("LEADING"))
            while (end > start && value.charAt(end - 1) == character) end--;
        return end - start;
    }

    private static Integer trimWidth(Function function, boolean leading) {
        String value = text(firstExpression(function));
        if (value == null) return width(firstExpression(function));
        String characters =
                function.getParameters().size() < 2 ? " " : text(function.getParameters().get(1));
        if (characters == null) return value.length();
        int start = 0, end = value.length();
        if (leading) while (start < end && characters.indexOf(value.charAt(start)) >= 0) start++;
        else while (end > start && characters.indexOf(value.charAt(end - 1)) >= 0) end--;
        return end - start;
    }

    private static String text(Expression expression) {
        if (expression instanceof StringValue value) return value.getValue();
        if (expression instanceof LongValue value) return Long.toString(value.getValue());
        return null;
    }

    private static boolean dateAddition(Expression expression) {
        return expression instanceof Addition addition
                && type(addition.getLeftExpression()).kind() == Kind.DATE
                && width(addition.getRightExpression()) != null;
    }

    private static boolean numericCase(CaseExpression value) {
        if (value.getWhenClauses() == null || value.getWhenClauses().isEmpty()) return false;
        for (WhenClause clause : value.getWhenClauses())
            if (type(clause.getThenExpression()).kind() != Kind.NUMBER) return false;
        return value.getElseExpression() == null
                || type(value.getElseExpression()).kind() == Kind.NUMBER;
    }

    private static boolean hasBindOrNull(Function function) {
        if (function.getParameters() == null) return false;
        for (Expression value : function.getParameters())
            if (value instanceof JdbcNamedParameter || value instanceof NullValue) return true;
        return false;
    }

    private static Integer substrWidth(Function function) {
        if (function.getParameters() == null || function.getParameters().isEmpty()) return null;
        Integer source = width(function.getParameters().get(0));
        if (source == null) return null;
        Long start =
                integer(
                        function.getParameters().size() > 1
                                ? function.getParameters().get(1)
                                : null);
        int available = source;
        if (start != null) {
            long normalized = start == 0 ? 1 : start;
            available =
                    normalized > 0
                            ? (int) Math.max(0, source - normalized + 1)
                            : (int) Math.min(source, -normalized);
        }
        if (function.getParameters().size() < 3) return available;
        Long length = integer(function.getParameters().get(2));
        return length == null ? available : length <= 0 ? 0 : (int) Math.min(available, length);
    }

    private static Integer nvl2Width(Function function) {
        if (function.getParameters() == null || function.getParameters().size() != 3)
            return maxStringWidth(function);
        Expression probe = function.getParameters().get(0);
        return oracleNull(probe)
                ? width(function.getParameters().get(2))
                : literal(probe)
                        ? width(function.getParameters().get(1))
                        : maxStringWidth(function);
    }

    private static Column decode(Function function) {
        if (function.getParameters() != null && function.getParameters().size() >= 3) {
            Column firstResult = type(function.getParameters().get(2));
            if (firstResult.kind() == Kind.NUMBER) return number(-127);
        }
        return meta(Kind.VARCHAR2, decodeWidth(function), 0);
    }

    private static Integer decodeWidth(Function function) {
        if (function.getParameters() == null
                || function.getParameters().size() < 3
                || !literal(function.getParameters().get(0))) return maxStringWidth(function);
        Expression source = function.getParameters().get(0);
        for (int i = 1; i + 1 < function.getParameters().size(); i += 2)
            if (sameLiteral(source, function.getParameters().get(i)))
                return width(function.getParameters().get(i + 1));
        return function.getParameters().size() % 2 == 0
                ? width(function.getParameters().get(function.getParameters().size() - 1))
                : 0;
    }

    private static Integer regexpSubstrWidth(Function function) {
        if (function.getParameters() == null
                || function.getParameters().isEmpty()
                || oracleNull(function.getParameters().get(0))) return 0;
        var parameters = function.getParameters();
        if (parameters.size() < 2
                || parameters.size() > 4
                || !(parameters.get(0) instanceof StringValue source)
                || !(parameters.get(1) instanceof StringValue pattern)) return null;
        Long start = parameters.size() > 2 ? integer(parameters.get(2)) : 1L;
        Long occurrence = parameters.size() > 3 ? integer(parameters.get(3)) : 1L;
        return start == null || occurrence == null
                ? null
                : LiteralMetadataInference.regexpSubstr(
                        source.getValue(), pattern.getValue(), start, occurrence);
    }

    private static Integer regexpReplaceWidth(Function function) {
        var parameters = function.getParameters();
        if (parameters == null
                || parameters.size() != 3
                || !(parameters.get(0) instanceof StringValue source)
                || !(parameters.get(1) instanceof StringValue pattern)
                || !(parameters.get(2) instanceof StringValue replacement)) return null;
        return LiteralMetadataInference.regexpReplace(
                source.getValue(), pattern.getValue(), replacement.getValue());
    }

    private static Integer toCharWidth(Function function) {
        if (function.getParameters() == null || function.getParameters().isEmpty()) return null;
        Expression value = function.getParameters().get(0);
        if (function.getParameters().size() == 2
                && function.getParameters().get(1) instanceof StringValue format) {
            Integer numericWidth = numericToCharFormatWidth(format.getValue());
            if (numericWidth != null) return numericWidth;
            Integer dateWidth = dateToCharFormatWidth(format.getValue());
            if (dateWidth != null) return dateWidth;
        }
        if (value instanceof JdbcNamedParameter) return 40;
        Column type = type(value);
        if (type.kind() == Kind.DATE) return 10;
        Integer fixed = width(value);
        if (fixed != null) return fixed;
        if (type.kind() == Kind.NUMBER) return 40;
        return null;
    }

    private static Integer numericToCharFormatWidth(String format) {
        if (format == null || format.isEmpty()) return null;
        boolean digit = false;
        boolean dot = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '9' || c == '0') {
                digit = true;
            } else if (c == '.' && !dot && i > 0 && i + 1 < format.length()) {
                dot = true;
            } else {
                return null;
            }
        }
        return digit ? format.length() + 1 : null;
    }

    private static Integer dateToCharFormatWidth(String format) {
        if (format == null || format.isEmpty()) return null;
        String upper = format.toUpperCase(Locale.ROOT);
        int i = upper.startsWith("FX") ? 2 : 0;
        int width = 0;
        boolean token = false;
        while (i < upper.length()) {
            if (!Character.isLetter(upper.charAt(i))) {
                i++;
                width++;
                continue;
            }
            String[] tokens = {"HH24", "HH12", "YYYY", "TZH", "TZM", "SS", "MI", "MM", "DD", "YY"};
            int[] widths = {2, 2, 4, 3, 2, 2, 2, 2, 2, 2};
            boolean found = false;
            for (int t = 0; t < tokens.length; t++) {
                if (upper.startsWith(tokens[t], i)) {
                    i += tokens[t].length();
                    width += widths[t];
                    token = found = true;
                    break;
                }
            }
            if (!found) {
                if (upper.startsWith("FF", i)
                        && i + 2 < upper.length()
                        && upper.charAt(i + 2) >= '1'
                        && upper.charAt(i + 2) <= '9') {
                    width += upper.charAt(i + 2) - '0';
                    i += 3;
                    token = true;
                    continue;
                }
                return null;
            }
        }
        return token ? width : null;
    }

    private static Integer maxStringWidth(Function function) {
        if (function.getParameters() == null) return null;
        int max = 0;
        boolean found = false;
        for (Expression value : function.getParameters())
            if (value instanceof StringValue string) {
                max = Math.max(max, literalWidth(string));
                found = true;
            }
        return found ? max : null;
    }

    private static Integer width(Expression value) {
        if (value instanceof NullValue) return 0;
        if (value instanceof StringValue string) return literalWidth(string);
        if (value instanceof LongValue || value instanceof DoubleValue)
            return value.toString().length();
        if (value instanceof SignedExpression signed) {
            Integer number = width(signed.getExpression());
            return number == null ? null : number + 1;
        }
        return null;
    }

    private static int literalWidth(StringValue value) {
        return value.getValue().replace("''", "'").length();
    }

    private static Integer castWidth(CastExpression cast) {
        List<String> arguments = cast.getColDataType().getArgumentsStringList();
        String width = arguments != null && arguments.size() == 1 ? arguments.get(0) : null;
        if (width == null) {
            String rendered = cast.getColDataType().toString();
            int open = rendered.indexOf('(');
            int close = rendered.indexOf(')', open + 1);
            if (open >= 0 && close > open) width = rendered.substring(open + 1, close).strip();
        }
        if (width == null) return null;
        try {
            return Integer.valueOf(width);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long integer(Expression expression) {
        if (expression instanceof LongValue value) return value.getValue();
        if (expression instanceof SignedExpression signed
                && signed.getExpression() instanceof LongValue value)
            return signed.getSign() == '-' ? -value.getValue() : value.getValue();
        return null;
    }

    private static boolean oracleNull(Expression expression) {
        return expression instanceof NullValue
                || expression instanceof StringValue string && string.getValue().isEmpty();
    }

    private static boolean literal(Expression expression) {
        return oracleNull(expression)
                || expression instanceof StringValue
                || expression instanceof LongValue
                || expression instanceof DoubleValue
                || expression instanceof SignedExpression;
    }

    private static boolean sameLiteral(Expression left, Expression right) {
        return oracleNull(left) && oracleNull(right)
                || !oracleNull(left)
                        && !oracleNull(right)
                        && left.toString().equals(right.toString());
    }

    private static Integer sum(Integer left, Integer right) {
        return left == null || right == null ? null : left + right;
    }

    static Column number(int scale) {
        return meta(Kind.NUMBER, 0, scale);
    }

    private static Column auto() {
        return meta(Kind.AUTO, null, null);
    }

    private static Column meta(Kind kind, Integer precision, Integer scale) {
        return new Column(null, kind, precision, scale);
    }
}
