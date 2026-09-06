package io.github.orafit.rewrite;

import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies implicit Oracle character-to-NUMBER conversion only where physical metadata proves it.
 */
public final class DatabaseTypeCoercionRule {
    /** Resolves expression families for scalar lowering and its JDBC metadata plan. */
    public static ColumnTypeResolver.Kind expressionKind(
            Expression value, PlainSelect select, ColumnTypeResolver resolver)
            throws TranslationException {
        try {
            return kind(value, Scope.from(select, resolver));
        } catch (ResolutionFailure failure) {
            throw failure.cause;
        }
    }

    public boolean rewrite(Statement statement, ColumnTypeResolver resolver)
            throws TranslationException {
        Change change = new Change();
        try {
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited =
                    statement instanceof Select select ? cteKinds(select, resolver) : Map.of();
            for (PlainSelect select : SelectTrees.plain(statement)) {
                Scope scope = Scope.from(select, resolver, inherited);
                Coercer coercer = new Coercer(scope, change);
                if (select.getSelectItems() != null)
                    for (SelectItem<?> item : select.getSelectItems())
                        coercer.apply(item.getExpression());
                coercer.apply(select.getWhere());
                coercer.apply(select.getHaving());
                if (select.getJoins() != null)
                    for (Join join : select.getJoins())
                        if (join.getOnExpressions() != null)
                            for (Expression on : join.getOnExpressions()) coercer.apply(on);
            }
            for (SetOperationList set : SelectTrees.sets(statement))
                reconcileSet(set, resolver, inherited, change);
            if (statement instanceof Update update) rewriteUpdate(update, resolver, change);
            if (statement instanceof Insert insert) rewriteInsert(insert, resolver, change);
        } catch (ResolutionFailure failure) {
            throw failure.cause;
        }
        return change.changed;
    }

    private static void reconcileSet(
            SetOperationList set,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        if (set.getSelects() == null || set.getSelects().size() < 2) return;
        int width = Integer.MAX_VALUE;
        List<PlainSelect> branches = new java.util.ArrayList<>();
        for (Select branch : set.getSelects()) {
            PlainSelect select = plainBranch(branch);
            if (select == null || select.getSelectItems() == null) return;
            branches.add(select);
            int branchWidth = outputWidth(select);
            if (branchWidth == 0) return;
            width = Math.min(width, branchWidth);
        }
        for (int index = 0; index < width; index++) {
            var setType = io.github.orafit.metadata.ResultMetadataPlanner.setColumnType(set, index);
            if (setType.kind() == io.github.orafit.translation.ResultMetadataPlan.Kind.AUTO) {
                for (PlainSelect select : branches) {
                    if (outputKind(select, index, resolver, inherited)
                            == ColumnTypeResolver.Kind.FIXED_CHAR)
                        throw new ResolutionFailure(
                                new TranslationException(
                                        "SET_CHAR_WIDTH",
                                        "CHAR set operations require explicit character widths in every branch"));
                }
            }
            if (setType.kind() == io.github.orafit.translation.ResultMetadataPlan.Kind.VARCHAR2) {
                for (PlainSelect select : branches) {
                    if (outputKind(select, index, resolver, inherited)
                            == ColumnTypeResolver.Kind.FIXED_CHAR) {
                        var item = select.getSelectItems().get(index);
                        select.getSelectItems()
                                .set(
                                        index,
                                        SelectItem.from(
                                                new Function(
                                                        "orafit.char_text", item.getExpression()),
                                                item.getAlias()));
                        change.changed = true;
                    }
                }
            }
            boolean number = false;
            for (PlainSelect select : branches) {
                ColumnTypeResolver.Kind type = outputKind(select, index, resolver, inherited);
                number |= type == ColumnTypeResolver.Kind.NUMBER;
            }
            if (!number) continue;
            for (PlainSelect select : branches)
                coerceOutput(select, index, resolver, inherited, change);
        }
    }

    private static PlainSelect plainBranch(Select select) {
        while (select instanceof ParenthesedSelect nested) select = nested.getSelect();
        return select instanceof PlainSelect plain ? plain : null;
    }

    private static int outputWidth(PlainSelect select) {
        if (select.getSelectItems().size() != 1
                || !(select.getSelectItems().get(0).getExpression() instanceof AllColumns))
            return select.getSelectItems().size();
        if (select.getFromItem() instanceof ParenthesedSelect nested)
            return selectWidth(nested.getSelect());
        return 0;
    }

    private static int selectWidth(Select select) {
        if (select instanceof ParenthesedSelect nested) return selectWidth(nested.getSelect());
        if (select instanceof PlainSelect plain) return outputWidth(plain);
        if (select instanceof SetOperationList set
                && set.getSelects() != null
                && !set.getSelects().isEmpty()) return selectWidth(set.getSelects().get(0));
        return 0;
    }

    private static ColumnTypeResolver.Kind outputKind(
            PlainSelect select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        SelectItem<?> item = select.getSelectItems().get(0);
        if (item.getExpression() instanceof AllColumns
                && select.getFromItem() instanceof ParenthesedSelect nested)
            return outputKind(nested.getSelect(), index, resolver, inherited);
        return index < select.getSelectItems().size()
                ? kind(
                        select.getSelectItems().get(index).getExpression(),
                        Scope.from(select, resolver, inherited))
                : ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static ColumnTypeResolver.Kind outputKind(
            Select select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        if (select instanceof ParenthesedSelect nested)
            return outputKind(nested.getSelect(), index, resolver, inherited);
        if (select instanceof PlainSelect plain)
            return outputKind(plain, index, resolver, inherited);
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            ColumnTypeResolver.Kind result = ColumnTypeResolver.Kind.UNKNOWN;
            for (Select branch : set.getSelects())
                result = merge(result, outputKind(branch, index, resolver, inherited));
            return result;
        }
        return ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static void coerceOutput(
            PlainSelect select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        SelectItem<?> item = select.getSelectItems().get(0);
        if (item.getExpression() instanceof AllColumns
                && select.getFromItem() instanceof ParenthesedSelect nested) {
            coerceOutput(nested.getSelect(), index, resolver, inherited, change);
            return;
        }
        item = select.getSelectItems().get(index);
        ColumnTypeResolver.Kind current =
                kind(item.getExpression(), Scope.from(select, resolver, inherited));
        Expression converted;
        if (textual(current)) {
            throw new ResolutionFailure(
                    new TranslationException(
                            "SET_DATATYPE",
                            "ORA-01790: expression must have same datatype as corresponding expression"));
        } else if (current == ColumnTypeResolver.Kind.UNKNOWN
                && item.getExpression() instanceof NullValue) {
            converted = new CastExpression(item.getExpression(), "numeric");
        } else {
            return;
        }
        select.getSelectItems().set(index, SelectItem.from(converted, item.getAlias()));
        change.changed = true;
    }

    private static void coerceOutput(
            Select select,
            int index,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited,
            Change change) {
        if (select instanceof ParenthesedSelect nested) {
            coerceOutput(nested.getSelect(), index, resolver, inherited, change);
        } else if (select instanceof PlainSelect plain) {
            coerceOutput(plain, index, resolver, inherited, change);
        } else if (select instanceof SetOperationList set && set.getSelects() != null) {
            for (Select branch : set.getSelects())
                coerceOutput(branch, index, resolver, inherited, change);
        }
    }

    private static void rewriteUpdate(Update update, ColumnTypeResolver resolver, Change change) {
        if (update.getTable() == null) return;
        Scope scope = Scope.single(update.getTable(), resolver);
        Coercer coercer = new Coercer(scope, change);
        coercer.apply(update.getWhere());
        if (update.getUpdateSets() == null) return;
        for (UpdateSet set : update.getUpdateSets()) {
            for (int i = 0; i < Math.min(set.getColumns().size(), set.getValues().size()); i++) {
                Expression value = set.getValue(i);
                coercer.apply(value);
                if (scope.target(set.getColumn(i)) == ColumnTypeResolver.Kind.NUMBER
                        && kind(value, scope) != ColumnTypeResolver.Kind.NUMBER
                        && !(value instanceof NullValue)) {
                    setValue(set.getValues(), i, OracleCoercion.numberDeferred(value));
                    change.changed = true;
                }
            }
        }
    }

    private static void rewriteInsert(Insert insert, ColumnTypeResolver resolver, Change change) {
        if (insert.getTable() == null || insert.getColumns() == null || insert.getSelect() == null)
            return;
        Scope target = Scope.single(insert.getTable(), resolver);
        if (insert.getSelect() instanceof Values values) {
            coerceTargets(insert, values.getExpressions(), target, change);
        } else {
            coerceSelectTargets(insert.getSelect(), insert, target, change);
        }
    }

    private static void coerceSelectTargets(
            Select select, Insert insert, Scope target, Change change) {
        if (select instanceof ParenthesedSelect nested) {
            coerceSelectTargets(nested.getSelect(), insert, target, change);
            return;
        }
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            for (Select branch : set.getSelects())
                coerceSelectTargets(branch, insert, target, change);
            return;
        }
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null) return;
        for (int i = 0;
                i < Math.min(insert.getColumns().size(), plain.getSelectItems().size());
                i++) {
            SelectItem<?> item = plain.getSelectItems().get(i);
            Expression converted =
                    coerceTarget(item.getExpression(), target, insert.getColumns().get(i));
            if (converted != item.getExpression()) {
                plain.getSelectItems().set(i, SelectItem.from(converted, item.getAlias()));
                change.changed = true;
            }
        }
    }

    private static void coerceTargets(
            Insert insert, ExpressionList<Expression> values, Scope target, Change change) {
        int count = Math.min(insert.getColumns().size(), values.size());
        for (int i = 0; i < count; i++) {
            Expression value = values.get(i);
            Expression converted = coerceTarget(value, target, insert.getColumns().get(i));
            if (converted != value) {
                values.set(i, converted);
                change.changed = true;
            }
        }
    }

    private static Expression coerceTarget(Expression value, Scope target, Column column) {
        ColumnTypeResolver.Kind targetKind = target.target(column);
        if (targetKind == ColumnTypeResolver.Kind.NUMBER
                && kind(value, target) != ColumnTypeResolver.Kind.NUMBER
                && !(value instanceof NullValue)) return OracleCoercion.numberDeferred(value);
        if (temporalTarget(targetKind) && temporalCandidate(value))
            return new CastExpression(value, postgresType(targetKind));
        return value;
    }

    private static boolean temporalTarget(ColumnTypeResolver.Kind kind) {
        return switch (kind) {
            case DATE, TIME, TIMESTAMP, TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    private static boolean temporalCandidate(Expression value) {
        return value instanceof JdbcParameter
                || value instanceof JdbcNamedParameter
                || value instanceof NullValue;
    }

    private static String postgresType(ColumnTypeResolver.Kind kind) {
        return switch (kind) {
            case DATE -> "date";
            case TIME -> "time";
            case TIMESTAMP -> "timestamp";
            case TIMESTAMP_WITH_TIMEZONE -> "timestamptz";
            default -> throw new IllegalArgumentException("Not a temporal kind: " + kind);
        };
    }

    private static final class Coercer extends ExpressionVisitorAdapter<Void> {
        private final Scope scope;
        private final Change change;

        Coercer(Scope scope, Change change) {
            this.scope = scope;
            this.change = change;
        }

        void apply(Expression expression) {
            if (expression != null) expression.accept(this, null);
        }

        @Override
        protected <S> Void visitBinaryExpression(BinaryExpression binary, S context) {
            super.visitBinaryExpression(binary, context);
            preserveFixedCharPaddingComparison(binary);
            if (scope.resolver == ColumnTypeResolver.NONE || !numericContext(binary)) return null;
            ColumnTypeResolver.Kind left = kind(binary.getLeftExpression(), scope);
            ColumnTypeResolver.Kind right = kind(binary.getRightExpression(), scope);
            if (left == ColumnTypeResolver.Kind.NUMBER
                    && numericCandidate(binary.getRightExpression(), right)) {
                binary.setRightExpression(
                        OracleCoercion.numberDeferred(binary.getRightExpression()));
                change.changed = true;
            } else if (right == ColumnTypeResolver.Kind.NUMBER
                    && numericCandidate(binary.getLeftExpression(), left)) {
                binary.setLeftExpression(OracleCoercion.numberDeferred(binary.getLeftExpression()));
                change.changed = true;
            }
            return null;
        }

        private void preserveFixedCharPaddingComparison(BinaryExpression binary) {
            if (!comparison(binary)) return;
            ColumnTypeResolver.Kind left = kind(binary.getLeftExpression(), scope);
            ColumnTypeResolver.Kind right = kind(binary.getRightExpression(), scope);
            if (left == ColumnTypeResolver.Kind.FIXED_CHAR
                    && right == ColumnTypeResolver.Kind.TEXT
                    && !(binary.getRightExpression() instanceof StringValue)
                    && !paddingFunction(binary.getRightExpression())) {
                binary.setLeftExpression(
                        new Function("orafit.char_text", binary.getLeftExpression()));
                change.changed = true;
            } else if (right == ColumnTypeResolver.Kind.FIXED_CHAR
                    && left == ColumnTypeResolver.Kind.TEXT
                    && !(binary.getLeftExpression() instanceof StringValue)
                    && !paddingFunction(binary.getLeftExpression())) {
                binary.setRightExpression(
                        new Function("orafit.char_text", binary.getRightExpression()));
                change.changed = true;
            } else if (left == ColumnTypeResolver.Kind.FIXED_CHAR
                    && paddingFunction(binary.getRightExpression())) {
                binary.setRightExpression(
                        new CastExpression(binary.getRightExpression(), "bpchar"));
                change.changed = true;
            } else if (right == ColumnTypeResolver.Kind.FIXED_CHAR
                    && paddingFunction(binary.getLeftExpression())) {
                binary.setLeftExpression(new CastExpression(binary.getLeftExpression(), "bpchar"));
                change.changed = true;
            }
        }

        @Override
        public <S> Void visit(Function function, S context) {
            super.visit(function, context);
            if ((OracleCoercion.unqualified(function, "NULLIF")
                            || OracleCoercion.unqualified(function, "COALESCE"))
                    && function.getParameters() != null) {
                boolean number = false, text = false;
                for (Expression argument : function.getParameters()) {
                    ColumnTypeResolver.Kind type = kind(argument, scope);
                    if (argument instanceof JdbcNamedParameter
                            || argument instanceof JdbcParameter) {
                        throw new ResolutionFailure(
                                new TranslationException(
                                        "FUNCTION_BIND_TYPE",
                                        "NULLIF/COALESCE bind arguments require an explicit CAST to preserve Oracle datatype validation"));
                    }
                    number |= type == ColumnTypeResolver.Kind.NUMBER;
                    text |= textual(type);
                }
                if (number && text)
                    throw new ResolutionFailure(
                            new TranslationException(
                                    "FUNCTION_DATATYPE", "ORA-00932: inconsistent datatypes"));
            }
            if (scope.resolver != ColumnTypeResolver.NONE
                    && function.getName() != null
                    && List.of("SUM", "AVG").contains(function.getName().toUpperCase(Locale.ROOT))
                    && function.getParameters() != null
                    && function.getParameters().size() == 1) {
                Expression value = function.getParameters().get(0);
                if (kind(value, scope) != ColumnTypeResolver.Kind.NUMBER
                        && !(value instanceof NullValue)) {
                    setValue(function.getParameters(), 0, OracleCoercion.numberDeferred(value));
                    change.changed = true;
                }
            }
            return null;
        }

        @Override
        public <S> Void visit(InExpression in, S context) {
            super.visit(in, context);
            if (!(in.getRightExpression() instanceof ExpressionList<?> values)) return null;
            if (in.getLeftExpression() instanceof ExpressionList<?> columns) {
                for (Expression value : values) {
                    if (!(value instanceof ExpressionList<?> row) || row.size() != columns.size())
                        continue;
                    for (int i = 0; i < columns.size(); i++) {
                        if (kind(columns.get(i), scope) == ColumnTypeResolver.Kind.FIXED_CHAR
                                && paddingFunction(row.get(i))) {
                            setValue(row, i, new CastExpression(row.get(i), "bpchar"));
                            change.changed = true;
                        }
                    }
                }
                return null;
            }
            ColumnTypeResolver.Kind left = kind(in.getLeftExpression(), scope);
            boolean number = false;
            boolean text = false;
            for (Expression value : values) {
                ColumnTypeResolver.Kind current = kind(value, scope);
                number |= current == ColumnTypeResolver.Kind.NUMBER;
                text |= textual(current);
            }
            if (textual(left) && number) {
                in.setLeftExpression(OracleCoercion.numberDeferred(in.getLeftExpression()));
                change.changed = true;
            } else if (left == ColumnTypeResolver.Kind.NUMBER && text) {
                for (int i = 0; i < values.size(); i++)
                    if (textual(kind(values.get(i), scope))) {
                        setValue(values, i, OracleCoercion.numberDeferred(values.get(i)));
                        change.changed = true;
                    }
            }
            return null;
        }
    }

    private static ColumnTypeResolver.Kind kind(Expression value, Scope scope) {
        if (value instanceof net.sf.jsqlparser.expression.DateTimeLiteralExpression literal)
            return literal.getType()
                            == net.sf.jsqlparser.expression.DateTimeLiteralExpression.DateTime.DATE
                    ? ColumnTypeResolver.Kind.DATE
                    : ColumnTypeResolver.Kind.TIMESTAMP;
        if (value == null || value instanceof NullValue) return ColumnTypeResolver.Kind.UNKNOWN;
        if (value instanceof StringValue || OracleCoercion.knownText(value))
            return ColumnTypeResolver.Kind.TEXT;
        if (value instanceof LongValue
                || value instanceof DoubleValue
                || value instanceof SignedExpression
                || OracleCoercion.knownNumber(value)
                || value instanceof Addition
                || value instanceof Subtraction
                || value instanceof Multiplication
                || value instanceof Division) return ColumnTypeResolver.Kind.NUMBER;
        if (value instanceof Column column) return scope.resolve(column);
        if (value instanceof CastExpression cast && cast.getColDataType() != null) {
            String type =
                    cast.getColDataType()
                            .getDataType()
                            .toUpperCase(Locale.ROOT)
                            .replaceFirst("\\s*\\(.*", "")
                            .strip();
            if (type.equals("DATE")) return ColumnTypeResolver.Kind.DATE;
            if (type.equals("TIMESTAMP")) return ColumnTypeResolver.Kind.TIMESTAMP;
            if (type.matches("NUMBER|NUMERIC|DECIMAL|INTEGER|BIGINT|SMALLINT|REAL|FLOAT|DOUBLE"))
                return ColumnTypeResolver.Kind.NUMBER;
            if (type.matches("CHAR|NCHAR|BPCHAR")) return ColumnTypeResolver.Kind.FIXED_CHAR;
            if (type.matches("VARCHAR|VARCHAR2|NVARCHAR2|TEXT|CLOB"))
                return ColumnTypeResolver.Kind.TEXT;
        }
        if (value instanceof Function function && function.getName() != null) {
            String name = function.getName().toUpperCase(Locale.ROOT);
            if (List.of(
                            "TO_DATE",
                            "ADD_MONTHS",
                            "LAST_DAY",
                            "ORAFIT.TO_DATE",
                            "ORAFIT.ADD_MONTHS",
                            "ORAFIT.LAST_DAY",
                            "ORAFIT.CAST_DATE",
                            "ORAFIT.SYSDATE")
                    .contains(name)) return ColumnTypeResolver.Kind.DATE;
            if (name.equals("ORAFIT.CAST_CHAR")) return ColumnTypeResolver.Kind.FIXED_CHAR;
            if (List.of("COUNT", "SUM", "AVG", "ORAFIT.DIVIDE").contains(name))
                return ColumnTypeResolver.Kind.NUMBER;
            if (List.of("NVL", "ORAFIT.NVL", "MIN", "MAX").contains(name)
                    && function.getParameters() != null
                    && !function.getParameters().isEmpty())
                return kind(function.getParameters().get(0), scope);
        }
        if (value instanceof CaseExpression caseExpression) {
            ColumnTypeResolver.Kind result = kind(caseExpression.getElseExpression(), scope);
            if (caseExpression.getWhenClauses() != null)
                for (Expression clause : caseExpression.getWhenClauses())
                    if (clause instanceof net.sf.jsqlparser.expression.WhenClause when)
                        result = merge(result, kind(when.getThenExpression(), scope));
            return result;
        }
        return ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static boolean numericContext(BinaryExpression value) {
        return value instanceof Addition
                || value instanceof Subtraction
                || value instanceof Multiplication
                || value instanceof Division
                || value instanceof EqualsTo
                || value instanceof NotEqualsTo
                || value instanceof GreaterThan
                || value instanceof GreaterThanEquals
                || value instanceof MinorThan
                || value instanceof MinorThanEquals;
    }

    private static boolean comparison(BinaryExpression value) {
        return value instanceof EqualsTo
                || value instanceof NotEqualsTo
                || value instanceof GreaterThan
                || value instanceof GreaterThanEquals
                || value instanceof MinorThan
                || value instanceof MinorThanEquals;
    }

    private static boolean paddingFunction(Expression value) {
        if (!(value instanceof Function function) || function.getName() == null) return false;
        String name = function.getName().toUpperCase(Locale.ROOT);
        return name.equals("LPAD")
                || name.equals("RPAD")
                || name.equals("ORAFIT.LPAD")
                || name.equals("ORAFIT.RPAD");
    }

    private static boolean textual(ColumnTypeResolver.Kind kind) {
        return kind == ColumnTypeResolver.Kind.TEXT || kind == ColumnTypeResolver.Kind.FIXED_CHAR;
    }

    private static boolean numericCandidate(Expression value, ColumnTypeResolver.Kind kind) {
        return textual(kind)
                || (kind == ColumnTypeResolver.Kind.UNKNOWN
                        && (value instanceof Column
                                || value instanceof JdbcParameter
                                || value instanceof JdbcNamedParameter));
    }

    @SuppressWarnings("unchecked")
    private static void setValue(ExpressionList<?> values, int index, Expression value) {
        ((ExpressionList<Expression>) values).set(index, value);
    }

    private record Relation(String schema, String table) {
        static Relation from(Table table) {
            return new Relation(table.getUnquotedSchemaName(), table.getUnquotedName());
        }
    }

    private static Map<String, Map<String, ColumnTypeResolver.Kind>> cteKinds(
            Select select, ColumnTypeResolver resolver) {
        Map<String, Map<String, ColumnTypeResolver.Kind>> visible = new LinkedHashMap<>();
        if (select.getWithItemsList() == null) return visible;
        for (WithItem<?> item : select.getWithItemsList())
            if (item.getSelect() != null && item.getUnquotedAliasName() != null)
                visible.put(
                        item.getUnquotedAliasName().toLowerCase(Locale.ROOT),
                        outputKinds(item.getSelect().getSelect(), resolver, visible));
        return visible;
    }

    private static Map<String, ColumnTypeResolver.Kind> outputKinds(
            Select select,
            ColumnTypeResolver resolver,
            Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
        if (select instanceof ParenthesedSelect nested)
            return outputKinds(nested.getSelect(), resolver, inherited);
        if (select instanceof SetOperationList set && set.getSelects() != null) {
            Map<String, ColumnTypeResolver.Kind> result = new LinkedHashMap<>();
            for (Select branch : set.getSelects())
                outputKinds(branch, resolver, inherited)
                        .forEach(
                                (name, type) ->
                                        result.merge(name, type, DatabaseTypeCoercionRule::merge));
            return result;
        }
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null)
            return Map.of();
        Scope scope = Scope.from(plain, resolver, inherited);
        Map<String, ColumnTypeResolver.Kind> result = new LinkedHashMap<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            if (item.getExpression() instanceof AllColumns
                    && plain.getFromItem() instanceof ParenthesedSelect nested) {
                result.putAll(outputKinds(nested.getSelect(), resolver, inherited));
                continue;
            }
            String name =
                    item.getAlias() != null
                            ? item.getAlias().getUnquotedName()
                            : item.getExpression() instanceof Column column
                                    ? column.getUnquotedColumnName()
                                    : null;
            if (name != null)
                result.put(name.toLowerCase(Locale.ROOT), kind(item.getExpression(), scope));
        }
        return result;
    }

    private static ColumnTypeResolver.Kind merge(
            ColumnTypeResolver.Kind left, ColumnTypeResolver.Kind right) {
        if (left == right || right == ColumnTypeResolver.Kind.UNKNOWN) return left;
        if (left == ColumnTypeResolver.Kind.UNKNOWN) return right;
        if (textual(left) && textual(right)) return ColumnTypeResolver.Kind.TEXT;
        return ColumnTypeResolver.Kind.UNKNOWN;
    }

    private static final class Scope {
        private final ColumnTypeResolver resolver;
        private final Map<String, Relation> relations = new LinkedHashMap<>();
        private final Map<String, Map<String, ColumnTypeResolver.Kind>> derived =
                new LinkedHashMap<>();

        private Scope(ColumnTypeResolver resolver) {
            this.resolver = resolver;
        }

        static Scope from(PlainSelect select, ColumnTypeResolver resolver) {
            return from(select, resolver, Map.of());
        }

        static Scope from(
                PlainSelect select,
                ColumnTypeResolver resolver,
                Map<String, Map<String, ColumnTypeResolver.Kind>> inherited) {
            Scope scope = new Scope(resolver);
            scope.derived.putAll(inherited);
            if (select.getWithItemsList() != null)
                for (WithItem<?> item : select.getWithItemsList())
                    if (item.getParenthesedStatement() instanceof ParenthesedSelect nested)
                        scope.derived.put(
                                item.getUnquotedAliasName().toLowerCase(Locale.ROOT),
                                outputKinds(nested.getSelect(), resolver, scope.derived));
            scope.add(select.getFromItem());
            if (select.getJoins() != null)
                for (Join join : select.getJoins()) scope.add(join.getFromItem());
            return scope;
        }

        static Scope single(Table table, ColumnTypeResolver resolver) {
            Scope scope = new Scope(resolver);
            scope.add(table);
            return scope;
        }

        ColumnTypeResolver.Kind resolve(Column column) {
            String qualifier = column.getUnquotedTableName();
            Relation relation =
                    qualifier == null ? null : relations.get(qualifier.toLowerCase(Locale.ROOT));
            if (relation != null) return resolved(relation, column);
            if (qualifier != null) {
                Map<String, ColumnTypeResolver.Kind> columns =
                        derived.get(qualifier.toLowerCase(Locale.ROOT));
                return columns == null
                        ? ColumnTypeResolver.Kind.UNKNOWN
                        : columns.getOrDefault(
                                column.getUnquotedColumnName().toLowerCase(Locale.ROOT),
                                ColumnTypeResolver.Kind.UNKNOWN);
            }
            return resolveUnqualified(column);
        }

        private ColumnTypeResolver.Kind resolveUnqualified(Column column) {
            ColumnTypeResolver.Kind found = ColumnTypeResolver.Kind.UNKNOWN;
            int matches = 0;
            for (Relation relation : relations.values().stream().distinct().toList()) {
                ColumnTypeResolver.Kind current = resolved(relation, column);
                if (current == ColumnTypeResolver.Kind.UNKNOWN) continue;
                matches++;
                found = current;
            }
            String name = column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
            for (Map<String, ColumnTypeResolver.Kind> columns : derived.values()) {
                if (!columns.containsKey(name)) continue;
                matches++;
                found = columns.get(name);
            }
            return matches == 1 ? found : ColumnTypeResolver.Kind.UNKNOWN;
        }

        private ColumnTypeResolver.Kind resolved(Relation relation, Column column) {
            try {
                return resolver.resolve(
                        relation.schema(), relation.table(), column.getUnquotedColumnName());
            } catch (TranslationException failure) {
                throw new ResolutionFailure(failure);
            }
        }

        ColumnTypeResolver.Kind target(Column column) {
            Relation relation = unique();
            if (relation == null) return ColumnTypeResolver.Kind.UNKNOWN;
            return resolved(relation, column);
        }

        private Relation unique() {
            return relations.values().stream().distinct().limit(2).count() == 1
                    ? relations.values().iterator().next()
                    : null;
        }

        private void add(FromItem item) {
            if (item instanceof ParenthesedSelect nested && nested.getAlias() != null) {
                derived.put(
                        nested.getAlias().getUnquotedName().toLowerCase(Locale.ROOT),
                        outputKinds(nested.getSelect(), resolver, derived));
                return;
            }
            if (!(item instanceof Table table)) return;
            Map<String, ColumnTypeResolver.Kind> cte =
                    derived.get(table.getUnquotedName().toLowerCase(Locale.ROOT));
            if (cte != null) {
                if (table.getAlias() != null)
                    derived.put(table.getAlias().getUnquotedName().toLowerCase(Locale.ROOT), cte);
                return;
            }
            Relation relation = Relation.from(table);
            relations.putIfAbsent(relation.table().toLowerCase(Locale.ROOT), relation);
            if (table.getAlias() != null)
                relations.put(
                        table.getAlias().getUnquotedName().toLowerCase(Locale.ROOT), relation);
        }
    }

    private static final class Change {
        boolean changed;
    }

    private static final class ResolutionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final TranslationException cause;

        ResolutionFailure(TranslationException cause) {
            this.cause = cause;
        }
    }
}
