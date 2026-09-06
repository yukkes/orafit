package io.github.orafit.rewrite;

import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.OracleNamedFunctionParameter;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lowers Oracle scalar operators using only public JSqlParser expression APIs. */
final class ScalarLoweringRule {
    boolean rewrite(Statement statement, ColumnTypeResolver resolver) throws TranslationException {
        Change change = new Change();
        change.resolver = resolver;
        change.databaseResolvedDecodes.addAll(DecodeLowering.databaseResolved(statement));
        for (PlainSelect select : SelectTrees.plain(statement)) {
            change.select = select;
            normalizeDerivedAggregateNumbers(select, change);
            lowerItems(select.getSelectItems(), change);
            select.setWhere(lower(select.getWhere(), change));
            select.setHaving(lower(select.getHaving(), change));
            if (select.getJoins() != null)
                for (var join : select.getJoins())
                    for (Expression on : join.getOnExpressions()) lower(on, change);
            if (select.getOrderByElements() != null)
                for (var order : select.getOrderByElements())
                    order.setExpression(lower(order.getExpression(), change));
            if (select.getGroupBy() != null)
                lowerList(select.getGroupBy().getGroupByExpressionList(), change);
        }
        if (statement instanceof Insert insert && insert.getSelect() instanceof Values values)
            lowerList(values.getExpressions(), change);
        if (statement instanceof Update update && update.getUpdateSets() != null)
            for (var set : update.getUpdateSets()) lowerList(set.getValues(), change);
        if (statement instanceof Update update) update.setWhere(lower(update.getWhere(), change));
        if (statement instanceof Delete delete) delete.setWhere(lower(delete.getWhere(), change));
        if (statement instanceof net.sf.jsqlparser.statement.merge.Merge merge)
            lowerMerge(merge, change);
        if (statement instanceof Execute execute && execute.getExprList() != null)
            lowerList(execute.getExprList(), change);
        ReturningClause returning = SelectTrees.returning(statement);
        if (returning != null) lowerItems(returning, change);
        return change.changed;
    }

    private static void lowerMerge(net.sf.jsqlparser.statement.merge.Merge merge, Change change)
            throws TranslationException {
        Expression on = merge.getOnCondition();
        if (on instanceof ExpressionList<?> list) lowerList(list, change);
        else merge.setOnCondition(lower(on, change));
        var update = merge.getMergeUpdate();
        if (update != null) {
            if (update.getUpdateSets() != null)
                for (var set : update.getUpdateSets()) lowerList(set.getValues(), change);
            update.setWhereCondition(lower(update.getWhereCondition(), change));
        }
        var insert = merge.getMergeInsert();
        if (insert != null) {
            lowerList(insert.getValues(), change);
            insert.setWhereCondition(lower(insert.getWhereCondition(), change));
        }
    }

    private static void normalizeDerivedAggregateNumbers(PlainSelect select, Change change) {
        if (!(select.getFromItem() instanceof ParenthesedSelect source)) return;
        for (SelectItem<?> item : select.getSelectItems()) {
            if (!(item.getExpression() instanceof Function function)
                    || !(OracleCoercion.unqualified(function, "SUM")
                            || OracleCoercion.unqualified(function, "AVG"))
                    || function.getParameters() == null
                    || function.getParameters().size() != 1
                    || !(function.getParameters().get(0) instanceof Column column)
                    || !derivedTextColumn(source, column.getUnquotedColumnName())) continue;
            @SuppressWarnings("unchecked")
            ExpressionList<Expression> parameters =
                    (ExpressionList<Expression>) function.getParameters();
            parameters.set(0, OracleCoercion.number(parameters.get(0)));
            change.changed = true;
        }
    }

    private static boolean derivedTextColumn(ParenthesedSelect source, String name) {
        if (source.getSelect() instanceof PlainSelect plain) {
            int index = outputIndex(plain, name);
            return index >= 0 && textLiteralOutput(plain, index);
        }
        if (!(source.getSelect() instanceof SetOperationList set)
                || set.getSelects() == null
                || set.getSelects().isEmpty()
                || !(set.getSelects().get(0) instanceof PlainSelect first)) return false;
        int index = outputIndex(first, name);
        if (index < 0) return false;
        for (var branch : set.getSelects()) {
            if (!(branch instanceof PlainSelect plain) || !textLiteralOutput(plain, index))
                return false;
        }
        return true;
    }

    private static int outputIndex(PlainSelect select, String name) {
        for (int i = 0; i < select.getSelectItems().size(); i++) {
            SelectItem<?> item = select.getSelectItems().get(i);
            String output =
                    item.getAlias() != null
                            ? item.getAlias().getUnquotedName()
                            : item.getExpression() instanceof Column column
                                    ? column.getUnquotedColumnName()
                                    : null;
            if (output != null && output.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static boolean textLiteralOutput(PlainSelect select, int index) {
        if (select.getSelectItems().size() <= index) return false;
        Expression value = select.getSelectItems().get(index).getExpression();
        return value instanceof StringValue || value instanceof NullValue;
    }

    private static void lowerItems(List<SelectItem<?>> items, Change change)
            throws TranslationException {
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            Expression source = item.getExpression(), lowered = lower(source, change);
            if (lowered != source) items.set(i, SelectItem.from(lowered, item.getAlias()));
        }
    }

    private static Expression lower(Expression expression, Change change)
            throws TranslationException {
        if (expression == null) return null;
        if (expression instanceof ExpressionList<?> list) {
            @SuppressWarnings("unchecked")
            ExpressionList<Expression> expressions = (ExpressionList<Expression>) list;
            for (int i = 0; i < expressions.size(); i++) {
                Expression source = expressions.get(i), lowered = lower(source, change);
                if (lowered != source) expressions.set(i, lowered);
            }
            return list;
        }
        if (expression instanceof StringValue literal && literal.getValue().isEmpty()) {
            change.changed = true;
            return new NullValue();
        }
        if (expression instanceof DateTimeLiteralExpression literal
                && literal.getType() == DateTimeLiteralExpression.DateTime.TIMESTAMP
                && fractionalDigits(literal.getValue()) > 6) {
            throw new TranslationException(
                    "TIMESTAMP_PRECISION",
                    "Oracle TIMESTAMP fractional precision above 6 digits cannot be represented"
                            + " exactly by PostgreSQL");
        }
        if (expression instanceof SignedExpression signed) {
            signed.setExpression(lower(signed.getExpression(), change));
            return signed;
        }
        if (expression instanceof CastExpression cast) {
            cast.setLeftExpression(lower(cast.getLeftExpression(), change));
            if (cast.getColDataType() != null) {
                String type = cast.getColDataType().getDataType();
                var charType =
                        java.util.regex.Pattern.compile(
                                        "(?i)^CHAR\\s*\\(\\s*(\\d+)\\s*(BYTE|CHAR)?\\s*\\)$")
                                .matcher(cast.getColDataType().toString());
                if (charType.matches()) {
                    change.changed = true;
                    return new Function(
                            "orafit.cast_char",
                            OracleCoercion.text(cast.getLeftExpression()),
                            new LongValue(charType.group(1)),
                            new net.sf.jsqlparser.expression.BooleanValue(
                                    !"CHAR".equalsIgnoreCase(charType.group(2))));
                }
                if ("DATE".equalsIgnoreCase(type)) {
                    change.changed = true;
                    return new Function(
                            "orafit.cast_date",
                            new CastExpression(cast.getLeftExpression(), "timestamp"));
                }
                if ("VARCHAR2".equalsIgnoreCase(type)) {
                    cast.getColDataType().setDataType("varchar");
                    change.changed = true;
                }
                if (type != null
                        && type.stripLeading().toUpperCase(Locale.ROOT).startsWith("NUMBER")) {
                    cast.getColDataType()
                            .setDataType(type.replaceFirst("(?i)^\\s*NUMBER", "numeric"));
                    change.changed = true;
                }
            }
            return cast;
        }
        if (expression instanceof OracleNamedFunctionParameter named) {
            Expression source = named.getExpression(), lowered = lower(source, change);
            return lowered == source
                    ? named
                    : new OracleNamedFunctionParameter(named.getName(), lowered);
        }
        if (expression instanceof Column column) {
            Expression replacement = sequence(column);
            if (replacement != null) {
                change.changed = true;
                return replacement;
            }
            return expression;
        }
        if (expression instanceof Concat concat) {
            change.changed = true;
            return new Function(
                    "orafit.concat_varchar2",
                    varchar(lower(concat.getLeftExpression(), change)),
                    varchar(lower(concat.getRightExpression(), change)));
        }
        if (expression instanceof TrimFunction trim) return lowerTrim(trim, change);
        if (expression instanceof Function function) {
            lowerFunction(function, change);
            if (OracleCoercion.unqualified(function, "LTRIM")
                    || OracleCoercion.unqualified(function, "RTRIM")) {
                return lowerDirectionalTrim(function, change);
            }
            if (OracleCoercion.unqualified(function, "DECODE")) {
                change.changed = true;
                return DecodeLowering.lower(
                        function, change.databaseResolvedDecodes.contains(function));
            }
            return function;
        }
        if (expression instanceof CaseExpression caseExpression) {
            if (caseDatatypeMismatch(caseExpression)) {
                throw new TranslationException(
                        "CASE_DATATYPE",
                        "ORA-00932: inconsistent datatypes: expected NUMBER got CHAR");
            }
            caseExpression.setSwitchExpression(lower(caseExpression.getSwitchExpression(), change));
            if (caseExpression.getWhenClauses() != null)
                for (WhenClause clause : caseExpression.getWhenClauses()) lowerWhen(clause, change);
            caseExpression.setElseExpression(lower(caseExpression.getElseExpression(), change));
            return caseExpression;
        }
        if (expression instanceof WhenClause clause) {
            lowerWhen(clause, change);
            return clause;
        }
        if (expression instanceof IsNullExpression test) {
            test.setLeftExpression(lower(test.getLeftExpression(), change));
            return test;
        }
        if (expression instanceof LikeExpression like) {
            Expression left = lower(like.getLeftExpression(), change);
            Expression right = lower(like.getRightExpression(), change);
            like.setLeftExpression(OracleCoercion.text(left));
            like.setRightExpression(OracleCoercion.text(right));
            if (like.getEscape() == null) like.setEscape(new StringValue(""));
            change.changed = true;
            return like;
        }
        if (expression instanceof BinaryExpression binary) {
            binary.setLeftExpression(lower(binary.getLeftExpression(), change));
            binary.setRightExpression(lower(binary.getRightExpression(), change));
            if (binary instanceof Division) {
                change.changed = true;
                return new Function(
                        "orafit.divide",
                        OracleCoercion.apply(
                                binary.getLeftExpression(), OracleCoercion.Kind.NUMBER),
                        OracleCoercion.apply(
                                binary.getRightExpression(), OracleCoercion.Kind.NUMBER));
            }
            if (binary instanceof Subtraction
                    && knownDate(binary.getLeftExpression(), change)
                    && knownDate(binary.getRightExpression(), change)) {
                change.changed = true;
                return new Function(
                        "orafit.date_difference",
                        binary.getLeftExpression(),
                        binary.getRightExpression());
            }
            lowerImplicitNumber(binary, change);
            lowerDays(binary, change);
        }
        return expression;
    }

    @SuppressWarnings("unchecked")
    private static Expression lowerDirectionalTrim(Function function, Change change)
            throws TranslationException {
        ExpressionList<Expression> parameters =
                (ExpressionList<Expression>) function.getParameters();
        int count = parameters == null ? 0 : parameters.size();
        String name = function.getName().toUpperCase(Locale.ROOT);
        if (count < 1 || count > 2) {
            throw new TranslationException(
                    "FUNCTION_" + name, name + " bounded contract requires 1..2 argument(s)");
        }
        for (int i = 0; i < count; i++) {
            parameters.set(i, OracleCoercion.apply(parameters.get(i), OracleCoercion.Kind.TEXT));
        }
        function.setName("orafit." + name.toLowerCase(Locale.ROOT));
        change.changed = true;
        return function;
    }

    private static Expression lowerTrim(TrimFunction trim, Change change)
            throws TranslationException {
        boolean explicitSource = trim.getFromExpression() != null;
        Expression source = explicitSource ? trim.getFromExpression() : trim.getExpression();
        Expression character = explicitSource ? trim.getExpression() : null;
        source = lower(source, change);
        character = lower(character, change);
        String direction =
                trim.getTrimSpecification() == null ? "BOTH" : trim.getTrimSpecification().name();
        change.changed = true;
        if (character == null) {
            return new Function(
                    "orafit.trim",
                    OracleCoercion.apply(source, OracleCoercion.Kind.TEXT),
                    new StringValue(direction));
        }
        return new Function(
                "orafit.trim",
                OracleCoercion.apply(source, OracleCoercion.Kind.TEXT),
                OracleCoercion.apply(character, OracleCoercion.Kind.TEXT),
                new StringValue(direction));
    }

    private static void lowerImplicitNumber(BinaryExpression binary, Change change) {
        if (!numericCoercionContext(binary)) return;
        Expression left = binary.getLeftExpression(), right = binary.getRightExpression();
        if ((characterLiteral(left) || OracleCoercion.knownText(left))
                && (numericLiteral(right) || OracleCoercion.knownNumber(right))) {
            binary.setLeftExpression(OracleCoercion.number(left));
            change.changed = true;
        } else if ((numericLiteral(left) || OracleCoercion.knownNumber(left))
                && (characterLiteral(right) || OracleCoercion.knownText(right))) {
            binary.setRightExpression(OracleCoercion.number(right));
            change.changed = true;
        }
    }

    private static boolean numericCoercionContext(BinaryExpression binary) {
        return binary instanceof Addition
                || binary instanceof Subtraction
                || binary instanceof Multiplication
                || binary instanceof Division
                || binary instanceof EqualsTo
                || binary instanceof NotEqualsTo
                || binary instanceof GreaterThan
                || binary instanceof GreaterThanEquals
                || binary instanceof MinorThan
                || binary instanceof MinorThanEquals;
    }

    private static void lowerDays(BinaryExpression binary, Change change)
            throws TranslationException {
        if (!(binary instanceof Addition || binary instanceof Subtraction)) return;
        Expression left = binary.getLeftExpression(), right = binary.getRightExpression();
        if (knownDate(left) && dayNumber(right)) {
            binary.setRightExpression(new Function("orafit.days_interval", right));
            change.changed = true;
        } else if (binary instanceof Addition && dayNumber(left) && knownDate(right)) {
            binary.setLeftExpression(new Function("orafit.days_interval", left));
            change.changed = true;
        }
    }

    private static boolean caseDatatypeMismatch(CaseExpression value) {
        boolean number = false;
        boolean text = false;
        if (value.getWhenClauses() != null) {
            for (WhenClause clause : value.getWhenClauses()) {
                Expression result = clause.getThenExpression();
                number |= numericLiteral(result);
                text |= characterLiteral(result);
            }
        }
        Expression result = value.getElseExpression();
        number |= numericLiteral(result);
        text |= characterLiteral(result);
        return number && text;
    }

    private static boolean characterLiteral(Expression value) {
        return value instanceof StringValue text && !text.getValue().isEmpty();
    }

    private static boolean numericLiteral(Expression value) {
        return value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression;
    }

    private static int fractionalDigits(String value) {
        int dot = value.indexOf('.');
        if (dot < 0) return 0;
        int end = dot + 1;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        return end - dot - 1;
    }

    private static boolean knownDate(Expression value, Change change) throws TranslationException {
        if (change.select != null
                && DatabaseTypeCoercionRule.expressionKind(value, change.select, change.resolver)
                        == ColumnTypeResolver.Kind.DATE) return true;
        return knownDate(value);
    }

    private static boolean knownDate(Expression value) {
        if (value instanceof DateTimeLiteralExpression literal
                && literal.getType() == DateTimeLiteralExpression.DateTime.DATE) return true;
        if (!(value instanceof Function function) || function.getName() == null) return false;
        return "orafit.cast_date".equalsIgnoreCase(function.getName())
                || "orafit.sysdate".equalsIgnoreCase(function.getName())
                || OracleCoercion.unqualified(function, "TO_DATE")
                || OracleCoercion.unqualified(function, "LAST_DAY")
                || OracleCoercion.unqualified(function, "ADD_MONTHS");
    }

    private static boolean dayNumber(Expression value) {
        return value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression
                || value instanceof JdbcNamedParameter;
    }

    @SuppressWarnings("unchecked")
    private static void lowerFunction(Function function, Change change)
            throws TranslationException {
        ExpressionList<Expression> parameters =
                (ExpressionList<Expression>) function.getParameters();
        if (parameters == null) return;
        for (int i = 0; i < parameters.size(); i++) {
            Expression source = parameters.get(i), lowered = lower(source, change);
            if (lowered != source) parameters.set(i, lowered);
        }
    }

    private static void lowerWhen(WhenClause clause, Change change) throws TranslationException {
        clause.setWhenExpression(lower(clause.getWhenExpression(), change));
        clause.setThenExpression(lower(clause.getThenExpression(), change));
    }

    @SuppressWarnings("unchecked")
    private static void lowerList(ExpressionList<?> raw, Change change)
            throws TranslationException {
        ExpressionList<Expression> expressions = (ExpressionList<Expression>) raw;
        if (expressions == null) return;
        for (int i = 0; i < expressions.size(); i++) {
            Expression value = expressions.get(i);
            if (value instanceof ExpressionList<?> nested) {
                lowerList(nested, change);
                continue;
            }
            Expression lowered = lower(value, change);
            if (lowered != value) expressions.set(i, lowered);
        }
    }

    private static boolean isSysdate(Expression expression) {
        return expression instanceof Column column
                && (column.getTableName() == null || column.getTableName().isBlank())
                && "SYSDATE".equalsIgnoreCase(column.getUnquotedColumnName());
    }

    private static boolean isSystimestamp(Expression expression) {
        return expression instanceof Column column
                && (column.getTableName() == null || column.getTableName().isBlank())
                && "SYSTIMESTAMP".equalsIgnoreCase(column.getUnquotedColumnName());
    }

    private static Expression sequence(Column column) throws TranslationException {
        if (isSysdate(column)) return new Function("orafit.sysdate");
        if (isSystimestamp(column)) return new Function("orafit.systimestamp");
        String operation = column.getUnquotedColumnName();
        if (operation == null || column.getTable() == null) return null;
        operation = operation.toUpperCase(Locale.ROOT);
        if (!operation.equals("NEXTVAL") && !operation.equals("CURRVAL")) return null;
        String sequence = column.getTable().getFullyQualifiedName();
        if (sequence == null || sequence.isBlank()) {
            throw new TranslationException(
                    "SEQUENCE_NAME", "Oracle sequence reference has no sequence name");
        }
        return new Function(
                "pg_catalog." + operation.toLowerCase(Locale.ROOT), new StringValue(sequence));
    }

    private static Expression varchar(Expression value) {
        return OracleCoercion.text(value);
    }

    private static final class Change {
        boolean changed;
        PlainSelect select;
        ColumnTypeResolver resolver;
        final Set<Function> databaseResolvedDecodes =
                Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
