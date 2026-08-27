package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.ArrayConstructor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.select.WithSearchClause;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps verified hierarchy ordering to SEARCH DEPTH FIRST, sibling rank path, or output ORDER BY.
 */
final class HierarchyOrder {
    static final String SEQUENCE = "__orafit_sibling_order";
    private static final String LEVEL_SLOT = "__orafit_level";
    private static final String RANK = "__orafit_sibling_rank";
    private static final String RANK_PATH = "__orafit_sibling_path";
    private static final String RANK_BASE = "__orafit_hr";
    private static final String NORMALIZED_SOURCE = "__orafit_b_hsource";
    private static final HierarchyOrder NONE =
            new HierarchyOrder(null, null, Set.of(), List.of(), List.of(), null);

    private final String siblingColumn;
    private final List<OrderByElement> outputOrder;
    private final Set<String> outputAliases;
    private final List<String> requiredSourceColumns;
    private final List<SiblingKey> siblingKeys;
    private final String childLinkColumn;

    private HierarchyOrder(
            String siblingColumn,
            List<OrderByElement> outputOrder,
            Set<String> outputAliases,
            List<String> requiredSourceColumns,
            List<SiblingKey> siblingKeys,
            String childLinkColumn) {
        this.siblingColumn = siblingColumn;
        this.outputOrder = outputOrder == null ? null : List.copyOf(outputOrder);
        this.outputAliases = Set.copyOf(outputAliases);
        this.requiredSourceColumns = List.copyOf(requiredSourceColumns);
        this.siblingKeys = List.copyOf(siblingKeys);
        this.childLinkColumn = childLinkColumn;
    }

    static HierarchyOrder resolve(PlainSelect select, Table source, String aliasName)
            throws TranslationException {
        List<OrderByElement> elements = select.getOrderByElements();
        if (elements == null || elements.isEmpty()) return NONE;
        if (!select.isOracleSiblings()) {
            OutputAliases aliases = outputAliases(select);
            List<String> required =
                    validateOutputOrder(
                            elements, aliases, select.getSelectItems().size(), source, aliasName);
            if (required.size() > 1) {
                throw unsupported(
                        "ordinary hierarchy ORDER BY supports at most one unprojected source"
                                + " column in the bounded contract");
            }
            return new HierarchyOrder(null, elements, aliases.safe(), required, List.of(), null);
        }

        OutputAliases aliases = outputAliases(select);
        if (simpleSiblingOrder(elements, aliases, source, aliasName)) {
            Column column = (Column) elements.get(0).getExpression();
            return new HierarchyOrder(
                    column.getColumnName(), null, Set.of(), List.of(), List.of(), null);
        }

        List<SiblingKey> keys =
                siblingKeys(elements, select.getSelectItems(), aliases, source, aliasName);
        HierarchyPredicate predicate =
                HierarchyPredicate.resolve(
                        select.getOracleHierarchical().getConnectExpression(), source, aliasName);
        String childLink = predicate.childLinkColumn();
        if (childLink == null) {
            throw unsupported(
                    "advanced ORDER SIBLINGS BY requires a direct child parent-link column");
        }
        List<String> required =
                keys.stream().map(key -> key.expression().sourceColumn()).distinct().toList();
        return new HierarchyOrder(null, null, Set.of(), required, keys, childLink);
    }

    boolean present() {
        return siblingColumn != null || !requiredSourceColumns.isEmpty() || !siblingKeys.isEmpty();
    }

    String columnName() {
        if (siblingColumn != null) return siblingColumn;
        if (!requiredSourceColumns.isEmpty()) return requiredSourceColumns.get(0);
        return siblingKeys.get(0).expression().sourceColumn();
    }

    void apply(WithItem<?> tree, PlainSelect output, String aliasName) throws TranslationException {
        if (outputOrder != null) {
            Table finalTable = new Table(aliasName);
            for (OrderByElement element : outputOrder) {
                Expression expression = element.getExpression();
                if (!(expression instanceof Column column)) continue;
                if (unqualified(column) && outputAliases.contains(key(column))) continue;
                if (unqualified(column)
                        && "LEVEL".equalsIgnoreCase(column.getUnquotedColumnName())) {
                    element.setExpression(new Column(finalTable, LEVEL_SLOT));
                } else {
                    element.setExpression(new Column(finalTable, column.getColumnName()));
                }
            }
            output.setOrderByElements(outputOrder);
            output.setOracleSiblings(false);
            return;
        }
        if (!siblingKeys.isEmpty()) {
            applyRankPath(tree, output, aliasName);
            return;
        }
        if (siblingColumn == null) return;
        WithSearchClause search =
                new WithSearchClause(
                        WithSearchClause.SearchOrder.DEPTH,
                        new ExpressionList<>(new Column(siblingColumn)),
                        SEQUENCE);
        tree.setSearchClause(search);
        output.setOrderByElements(
                List.of(
                        new OrderByElement()
                                .withExpression(new Column(new Table(aliasName), SEQUENCE))));
        output.setOracleSiblings(false);
    }

    private void applyRankPath(WithItem<?> tree, PlainSelect output, String aliasName)
            throws TranslationException {
        if (!(tree.getParenthesedStatement() instanceof ParenthesedSelect body)
                || !(body.getSelect() instanceof SetOperationList union)
                || union.getSelects() == null
                || union.getSelects().size() != 2
                || !(union.getSelect(0) instanceof PlainSelect anchor)
                || !(union.getSelect(1) instanceof PlainSelect recursive)) {
            throw unsupported("generated hierarchy tree has an unexpected recursive shape");
        }

        Table anchorChild = sourceAlias(anchor.getFromItem());
        ArrayConstructor rootPath = new ArrayConstructor(rowNumber(anchorChild, false));
        rootPath.setArrayKeyword(true);
        anchor.addSelectItem(rootPath);

        FromItem recursiveFrom = recursive.getFromItem();
        if (!(recursiveFrom instanceof Table original)) {
            throw unsupported("generated recursive hierarchy source is not rankable");
        }
        String childAlias = aliasName(original);
        boolean normalized = NORMALIZED_SOURCE.equalsIgnoreCase(original.getUnquotedName());
        original.setAlias(new Alias(RANK_BASE, false));
        Table rankBase = new Table(RANK_BASE);

        PlainSelect rankedSource = new PlainSelect();
        rankedSource.addSelectItem(new AllTableColumns(rankBase));
        if (!normalized) {
            rankedSource.addSelectItem(
                    new Column(rankBase, "tableoid"), new Alias("tableoid", false));
            rankedSource.addSelectItem(new Column(rankBase, "ctid"), new Alias("ctid", false));
        }
        rankedSource.addSelectItem(rowNumber(rankBase, true), new Alias(RANK, false));
        rankedSource.setFromItem(original);
        ParenthesedSelect ranked =
                new ParenthesedSelect()
                        .withSelect(rankedSource)
                        .withAlias(new Alias(childAlias, false));
        recursive.setFromItem(ranked);

        if (recursive.getJoins() == null || recursive.getJoins().size() != 1) {
            throw unsupported("generated recursive hierarchy parent join is not rankable");
        }
        Table parent = sourceAlias(recursive.getJoins().get(0).getFromItem());
        recursive.addSelectItem(
                new Function(
                        "array_append",
                        new Column(parent, RANK_PATH),
                        new Column(new Table(childAlias), RANK)));

        tree.addWithItemList(SelectItem.from(new Column(RANK_PATH)));
        output.setOrderByElements(
                List.of(
                        new OrderByElement()
                                .withExpression(new Column(new Table(aliasName), RANK_PATH))));
        output.setOracleSiblings(false);
    }

    private AnalyticExpression rowNumber(Table table, boolean partition) {
        AnalyticExpression rank = new AnalyticExpression().withName("ROW_NUMBER");
        if (partition) {
            rank.setPartitionExpressionList(
                    new ExpressionList<>(new Column(table, childLinkColumn)));
        }
        rank.setOrderByElements(siblingKeys.stream().map(key -> key.render(table)).toList());
        return rank;
    }

    private static boolean simpleSiblingOrder(
            List<OrderByElement> elements, OutputAliases aliases, Table source, String aliasName)
            throws TranslationException {
        if (elements.size() != 1) return false;
        OrderByElement element = elements.get(0);
        if (!element.isAsc() || element.getNullOrdering() != null) return false;
        if (!(element.getExpression() instanceof Column column)) return false;
        if (unqualified(column) && aliases.all().contains(key(column))) return false;
        validateQualifier(column, source, aliasName);
        return true;
    }

    private static List<SiblingKey> siblingKeys(
            List<OrderByElement> elements,
            List<SelectItem<?>> selectItems,
            OutputAliases aliases,
            Table source,
            String aliasName)
            throws TranslationException {
        List<SiblingKey> result = new ArrayList<>(elements.size());
        for (OrderByElement element : elements) {
            Expression resolved =
                    resolveSiblingExpression(
                            element.getExpression(), selectItems, aliases, source, aliasName);
            result.add(
                    new SiblingKey(
                            SiblingExpression.resolve(resolved, source, aliasName),
                            element.isAsc(),
                            element.isAscDescPresent(),
                            element.getNullOrdering()));
        }
        return List.copyOf(result);
    }

    private static Expression resolveSiblingExpression(
            Expression expression,
            List<SelectItem<?>> selectItems,
            OutputAliases aliases,
            Table source,
            String aliasName)
            throws TranslationException {
        if (expression instanceof LongValue ordinal) {
            long position = ordinal.getValue();
            if (position < 1 || position > selectItems.size()) {
                throw unsupported("ORDER SIBLINGS BY ordinal is outside the SELECT list");
            }
            return selectItems.get((int) position - 1).getExpression();
        }
        if (expression instanceof Function function
                && function.getName() != null
                && (function.getName().equalsIgnoreCase("UPPER")
                        || function.getName().equalsIgnoreCase("LOWER"))
                && function.getParameters() != null
                && function.getParameters().size() == 1
                && function.getParameters().get(0) instanceof Column argument
                && unqualified(argument)) {
            String name = key(argument);
            if (aliases.duplicates().contains(name)) {
                throw unsupported("ORDER SIBLINGS BY SELECT alias is ambiguous: " + name);
            }
            if (aliases.all().contains(name)) {
                for (SelectItem<?> item : selectItems) {
                    if (item.getAlias() != null
                            && item.getAlias().getUnquotedName().equalsIgnoreCase(name)) {
                        if (!(item.getExpression() instanceof Column resolved)) {
                            throw unsupported(
                                    "function-wrapped ORDER SIBLINGS BY alias requires a direct"
                                            + " source-column SELECT alias");
                        }
                        return new Function(function.getName(), resolved);
                    }
                }
            }
            validateQualifier(argument, source, aliasName);
            return expression;
        }
        if (expression instanceof Column column && unqualified(column)) {
            String name = key(column);
            if (aliases.duplicates().contains(name)) {
                throw unsupported("ORDER SIBLINGS BY SELECT alias is ambiguous: " + name);
            }
            if (aliases.all().contains(name)) {
                for (SelectItem<?> item : selectItems) {
                    if (item.getAlias() != null
                            && item.getAlias().getUnquotedName().equalsIgnoreCase(name)) {
                        return item.getExpression();
                    }
                }
            }
        }
        if (expression instanceof Column column) {
            validateQualifier(column, source, aliasName);
            return expression;
        }
        throw unsupported(
                "ORDER SIBLINGS BY supports source columns, bounded SELECT aliases and ordinals");
    }

    private static List<String> validateOutputOrder(
            List<OrderByElement> elements,
            OutputAliases aliases,
            int projectionCount,
            Table source,
            String aliasName)
            throws TranslationException {
        Set<String> required = new LinkedHashSet<>();
        for (OrderByElement element : elements) {
            Expression expression = element.getExpression();
            if (expression instanceof LongValue ordinal) {
                if (ordinal.getValue() < 1 || ordinal.getValue() > projectionCount) {
                    throw unsupported("hierarchy ORDER BY ordinal is outside the SELECT list");
                }
                continue;
            }
            if (!(expression instanceof Column column)) {
                throw unsupported(
                        "ordinary hierarchy ORDER BY supports source columns, LEVEL, direct-column"
                                + " SELECT aliases and ordinals");
            }
            if (unqualified(column)) {
                String key = key(column);
                if (aliases.unsafe().contains(key)) {
                    throw unsupported(
                            "computed SELECT aliases are deferred because Oracle output metadata"
                                    + " cannot be preserved without type inference");
                }
                if (aliases.safe().contains(key)
                        || "LEVEL".equalsIgnoreCase(column.getUnquotedColumnName())) continue;
            }
            validateQualifier(column, source, aliasName);
            required.add(column.getColumnName());
        }
        return List.copyOf(required);
    }

    private static OutputAliases outputAliases(PlainSelect select) {
        Set<String> safe = new HashSet<>();
        Set<String> unsafe = new HashSet<>();
        Set<String> all = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (SelectItem<?> item : select.getSelectItems()) {
            if (item.getAlias() == null) continue;
            String name = item.getAlias().getUnquotedName().toLowerCase(Locale.ROOT);
            if (!all.add(name)) duplicates.add(name);
            if (metadataPreserving(item.getExpression())) safe.add(name);
            else unsafe.add(name);
        }
        return new OutputAliases(safe, unsafe, all, duplicates);
    }

    private static boolean metadataPreserving(Expression expression) {
        if (expression instanceof Column) return true;
        if (!(expression instanceof Function function)
                || function.getName() == null
                || !(function.getName().equalsIgnoreCase("UPPER")
                        || function.getName().equalsIgnoreCase("LOWER"))
                || function.getParameters() == null
                || function.getParameters().size() != 1) return false;
        return function.getParameters().get(0) instanceof Column;
    }

    private static boolean unqualified(Column column) {
        return column.getTableName() == null || column.getTableName().isBlank();
    }

    private static String key(Column column) {
        return column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
    }

    private static String aliasName(Table table) throws TranslationException {
        if (table.getAlias() == null || table.getAlias().getName() == null) {
            throw unsupported("generated hierarchy source lost its alias");
        }
        return table.getAlias().getName();
    }

    private static Table sourceAlias(FromItem item) throws TranslationException {
        if (item == null || item.getAlias() == null || item.getAlias().getName() == null) {
            throw unsupported("generated hierarchy source lost its alias");
        }
        return new Table(item.getAlias().getName());
    }

    private static void validateQualifier(Column column, Table source, String aliasName)
            throws TranslationException {
        String qualifier = HierarchySupport.invalidQualifier(column, source, aliasName);
        if (qualifier != null)
            throw unsupported(
                    "hierarchy ORDER BY column is outside the CONNECT BY source: " + qualifier);
    }

    private static TranslationException unsupported(String message) {
        return HierarchySupport.fail("CONNECT_BY_ORDER", message);
    }

    private sealed interface SiblingExpression permits SiblingColumn, SiblingFunction {
        Expression render(Table table);

        String sourceColumn();

        static SiblingExpression resolve(Expression expression, Table source, String aliasName)
                throws TranslationException {
            if (expression instanceof Column column) {
                validateQualifier(column, source, aliasName);
                return new SiblingColumn(column.getColumnName());
            }
            if (expression instanceof Function function
                    && function.getName() != null
                    && (function.getName().equalsIgnoreCase("UPPER")
                            || function.getName().equalsIgnoreCase("LOWER"))
                    && function.getParameters() != null
                    && function.getParameters().size() == 1
                    && function.getParameters().get(0) instanceof Column column) {
                validateQualifier(column, source, aliasName);
                return new SiblingFunction(function.getName(), column.getColumnName());
            }
            throw unsupported(
                    "ORDER SIBLINGS BY alias/ordinal expression is outside the bounded"
                            + " Column/UPPER/LOWER subset");
        }
    }

    private record SiblingColumn(String name) implements SiblingExpression {
        @Override
        public Expression render(Table table) {
            return new Column(table, name);
        }

        @Override
        public String sourceColumn() {
            return name;
        }
    }

    private record SiblingFunction(String name, String column) implements SiblingExpression {
        @Override
        public Expression render(Table table) {
            return new Function(name, new Column(table, column));
        }

        @Override
        public String sourceColumn() {
            return column;
        }
    }

    private record SiblingKey(
            SiblingExpression expression,
            boolean asc,
            boolean ascDescPresent,
            OrderByElement.NullOrdering nullOrdering) {
        OrderByElement render(Table table) {
            return new OrderByElement()
                    .withExpression(expression.render(table))
                    .withAsc(asc)
                    .withAscDescPresent(ascDescPresent)
                    .withNullOrdering(nullOrdering);
        }
    }

    private record OutputAliases(
            Set<String> safe, Set<String> unsafe, Set<String> all, Set<String> duplicates) {}
}
