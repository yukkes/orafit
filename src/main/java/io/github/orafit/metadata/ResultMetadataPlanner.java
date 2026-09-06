package io.github.orafit.metadata;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.rewrite.DatabaseTypeCoercionRule;
import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.ResultMetadataPlan.Column;
import io.github.orafit.translation.ResultMetadataPlan.Kind;
import io.github.orafit.translation.ResultMetadataPlan.Source;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.ConnectByRootOperator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds Oracle-facing result metadata from one parsed SELECT without reparsing rendered SQL. */
public final class ResultMetadataPlanner {
    public ResultMetadataPlan plan(Statement statement) throws TranslationException {
        return plan(statement, null);
    }

    public ResultMetadataPlan plan(Statement statement, String sourceSql)
            throws TranslationException {
        return plan(statement, sourceSql, ColumnTypeResolver.NONE);
    }

    public ResultMetadataPlan plan(
            Statement statement, String sourceSql, ColumnTypeResolver resolver)
            throws TranslationException {
        boolean setOperation = statement instanceof SetOperationList;
        PlainSelect select = statement instanceof Select root ? first(root) : null;
        if (select == null) return ResultMetadataPlan.none();
        List<Column> columns = new ArrayList<>(select.getSelectItems().size());
        List<String> rownumAliases = rownumAliases(statement);
        List<String> sourceLabels = null;
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression expression = item.getExpression();
            if (!(expression instanceof net.sf.jsqlparser.schema.Column)
                    && !(expression instanceof AllColumns)
                    && sourceLabels == null) sourceLabels = sourceLabels(sourceSql);
            Column type =
                    rownumAlias(rownumAliases, expression)
                            ? ExpressionMetadata.number(-127)
                            : setOperation && function(expression, "DECODE")
                                    ? ExpressionMetadata.number(-127)
                                    : oracleType(expression);
            if (expression instanceof Subtraction subtraction
                    && DatabaseTypeCoercionRule.expressionKind(
                                    subtraction.getLeftExpression(), select, resolver)
                            == ColumnTypeResolver.Kind.DATE
                    && DatabaseTypeCoercionRule.expressionKind(
                                    subtraction.getRightExpression(), select, resolver)
                            == ColumnTypeResolver.Kind.DATE) type = ExpressionMetadata.number(0);
            columns.add(
                    new Column(
                            label(item, sourceLabels, columns.size()),
                            type.kind(),
                            type.precision(),
                            type.scale(),
                            type.precisionBind(),
                            source(select, expression),
                            optional(select, expression) ? 1 : null,
                            type.padFromSource(),
                            type.paddingFallback()));
        }
        return new ResultMetadataPlan(columns);
    }

    private static boolean function(Expression expression, String name) {
        return expression instanceof Function function
                && function.getName() != null
                && function.getName().equalsIgnoreCase(name);
    }

    private static PlainSelect first(Select select) {
        if (select instanceof PlainSelect plain) return plain;
        if (select instanceof ParenthesedSelect nested) return first(nested.getSelect());
        if (select instanceof SetOperationList set
                && set.getSelects() != null
                && !set.getSelects().isEmpty()) return first(set.getSelects().get(0));
        return null;
    }

    private static Column oracleType(Expression expression) {
        Column inferred = ExpressionMetadata.type(expression);
        if (expression instanceof BinaryExpression binary
                && (binary instanceof Addition
                        || binary instanceof Subtraction
                        || binary instanceof Multiplication
                        || binary instanceof Division)
                && (toChar(binary.getLeftExpression()) || toChar(binary.getRightExpression()))) {
            return ExpressionMetadata.number(0);
        }
        if (!(expression instanceof Function function)
                || function.getName() == null
                || function.getParameters() == null) return inferred;

        String name = function.getName().toUpperCase(Locale.ROOT);
        if (name.equals("NVL")
                && !function.getParameters().isEmpty()
                && function.getParameters().get(0) instanceof net.sf.jsqlparser.schema.Column) {
            String fallback =
                    function.getParameters().size() >= 2
                                    && function.getParameters().get(1)
                                            instanceof StringValue literal
                            ? literal.getValue()
                            : null;
            return new Column(null, Kind.AUTO, null, 0, null, null, null, true, fallback);
        }
        if (name.equals("DECODE")
                && function.getParameters().size() >= 3
                && function.getParameters().get(2) instanceof net.sf.jsqlparser.schema.Column) {
            return new Column(null, Kind.AUTO, null, null);
        }
        if (name.equals("TO_NUMBER")
                && !function.getParameters().isEmpty()
                && function.getParameters().get(0) instanceof net.sf.jsqlparser.schema.Column) {
            return ExpressionMetadata.number(0);
        }
        if (name.equals("LPAD") && function.getParameters().size() >= 2) {
            Integer width = lpadWidth(function.getParameters().get(1));
            if (width != null) return new Column(null, Kind.VARCHAR2, width, 0);
        }
        return inferred;
    }

    private static boolean toChar(Expression expression) {
        return expression instanceof Function function
                && function.getName() != null
                && function.getName().equalsIgnoreCase("TO_CHAR");
    }

    private static Integer lpadWidth(Expression expression) {
        if (!(expression instanceof LongValue value)) return null;
        long width = value.getValue();
        return width < 0 || width > Integer.MAX_VALUE ? null : (int) width;
    }

    private static String label(SelectItem<?> item, List<String> sourceLabels, int index) {
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null)
            return identifier(alias.getName(), alias.getUnquotedName());
        if (!(item.getExpression() instanceof net.sf.jsqlparser.schema.Column)
                && !(item.getExpression() instanceof AllColumns)
                && sourceLabels != null
                && index < sourceLabels.size()) {
            String source = rawLabel(sourceLabels.get(index));
            if (!source.isBlank()) return source;
        }
        if (item.getExpression() instanceof net.sf.jsqlparser.schema.Column column)
            return identifier(column.getColumnName(), column.getUnquotedColumnName());
        if (item.getExpression() instanceof TrimFunction trim) return trimLabel(trim);
        if (item.getExpression() instanceof AllColumns) return null;
        return item.getExpression() == null
                ? null
                : expressionLabel(item.getExpression().toString());
    }

    private static List<String> sourceLabels(String sql) {
        if (sql == null) return List.of();
        int select = keyword(sql, "SELECT", 0);
        int from = select < 0 ? -1 : keyword(sql, "FROM", select + 6);
        if (from < 0) return List.of();
        String projection = sql.substring(select + 6, from).strip();
        projection = projection.replaceFirst("(?is)^(?:DISTINCT|UNIQUE|ALL)\\s+", "");
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < projection.length(); i++) {
            char current = projection.charAt(i);
            int skipped = skipped(projection, i);
            if (skipped != i) {
                i = skipped;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && depth > 0) {
                depth--;
            } else if (current == ',' && depth == 0) {
                result.add(projection.substring(start, i));
                start = i + 1;
            }
        }
        result.add(projection.substring(start));
        return result;
    }

    private static int keyword(String sql, String keyword, int start) {
        int depth = 0;
        for (int i = start; i <= sql.length() - keyword.length(); i++) {
            int skipped = skipped(sql, i);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char current = sql.charAt(i);
            if (current == '(') depth++;
            else if (current == ')' && depth > 0) depth--;
            else if (depth == 0
                    && sql.regionMatches(true, i, keyword, 0, keyword.length())
                    && (i == 0 || !Character.isJavaIdentifierPart(sql.charAt(i - 1)))
                    && (i + keyword.length() == sql.length()
                            || !Character.isJavaIdentifierPart(sql.charAt(i + keyword.length()))))
                return i;
        }
        return -1;
    }

    private static int skipped(String sql, int start) {
        char current = sql.charAt(start);
        if (current == '\'' || current == '"') {
            for (int i = start + 1; i < sql.length(); i++)
                if (sql.charAt(i) == current) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == current) i++;
                    else return i;
                }
            return sql.length() - 1;
        }
        if (start + 1 < sql.length() && current == '/' && sql.charAt(start + 1) == '*') {
            int end = sql.indexOf("*/", start + 2);
            return end < 0 ? sql.length() - 1 : end + 1;
        }
        if (start + 1 < sql.length() && current == '-' && sql.charAt(start + 1) == '-') {
            int end = sql.indexOf('\n', start + 2);
            return end < 0 ? sql.length() - 1 : end;
        }
        return start;
    }

    private static String rawLabel(String raw) {
        raw = raw.replaceFirst("(?s)^(?:\\s*(?:/\\*.*?\\*/|--[^\\r\\n]*(?:\\R|$)))*", "");
        boolean format = raw.matches("(?is).*\\bTO_(?:CHAR|DATE|TIMESTAMP)\\s*\\(.*");
        StringBuilder result = new StringBuilder(raw.length());
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (!quoted && i + 1 < raw.length() && current == '/' && raw.charAt(i + 1) == '*') {
                int end = raw.indexOf("*/", i + 2);
                if (end < 0) end = raw.length() - 2;
                result.append("/*").append(raw.substring(i + 2, end).strip()).append("*/");
                i = end + 1;
            } else if (!quoted
                    && i + 1 < raw.length()
                    && current == '-'
                    && raw.charAt(i + 1) == '-') {
                int end = raw.indexOf('\n', i + 2);
                if (end < 0) end = raw.length();
                result.append("--").append(raw.substring(i + 2, end).strip());
                i = end;
            } else if (current == '\'') {
                quoted = !quoted;
                result.append(current);
            } else if (!quoted && Character.isWhitespace(current)) {
                continue;
            } else {
                result.append(quoted && !format ? current : Character.toUpperCase(current));
            }
        }
        return result.toString();
    }

    private static String trimLabel(TrimFunction trim) {
        StringBuilder label = new StringBuilder("TRIM(");
        if (trim.getTrimSpecification() != null) {
            label.append(trim.getTrimSpecification().name());
        }
        if (trim.getFromExpression() == null) {
            if (trim.getExpression() != null) {
                label.append(expressionLabel(trim.getExpression().toString()));
            }
        } else {
            if (trim.getExpression() != null) {
                if (trim.getTrimSpecification() != null) label.append(' ');
                label.append(expressionLabel(trim.getExpression().toString()));
            }
            if (label.charAt(label.length() - 1) != '(') label.append(' ');
            label.append("FROM ").append(expressionLabel(trim.getFromExpression().toString()));
        }
        return label.append(')').toString();
    }

    private static String expressionLabel(String expression) {
        StringBuilder label = new StringBuilder(expression.length());
        boolean string = false;
        boolean quotedIdentifier = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (string) {
                label.append(c);
                if (c == '\'') {
                    if (i + 1 < expression.length() && expression.charAt(i + 1) == '\'')
                        label.append(expression.charAt(++i));
                    else string = false;
                }
                continue;
            }
            if (quotedIdentifier) {
                label.append(c);
                if (c == '"') {
                    if (i + 1 < expression.length() && expression.charAt(i + 1) == '"')
                        label.append(expression.charAt(++i));
                    else quotedIdentifier = false;
                }
                continue;
            }
            if (c == '\'') {
                string = true;
                label.append(c);
            } else if (c == '"') {
                quotedIdentifier = true;
                label.append(c);
            } else {
                label.append(Character.toUpperCase(c));
            }
        }
        return label.toString();
    }

    private static String identifier(String raw, String unquoted) {
        return raw != null && raw.startsWith("\"") ? unquoted : unquoted.toUpperCase(Locale.ROOT);
    }

    private static Source source(PlainSelect select, Expression expression) {
        net.sf.jsqlparser.schema.Column column = sourceColumn(expression);
        if (column == null) return null;
        String qualifier = column.getUnquotedTableName();
        List<Table> tables = new ArrayList<>();
        if (select.getFromItem() instanceof Table table) tables.add(table);
        if (select.getJoins() != null)
            for (Join join : select.getJoins())
                if (join.getFromItem() instanceof Table table) tables.add(table);

        Table match = null;
        for (Table table : tables) {
            if (qualifier != null
                    && !qualifier.isBlank()
                    && !qualifier.equalsIgnoreCase(relationName(table))) continue;
            if (match != null && (qualifier == null || qualifier.isBlank())) return null;
            match = table;
        }
        return match == null
                ? null
                : new Source(
                        match.getSchemaName(),
                        match.getUnquotedName(),
                        column.getUnquotedColumnName());
    }

    private static net.sf.jsqlparser.schema.Column sourceColumn(Expression expression) {
        if (expression instanceof net.sf.jsqlparser.schema.Column column) return column;
        if (expression instanceof ConnectByRootOperator root)
            return root.getExpression() instanceof Function
                    ? sourceColumn(root.getExpression())
                    : null;
        if (expression instanceof TrimFunction trim) {
            Expression source =
                    trim.getFromExpression() == null
                            ? trim.getExpression()
                            : trim.getFromExpression();
            return sourceColumn(source);
        }
        if (!(expression instanceof Function function)
                || function.getName() == null
                || function.getParameters() == null) return null;
        if ((function.getName().equalsIgnoreCase("UPPER")
                        || function.getName().equalsIgnoreCase("LOWER")
                        || function.getName().equalsIgnoreCase("NVL")
                        || function.getName().equalsIgnoreCase("LTRIM")
                        || function.getName().equalsIgnoreCase("RTRIM"))
                && !function.getParameters().isEmpty()) {
            return sourceColumn(function.getParameters().get(0));
        }
        if (function.getName().equalsIgnoreCase("DECODE") && function.getParameters().size() >= 3) {
            return sourceColumn(function.getParameters().get(2));
        }
        return null;
    }

    private static boolean optional(PlainSelect select, Expression expression) {
        if (!(expression instanceof net.sf.jsqlparser.schema.Column column)) return false;
        String qualifier = column.getUnquotedTableName();
        if (qualifier == null || qualifier.isBlank()) return false;

        for (net.sf.jsqlparser.schema.Column marked :
                ParserAdapter.nodes(select.getWhere(), net.sf.jsqlparser.schema.Column.class))
            if (marked.getOldOracleJoinSyntax() != 0
                    && qualifier.equalsIgnoreCase(marked.getUnquotedTableName())) return true;

        List<String> left = new ArrayList<>();
        if (select.getFromItem() instanceof Table table) left.add(relationName(table));
        if (select.getJoins() != null)
            for (Join join : select.getJoins()) {
                String right =
                        join.getFromItem() instanceof Table table ? relationName(table) : null;
                if (right != null
                        && qualifier.equalsIgnoreCase(right)
                        && (join.isLeft() || join.isFull())) return true;
                if ((join.isRight() || join.isFull())
                        && left.stream().anyMatch(qualifier::equalsIgnoreCase)) return true;
                if (right != null) left.add(right);
            }

        if (select.getJoins() != null)
            for (Join join : select.getJoins())
                if (join.isApply()
                        && join.isOuter()
                        && join.getFromItem().getAlias() != null
                        && qualifier.equalsIgnoreCase(
                                join.getFromItem().getAlias().getUnquotedName())) return true;
        return false;
    }

    private static String relationName(Table table) {
        return table.getAlias() != null
                ? table.getAlias().getUnquotedName()
                : table.getUnquotedName();
    }

    private static List<String> rownumAliases(Statement statement) {
        List<String> result = new ArrayList<>();
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class))
            for (SelectItem<?> item : select.getSelectItems())
                if (item.getAlias() != null
                        && item.getExpression() instanceof net.sf.jsqlparser.schema.Column source
                        && "ROWNUM".equalsIgnoreCase(source.getUnquotedColumnName()))
                    result.add(item.getAlias().getUnquotedName());
        return result;
    }

    private static boolean rownumAlias(List<String> aliases, Expression expression) {
        if (!(expression instanceof net.sf.jsqlparser.schema.Column column)) return false;
        String name = column.getUnquotedColumnName();
        for (String alias : aliases) if (name.equalsIgnoreCase(alias)) return true;
        return false;
    }
}
