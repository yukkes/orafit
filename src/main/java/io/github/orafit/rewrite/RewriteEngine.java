package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.rewrite.hierarchy.HierarchyLoweringRule;
import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.Feature;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Distinct;
import net.sf.jsqlparser.statement.select.ExceptOp;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.MinusOp;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Applies the ordered Orafit rewrite pipeline directly to one JSqlParser AST.
 *
 * <p>Rules mutate the same tree and the caller renders only after the complete pipeline. The rule
 * order is part of the translation contract because later rules may rely on normalization performed
 * by earlier rules.
 */
public final class RewriteEngine {
    private static final Set<Feature> UNSUPPORTED =
            EnumSet.of(Feature.MULTI_INSERT, Feature.UNSUPPORTED_ORACLE);

    private final PivotUnpivotRule pivotUnpivot = new PivotUnpivotRule();
    private final ScalarLoweringRule scalar = new ScalarLoweringRule();
    private final OracleFunctionRule functions = new OracleFunctionRule();
    private final DualRule dual = new DualRule();
    private final ListaggRule listagg = new ListaggRule();
    private final ApplyRule apply = new ApplyRule();
    private final OuterJoinRule outerJoin = new OuterJoinRule();
    private final ForUpdateRule forUpdate = new ForUpdateRule();
    private final HierarchyLoweringRule hierarchy = new HierarchyLoweringRule();
    private final RownumRule rownum = new RownumRule();
    private final MergeRule merge = new MergeRule();
    private final SequenceRestartRule sequenceRestart = new SequenceRestartRule();

    /** Rejects features that are recognized but intentionally outside the supported contract. */
    public void validate(Set<Feature> features) throws TranslationException {
        if (features.contains(Feature.PIVOT) && features.contains(Feature.UNPIVOT)) {
            throw new TranslationException(
                    "UNSUPPORTED_PIVOT",
                    "Combined PIVOT/UNPIVOT forms are outside the supported basic subset");
        }
        for (Feature feature : UNSUPPORTED) {
            if (features.contains(feature)) {
                throw new TranslationException(
                        feature == Feature.UNSUPPORTED_ORACLE
                                ? feature.name()
                                : "UNSUPPORTED_" + feature.name(),
                        feature + " is intentionally outside the supported Orafit contract");
            }
        }
    }

    /**
     * Applies every relevant lowering rule in contract order.
     *
     * @param statement parsed statement to mutate
     * @return final statement reference and whether any rewrite changed it
     * @throws TranslationException when a recognized form cannot be lowered safely
     */
    public Result rewrite(Statement statement) throws TranslationException {
        return rewrite(statement, ColumnTypeResolver.NONE);
    }

    public Result rewrite(Statement statement, ColumnTypeResolver resolver)
            throws TranslationException {
        validateInListLimit(statement);
        SequenceRestartRule.Result sequenceResult = sequenceRestart.rewrite(statement);
        boolean changed = rewriteUnique(statement);
        changed |= rewriteMinus(statement);
        changed |= pivotUnpivot.rewrite(statement);
        changed |= normalizeDerivedAliases(statement);
        changed |= normalizeDuplicateJoinAliases(statement);
        changed |= normalizeSingleRowAggregateOrder(statement);
        changed |= new SequenceProjectionRule().rewrite(statement);
        changed |= scalar.rewrite(statement, resolver);
        changed |= functions.rewrite(statement);
        changed |= normalizeRowLimiting(statement);
        changed |= normalizeUpdateTarget(statement);
        changed |= normalizeNullOrderBy(statement);
        changed |= dual.rewrite(statement);
        changed |= listagg.rewrite(statement);
        changed |= apply.rewrite(statement);
        changed |= outerJoin.rewrite(statement);
        changed |= forUpdate.rewrite(statement);
        changed |= hierarchy.rewrite(statement);
        RownumRule.Result rownumResult = rownum.rewrite(statement);
        statement = rownumResult.statement();
        changed |= rownumResult.changed();
        changed |= merge.rewrite(statement);
        return new Result(
                statement, changed || sequenceResult.changed(), sequenceResult.renderedSql());
    }

    private static void validateInListLimit(Statement statement) throws TranslationException {
        for (InExpression expression : ParserAdapter.nodes(statement, InExpression.class)) {
            if (expression.getRightExpression() instanceof ExpressionList<?> values
                    && exceedsOracleExpressionLimit(expression.getLeftExpression(), values)) {
                throw new TranslationException(
                        "IN_LIST_LIMIT",
                        "ORA-01795: maximum number of expressions in a list is 1000");
            }
        }
    }

    private static boolean normalizeRowLimiting(Statement statement) throws TranslationException {
        boolean changed = false;
        for (Select select : ParserAdapter.nodes(statement, Select.class)) {
            if (select.getFetch() != null
                    && select.getFetch().getFetchParameters().stream()
                            .anyMatch("PERCENT"::equalsIgnoreCase))
                throw new TranslationException(
                        "FETCH_PERCENT",
                        "FETCH PERCENT requires counting the complete selected row set and is unsupported");
            if (select.getFetch() != null
                    && select.getFetch().getExpression() != null
                    && !select.getFetch().getFetchParameters().stream()
                            .anyMatch("PERCENT"::equalsIgnoreCase)) {
                select.getFetch()
                        .setExpression(
                                new Function(
                                        "orafit.row_count", select.getFetch().getExpression()));
                changed = true;
            }
            if (select.getOffset() == null || select.getOffset().getOffset() == null) continue;
            select.getOffset()
                    .setOffset(new Function("orafit.row_offset", select.getOffset().getOffset()));
            changed = true;
        }
        return changed;
    }

    private static boolean exceedsOracleExpressionLimit(
            Expression leftExpression, ExpressionList<?> values) {
        // JSqlParser represents both row-value tuples and redundantly parenthesized scalar values
        // as nested ExpressionLists.  Oracle's tuple form intentionally avoids the scalar
        // 1000-item limit, but IN ((?), (?), ...) with a scalar left hand side does not.
        if (leftExpression instanceof ExpressionList<?> leftTuple && leftTuple.size() > 1) {
            return values.stream()
                    .filter(ExpressionList.class::isInstance)
                    .map(ExpressionList.class::cast)
                    .anyMatch(set -> set.size() > 1000);
        }
        return values.size() > 1000;
    }

    private static boolean normalizeSingleRowAggregateOrder(Statement statement) {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            if (select.getOrderByElements() == null
                    || select.getOrderByElements().isEmpty()
                    || select.getGroupBy() != null
                    || select.getHaving() != null) continue;
            if (!SelectTrees.aggregateProjection(select)) continue;
            select.setOrderByElements(null);
            changed = true;
        }
        return changed;
    }

    private static boolean normalizeDuplicateJoinAliases(Statement statement)
            throws TranslationException {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            if (select.getJoins() == null || select.getJoins().isEmpty()) continue;
            java.util.Map<String, AliasOwner> owners = new java.util.HashMap<>();
            if (select.getFromItem() != null && select.getFromItem().getAlias() != null) {
                owners.put(
                        select.getFromItem()
                                .getAlias()
                                .getUnquotedName()
                                .toLowerCase(java.util.Locale.ROOT),
                        new AliasOwner(select.getFromItem(), List.of()));
            }
            int duplicate = 0;
            for (Join join : select.getJoins()) {
                if (join.getFromItem() == null || join.getFromItem().getAlias() == null) continue;
                String alias = join.getFromItem().getAlias().getUnquotedName();
                String key = alias.toLowerCase(java.util.Locale.ROOT);
                AliasOwner previous =
                        owners.put(
                                key,
                                new AliasOwner(
                                        join.getFromItem(),
                                        join.getOnExpressions() == null
                                                ? List.of()
                                                : List.copyOf(join.getOnExpressions())));
                if (previous == null) continue;
                String replacement = "__orafit_alias_" + (++duplicate);
                previous.fromItem().getAlias().setName(replacement);
                for (Expression on : previous.onExpressions()) renameAlias(on, alias, replacement);
                changed = true;
            }
        }
        return changed;
    }

    private record AliasOwner(FromItem fromItem, List<Expression> onExpressions) {}

    private static void renameAlias(Expression expression, String source, String replacement) {
        for (Column column : ParserAdapter.columns(expression)) {
            if (source.equalsIgnoreCase(column.getUnquotedTableName()))
                column.getTable().setName(replacement);
        }
    }

    private static boolean normalizeUpdateTarget(Statement statement) throws TranslationException {
        if (!(statement instanceof Update update)
                || update.getUpdateSets() == null
                || update.getTable() == null) return false;
        String target =
                update.getTable().getAlias() == null
                        ? update.getTable().getUnquotedName()
                        : update.getTable().getAlias().getUnquotedName();
        boolean changed = false;
        for (var set : update.getUpdateSets()) {
            for (var column : set.getColumns()) {
                String qualifier = column.getUnquotedTableName();
                if (qualifier == null || qualifier.isBlank()) continue;
                if (!qualifier.equalsIgnoreCase(target)) {
                    throw new TranslationException(
                            "UPDATE_TARGET",
                            "UPDATE SET target qualifier must name the update target relation");
                }
                column.setTable(null);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean normalizeNullOrderBy(Statement statement) {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            List<OrderByElement> order = select.getOrderByElements();
            if (order == null || order.isEmpty()) continue;
            if (!order.removeIf(element -> element.getExpression() instanceof NullValue)) continue;
            if (order.isEmpty()) select.setOrderByElements(null);
            changed = true;
        }
        return changed;
    }

    private static boolean normalizeDerivedAliases(Statement statement) {
        boolean changed = false;
        int inline = 0;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            if (select.getFromItem() instanceof ParenthesedSelect nested
                    && nested.getAlias() == null) {
                nested.setAlias(new Alias("__orafit_inline_" + (++inline), true));
                changed = true;
            }
            if (select.getJoins() != null) {
                for (var join : select.getJoins()) {
                    if (join.getFromItem() instanceof ParenthesedSelect nested
                            && nested.getAlias() == null) {
                        nested.setAlias(new Alias("__orafit_inline_" + (++inline), true));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static boolean rewriteUnique(Statement statement) {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            Distinct distinct = select.getDistinct();
            if (distinct != null && distinct.isUseUnique()) {
                distinct.setUseUnique(false);
                if (distinct.getOnSelectItems() == null) distinct.setOnSelectItems(List.of());
                changed = true;
            }
        }
        return changed;
    }

    private static boolean rewriteMinus(Statement statement) {
        boolean changed = false;
        for (SetOperationList set : ParserAdapter.nodes(statement, SetOperationList.class)) {
            List<SetOperation> operations = set.getOperations();
            if (operations == null) continue;
            for (int i = 0; i < operations.size(); i++) {
                if (operations.get(i) instanceof MinusOp minus) {
                    operations.set(i, new ExceptOp(minus.getModifier()));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Result of applying the ordered rewrite pipeline. */
    public record Result(Statement statement, boolean changed, String renderedSql) {}
}
