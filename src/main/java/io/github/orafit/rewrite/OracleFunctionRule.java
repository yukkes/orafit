package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.OracleNamedFunctionParameter;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Data-driven Oracle scalar-function routing to the compatibility extension. */
final class OracleFunctionRule {
    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        for (Function function : functions(statement)) {
            if (!OracleCoercion.unqualified(function) || function.getName() == null) continue;
            String name = function.getName().toUpperCase(Locale.ROOT);
            if (name.equals("TO_TIMESTAMP_TZ")) {
                throw new TranslationException(
                        "TIMESTAMP_TZ_EXACT",
                        "PostgreSQL timestamptz does not preserve Oracle TIMESTAMP WITH TIME ZONE"
                                + " offset identity");
            }
            if (name.equals("LNNVL")) {
                checked(function, name, 1, 1, "lnnvl");
                Expression condition = function.getParameters().get(0);
                if (!ParserAdapter.nodes(condition, AndExpression.class).isEmpty()
                        || !ParserAdapter.nodes(condition, OrExpression.class).isEmpty()
                        || !ParserAdapter.nodes(condition, Between.class).isEmpty()) {
                    throw new TranslationException(
                            "LNNVL", "Oracle LNNVL condition cannot contain AND, OR, or BETWEEN");
                }
                function.setName("COALESCE");
                function.setParameters(new NotExpression(condition), new BooleanValue(true));
                changed = true;
                continue;
            }
            String helper =
                    switch (name) {
                        case "NVL" -> checked(function, name, 2, 2, "nvl");
                        case "NVL2" -> checked(function, name, 3, 3, "nvl2");
                        case "CONCAT" ->
                                coerced(
                                        function,
                                        name,
                                        2,
                                        2,
                                        "concat_varchar2",
                                        OracleCoercion.Kind.TEXT,
                                        OracleCoercion.Kind.TEXT);
                        case "ADD_MONTHS" -> checked(function, name, 2, 2, "add_months");
                        case "MONTHS_BETWEEN" -> checked(function, name, 2, 2, "months_between");
                        case "LAST_DAY" -> checked(function, name, 1, 1, "last_day");
                        case "TO_NUMBER" -> checked(function, name, 1, 1, "to_number");
                        case "TO_DATE" -> formatted(function, name, "to_date");
                        case "TO_TIMESTAMP" -> formatted(function, name, "to_timestamp");
                        case "TO_CHAR" -> toChar(function);
                        case "SUBSTR" ->
                                coerced(
                                        function,
                                        name,
                                        2,
                                        3,
                                        "substr",
                                        OracleCoercion.Kind.TEXT,
                                        OracleCoercion.Kind.NUMBER,
                                        OracleCoercion.Kind.NUMBER);
                        case "INSTR" ->
                                coerced(
                                        function,
                                        name,
                                        2,
                                        4,
                                        "instr",
                                        OracleCoercion.Kind.TEXT,
                                        OracleCoercion.Kind.TEXT,
                                        OracleCoercion.Kind.NUMBER,
                                        OracleCoercion.Kind.NUMBER);
                        case "LPAD", "RPAD" ->
                                coerced(
                                        function,
                                        name,
                                        2,
                                        3,
                                        name.toLowerCase(Locale.ROOT),
                                        OracleCoercion.Kind.TEXT,
                                        OracleCoercion.Kind.NUMBER,
                                        OracleCoercion.Kind.TEXT);
                        case "TRUNC" -> checked(function, name, 1, 2, "trunc");
                        case "ROUND" -> checked(function, name, 1, 2, "round");
                        case "REGEXP_LIKE",
                                "REGEXP_COUNT",
                                "REGEXP_INSTR",
                                "REGEXP_SUBSTR",
                                "REGEXP_REPLACE" ->
                                regexp(function, name);
                        case "GREATEST", "LEAST" -> numericExtreme(function, name);
                        default -> null;
                    };
            if (helper != null) {
                function.setName("orafit." + helper);
                changed = true;
            }
        }
        return changed;
    }

    private static List<Function> functions(Statement statement) {
        List<Function> result = new ArrayList<>(ParserAdapter.nodes(statement, Function.class));
        for (PlainSelect select : SelectTrees.plain(statement))
            result.addAll(
                    ParserAdapter.nodes(
                            (net.sf.jsqlparser.parser.ASTNodeAccess) select, Function.class));
        if (statement instanceof Insert insert && insert.getSelect() instanceof Values values)
            addFunctions(result, values.getExpressions());
        if (statement instanceof Update update && update.getUpdateSets() != null)
            for (var set : update.getUpdateSets()) addFunctions(result, set.getValues());
        if (statement instanceof Update update && update.getWhere() != null)
            result.addAll(ParserAdapter.nodes(update.getWhere(), Function.class));
        if (statement instanceof Delete delete && delete.getWhere() != null)
            result.addAll(ParserAdapter.nodes(delete.getWhere(), Function.class));
        if (statement instanceof Execute execute && execute.getExprList() != null) {
            for (Object raw : execute.getExprList()) {
                Expression expression = (Expression) raw;
                if (expression instanceof OracleNamedFunctionParameter named)
                    expression = named.getExpression();
                result.addAll(ParserAdapter.nodes(expression, Function.class));
            }
        }
        ReturningClause returning = SelectTrees.returning(statement);
        if (returning != null) {
            for (SelectItem<?> item : returning) {
                if (item != null && item.getExpression() != null) {
                    result.addAll(ParserAdapter.nodes(item.getExpression(), Function.class));
                }
            }
        }
        return result;
    }

    private static void addFunctions(List<Function> result, Iterable<?> values) {
        if (values == null) return;
        for (Object raw : values) {
            if (raw instanceof Expression expression)
                result.addAll(ParserAdapter.nodes(expression, Function.class));
            else if (raw instanceof Iterable<?> nested) addFunctions(result, nested);
        }
    }

    @SuppressWarnings("unchecked")
    private static String coerced(
            Function function,
            String name,
            int min,
            int max,
            String helper,
            OracleCoercion.Kind... kinds)
            throws TranslationException {
        checked(function, name, min, max, helper);
        var parameters =
                (net.sf.jsqlparser.expression.operators.relational.ExpressionList<Expression>)
                        function.getParameters();
        for (int i = 0; i < parameters.size() && i < kinds.length; i++) {
            parameters.set(i, OracleCoercion.apply(parameters.get(i), kinds[i]));
        }
        return helper;
    }

    private static String formatted(Function function, String name, String helper)
            throws TranslationException {
        checked(function, name, 1, 2, helper);
        if (function.getParameters() != null && function.getParameters().size() == 2) {
            Expression format = function.getParameters().get(1);
            if (!(format instanceof StringValue literal) || !safeDateFormat(literal.getValue())) {
                throw new TranslationException(
                        "FUNCTION_" + name + "_FORMAT",
                        name
                                + " the bounded contract supports only conservative numeric"
                                + " date/time format literals");
            }
        }
        return helper;
    }

    private static String toChar(Function function) throws TranslationException {
        if (function.getParameters() != null && function.getParameters().size() == 2) {
            Expression value = function.getParameters().get(0);
            Expression format = function.getParameters().get(1);
            if (knownDate(value)) return formatted(function, "TO_CHAR", "to_char_format");
            if (numericToCharValue(value)
                    && format instanceof StringValue literal
                    && numericFormat(literal.getValue())) return "to_char_number_format";
            if (value instanceof Column) return formatted(function, "TO_CHAR", "to_char_format");
            throw new TranslationException(
                    "FUNCTION_TO_CHAR_TYPE",
                    "TO_CHAR explicit format requires a known DATE or bounded numeric value");
        }
        checked(function, "TO_CHAR", 1, 1, "to_varchar2");
        Expression value = function.getParameters().get(0);
        function.setParameters(
                value,
                new StringValue("YYYY-MM-DD"),
                new StringValue("YYYY-MM-DD HH24:MI:SS"),
                new StringValue("YYYY-MM-DD HH24:MI:SS TZH:TZM"),
                new StringValue(".,"));
        return "to_varchar2";
    }

    private static boolean numericToCharValue(Expression value) {
        return value instanceof Column
                || value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression
                || value instanceof JdbcNamedParameter;
    }

    private static boolean numericFormat(String format) {
        return format != null && format.matches("[90]+(?:\\.[90]+)?");
    }

    private static boolean knownDate(Expression value) {
        if (value instanceof DateTimeLiteralExpression literal) {
            return literal.getType() == DateTimeLiteralExpression.DateTime.DATE;
        }
        if (value instanceof Addition addition) {
            return knownDate(addition.getLeftExpression())
                    || knownDate(addition.getRightExpression());
        }
        if (value instanceof Subtraction subtraction) {
            return knownDate(subtraction.getLeftExpression());
        }
        if (!(value instanceof Function function) || function.getName() == null) return false;
        List<String> parts = function.getMultipartName();
        if (parts != null
                && parts.size() > 1
                && (parts.size() != 2 || !"orafit".equalsIgnoreCase(parts.get(0)))) {
            return false;
        }
        String name = function.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        return name.equalsIgnoreCase("SYSDATE")
                || name.equalsIgnoreCase("SYSTIMESTAMP")
                || name.equalsIgnoreCase("TO_DATE")
                || name.equalsIgnoreCase("LAST_DAY")
                || name.equalsIgnoreCase("ADD_MONTHS");
    }

    private static String numericExtreme(Function function, String name)
            throws TranslationException {
        List<? extends Expression> args = function.getParameters();
        if (args == null || args.size() < 2) {
            throw new TranslationException(
                    "FUNCTION_" + name, name + " requires at least two arguments");
        }
        Expression first = args.get(0);
        if (!(first instanceof LongValue || first instanceof DoubleValue)) {
            throw new TranslationException(
                    "FUNCTION_" + name + "_TYPE",
                    name + " bounded contract requires a numeric literal as the first argument");
        }
        return name.equals("GREATEST") ? "greatest_number" : "least_number";
    }

    private static String regexp(Function function, String name) throws TranslationException {
        int max =
                switch (name) {
                    case "REGEXP_LIKE" -> 3;
                    case "REGEXP_COUNT" -> 4;
                    case "REGEXP_INSTR" -> 7;
                    case "REGEXP_SUBSTR", "REGEXP_REPLACE" -> 6;
                    default -> throw new IllegalStateException(name);
                };
        checked(function, name, 2, max, name.toLowerCase(Locale.ROOT));
        List<? extends Expression> args = function.getParameters();
        if (!(args.get(1) instanceof StringValue pattern)) {
            throw new TranslationException(
                    "REGEXP_PATTERN",
                    name + " pattern must be a string literal in the bounded contract");
        }
        validatePattern(pattern.getValue());

        int flagIndex =
                switch (name) {
                    case "REGEXP_LIKE" -> args.size() >= 3 ? 2 : -1;
                    case "REGEXP_COUNT" -> args.size() >= 4 ? 3 : -1;
                    case "REGEXP_INSTR" -> args.size() >= 6 ? 5 : -1;
                    case "REGEXP_SUBSTR" -> args.size() >= 5 ? 4 : -1;
                    case "REGEXP_REPLACE" -> args.size() >= 6 ? 5 : -1;
                    default -> -1;
                };
        if (flagIndex >= 0
                && args.get(flagIndex) instanceof StringValue flags
                && unsupportedFlags(flags.getValue())) {
            throw new TranslationException(
                    "REGEXP_MATCH_PARAM", "REGEXP match_param supports c/i/m/n only");
        }
        if (name.equals("REGEXP_REPLACE") && args.size() >= 3) {
            Expression replacement = args.get(2);
            if (replacement instanceof StringValue literal) validateReplacement(literal.getValue());
            else if (!(replacement instanceof NullValue)) {
                throw new TranslationException(
                        "REGEXP_REPLACEMENT",
                        "REGEXP_REPLACE replacement must be a string literal or NULL in the bounded"
                                + " contract");
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validatePattern(String pattern) throws TranslationException {
        if (pattern.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new TranslationException(
                    "REGEXP_PATTERN", "Oracle REGEXP pattern exceeds 512 bytes");
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
                throw new TranslationException(
                        "REGEXP_PATTERN",
                        "PostgreSQL '(?' regex extensions are outside the Oracle subset");
            }
            if (c != '\\') continue;
            if (++i >= pattern.length()) {
                throw new TranslationException(
                        "REGEXP_PATTERN", "REGEXP pattern cannot end with a backslash");
            }
            char escaped = pattern.charAt(i);
            if (Character.isLetter(escaped) || escaped == '0') {
                throw new TranslationException(
                        "REGEXP_PATTERN",
                        "Backslash-letter/octal regex extensions are outside the Oracle subset");
            }
        }
    }

    private static void validateReplacement(String replacement) throws TranslationException {
        int refs = 0;
        for (int i = 0; i < replacement.length(); i++) {
            if (replacement.charAt(i) != '\\') continue;
            if (++i >= replacement.length()) {
                throw new TranslationException(
                        "REGEXP_REPLACEMENT", "Replacement cannot end with a backslash");
            }
            char escaped = replacement.charAt(i);
            if (escaped >= '1' && escaped <= '9') refs++;
            else if (escaped != '\\') {
                throw new TranslationException(
                        "REGEXP_REPLACEMENT",
                        "Replacement supports only backreferences and escaped backslashes");
            }
        }
        if (refs > 500) {
            throw new TranslationException(
                    "REGEXP_REPLACEMENT",
                    "Oracle REGEXP_REPLACE allows at most 500 backreferences");
        }
    }

    private static boolean unsupportedFlags(String flags) {
        for (int i = 0; i < flags.length(); i++) {
            if ("cimn".indexOf(Character.toLowerCase(flags.charAt(i))) < 0) return true;
        }
        return false;
    }

    private static boolean safeDateFormat(String format) {
        String upper = format.toUpperCase(Locale.ROOT);
        String[] tokens = {
            "HH24", "HH12", "YYYY", "TZH", "TZM", "FF9", "FF8", "FF7", "FF6", "FF5", "FF4", "FF3",
            "FF2", "FF1", "FF", "SS", "MI", "MM", "DD", "YY"
        };
        int i = upper.startsWith("FX") ? 2 : 0;
        int start = i;
        while (i < upper.length()) {
            if (!Character.isLetter(upper.charAt(i))) {
                i++;
                continue;
            }
            String found = null;
            for (String token : tokens) {
                if (upper.startsWith(token, i)) {
                    found = token;
                    break;
                }
            }
            if (found == null) return false;
            i += found.length();
        }
        return i > start;
    }

    private static String checked(
            Function function, String name, int minArgs, int maxArgs, String helper)
            throws TranslationException {
        int args = function.getParameters() == null ? 0 : function.getParameters().size();
        if (args < minArgs || args > maxArgs) {
            throw new TranslationException(
                    "FUNCTION_" + name,
                    name
                            + " bounded contract requires "
                            + minArgs
                            + (minArgs == maxArgs ? "" : ".." + maxArgs)
                            + " argument(s)");
        }
        return helper;
    }
}
