package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.merge.MergeInsert;
import net.sf.jsqlparser.statement.merge.MergeOperation;
import net.sf.jsqlparser.statement.merge.MergeUpdate;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Normalizes the conservative Oracle MERGE subset to PostgreSQL MERGE syntax. */
final class MergeRule {
    boolean rewrite(Statement statement) throws TranslationException {
        if (!(statement instanceof Merge merge)) return false;
        if (merge.getOutputClause() != null) {
            throw unsupported(
                    "MERGE_RETURNING",
                    "MERGE output/returning clauses are outside the supported contract");
        }
        if (merge.getOperations() == null || merge.getOperations().isEmpty()) {
            throw unsupported("MERGE", "MERGE requires an action");
        }

        DualRule.rewriteNested(merge.getFromItem());
        String target = targetName(merge.getTable());
        Set<String> onColumns = targetColumns(merge.getOnCondition(), target);
        for (MergeOperation operation : merge.getOperations()) {
            if (operation instanceof MergeUpdate update) normalizeUpdate(update, target, onColumns);
            else if (operation instanceof MergeInsert insert) normalizeInsert(insert, target);
            else
                throw unsupported(
                        "MERGE_ACTION",
                        "The bounded MERGE contract supports UPDATE and INSERT actions only");
        }
        return true;
    }

    private static void normalizeUpdate(MergeUpdate update, String target, Set<String> onColumns)
            throws TranslationException {
        if (update.getDeleteWhereCondition() != null) {
            throw unsupported(
                    "MERGE_DELETE_WHERE", "Oracle MERGE DELETE WHERE has post-update semantics");
        }
        if (update.getUpdateSets() == null || update.getUpdateSets().isEmpty()) {
            throw unsupported("MERGE_UPDATE", "MERGE UPDATE requires SET assignments");
        }
        for (UpdateSet set : update.getUpdateSets()) {
            for (Column column : set.getColumns()) {
                if (onColumns.contains(normalize(column.getColumnName()))) {
                    throw unsupported(
                            "MERGE_ON_COLUMN_UPDATE",
                            "Oracle MERGE cannot update a target column referenced by ON");
                }
                stripTargetQualifier(column, target);
            }
        }
        if (update.getWhereCondition() != null) {
            update.setAndPredicate(and(update.getAndPredicate(), update.getWhereCondition()));
            update.setWhereCondition(null);
        }
    }

    private static void normalizeInsert(MergeInsert insert, String target)
            throws TranslationException {
        if (insert.getValues() == null)
            throw unsupported("MERGE_INSERT", "MERGE INSERT requires VALUES");
        for (Expression value : insert.getValues()) {
            if (referencesTarget(value, target)) {
                throw unsupported(
                        "MERGE_INSERT_VALUES",
                        "MERGE INSERT values may not reference the target relation");
            }
        }
        if (insert.getWhereCondition() != null
                && referencesTarget(insert.getWhereCondition(), target)) {
            throw unsupported(
                    "MERGE_INSERT_WHERE",
                    "MERGE INSERT WHERE may not reference the target relation");
        }
        if (insert.getColumns() != null) {
            for (Column column : insert.getColumns()) stripTargetQualifier(column, target);
        }
        if (insert.getWhereCondition() != null) {
            insert.setAndPredicate(and(insert.getAndPredicate(), insert.getWhereCondition()));
            insert.setWhereCondition(null);
        }
    }

    private static Expression and(Expression first, Expression second) {
        if (first == null) return parenthesize(second);
        return new AndExpression(parenthesize(first), parenthesize(second));
    }

    private static Expression parenthesize(Expression expression) {
        return new ParenthesedExpressionList<>(expression);
    }

    private static Set<String> targetColumns(Expression expression, String target) {
        Set<String> columns = new HashSet<>();
        if (expression == null) return columns;
        for (Column column : ParserAdapter.columns(expression)) {
            String qualifier = column.getTable() == null ? null : column.getTable().getName();
            if (qualifier == null
                    || qualifier.isBlank()
                    || normalize(qualifier).equals(normalize(target))) {
                columns.add(normalize(column.getColumnName()));
            }
        }
        return columns;
    }

    private static boolean referencesTarget(Expression expression, String target) {
        if (expression == null) return false;
        String normalizedTarget = normalize(target);
        return ParserAdapter.columns(expression).stream()
                .map(Column::getTable)
                .filter(java.util.Objects::nonNull)
                .map(Table::getName)
                .filter(name -> name != null && !name.isBlank())
                .map(MergeRule::normalize)
                .anyMatch(normalizedTarget::equals);
    }

    private static void stripTargetQualifier(Column column, String target) {
        if (column.getTable() != null
                && normalize(column.getTable().getName()).equals(normalize(target))) {
            column.setTable(null);
        }
    }

    private static String targetName(Table table) {
        return table.getAlias() == null ? table.getName() : table.getAlias().getName();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static TranslationException unsupported(String code, String message) {
        return new TranslationException(code, message);
    }
}
