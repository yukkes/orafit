package io.github.orafit.rewrite;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;

import java.util.List;

/** Shared bounded Oracle implicit conversions for contexts with a known target type. */
final class OracleCoercion {
    enum Kind {
        NONE,
        TEXT,
        NUMBER
    }

    private OracleCoercion() {}

    static Expression apply(Expression value, Kind kind) {
        return switch (kind) {
            case NONE -> value;
            case TEXT -> textArgument(value);
            case NUMBER -> numberArgument(value);
        };
    }

    static Expression text(Expression value) {
        if (value == null || value instanceof StringValue || value instanceof NullValue)
            return value;
        if (value instanceof JdbcNamedParameter) return new CastExpression(value, "text");
        if (knownText(value)) return value;
        return new Function(
                "orafit.to_varchar2",
                value,
                new StringValue("YYYY-MM-DD"),
                new StringValue("YYYY-MM-DD HH24:MI:SS"),
                new StringValue("YYYY-MM-DD HH24:MI:SS TZH:TZM"),
                new StringValue(".,"));
    }

    static Expression number(Expression value) {
        if (value == null
                || value instanceof NullValue
                || value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression) return value;
        if (function(value, "orafit.to_number") || function(value, "orafit.to_number_bind"))
            return value;
        return new Function("orafit.to_number", value);
    }

    static Expression numberDeferred(Expression value) {
        if (value instanceof StringValue)
            return new Function("orafit.to_number_deferred", new CastExpression(value, "text"));
        return number(value);
    }

    static boolean knownText(Expression value) {
        if (value instanceof StringValue) return true;
        if (!(value instanceof Function function) || function.getName() == null) return false;
        return unqualified(function, "TO_CHAR")
                || unqualified(function, "SUBSTR")
                || unqualified(function, "LPAD")
                || unqualified(function, "RPAD")
                || unqualified(function, "LTRIM")
                || unqualified(function, "RTRIM")
                || function(value, "orafit.to_varchar2")
                || function(value, "orafit.concat_varchar2")
                || function(value, "orafit.substr")
                || function(value, "orafit.lpad")
                || function(value, "orafit.rpad")
                || function(value, "orafit.trim")
                || function(value, "orafit.ltrim")
                || function(value, "orafit.rtrim");
    }

    static boolean knownNumber(Expression value) {
        return value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression
                || function(value, "orafit.to_number")
                || function(value, "orafit.to_number_bind");
    }

    private static Expression textArgument(Expression value) {
        Expression converted = text(value);
        if (converted instanceof StringValue || converted instanceof NullValue)
            return new CastExpression(converted, "text");
        return converted;
    }

    private static Expression numberArgument(Expression value) {
        if (value instanceof StringValue)
            return new Function("orafit.to_number", new CastExpression(value, "text"));
        Expression converted = number(value);
        if (converted instanceof NullValue
                || converted instanceof LongValue
                || converted instanceof DoubleValue
                || converted instanceof SignedExpression)
            return new CastExpression(converted, "numeric");
        return converted;
    }

    static boolean unqualified(Function function) {
        List<String> parts = function.getMultipartName();
        return parts == null || parts.size() == 1;
    }

    static boolean unqualified(Function function, String name) {
        return function.getName() != null
                && name.equalsIgnoreCase(function.getName())
                && unqualified(function);
    }

    private static boolean function(Expression value, String name) {
        if (!(value instanceof Function function) || function.getName() == null) return false;
        return name.equalsIgnoreCase(function.getName());
    }
}
