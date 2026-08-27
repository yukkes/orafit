package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.rewrite.SelectTrees;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.ArrayConstructor;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.OracleHierarchicalExpression;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded CONNECT BY lowering over one stable logical source and hierarchy state. */
public final class HierarchyLoweringRule {
    private static final String TREE = "__orafit_h";
    private static final String CHILD = "__orafit_hc";
    private static final String PROBE = "__orafit_hq";
    private static final String PARENT = "__orafit_hp";
    private static final String LEVEL = "__orafit_level";
    private static final String PATH = "__orafit_path";
    private static final String CYCLE = "__orafit_cycle";
    private static final String GUARD = "__orafit_guard";
    private static final String ROW_ID = "__orafit_row_identity";
    private static final String WILDCARD_SOURCE = "__orafit_hw";

    public boolean rewrite(Statement statement) throws TranslationException {
        if (!(statement instanceof PlainSelect select) || select.getOracleHierarchical() == null)
            return false;
        OracleHierarchicalExpression hierarchy = select.getOracleHierarchical();
        validate(select, hierarchy);
        HierarchySource hierarchySource = HierarchySource.resolve(select);
        boolean physicalWildcard = expandWildcardProjection(select, hierarchySource);
        Table source = hierarchySource.logicalTable();
        String finalAlias = hierarchySource.aliasName();
        HierarchyPredicate predicate =
                HierarchyPredicate.resolve(hierarchy.getConnectExpression(), source, finalAlias);
        Expression residualWhere = select.getWhere();
        HierarchyOrder order = HierarchyOrder.resolve(select, source, finalAlias);
        HierarchyRoot roots = HierarchyRoot.resolve(select, source, finalAlias);
        HierarchyPath valuePaths = HierarchyPath.resolve(select, source, finalAlias);
        HierarchyLeaf leaves = HierarchyLeaf.resolve(select);
        Map<String, String> columns = columns(select, hierarchy, source, finalAlias, order);

        PlainSelect anchor =
                anchor(
                        hierarchySource,
                        source,
                        hierarchy.getStartExpression(),
                        columns,
                        finalAlias,
                        roots,
                        valuePaths,
                        physicalWildcard);
        PlainSelect recursive =
                recursive(hierarchySource, predicate, columns, roots, valuePaths, physicalWildcard);
        SetOperationList union =
                new SetOperationList()
                        .withSelects(List.of(anchor, recursive))
                        .withOperations(List.of(new UnionOp().withAll(true)));
        ParenthesedSelect body = new ParenthesedSelect().withSelect(union);

        WithItem<ParenthesedSelect> tree = new WithItem<>();
        tree.setAlias(new Alias(TREE, false));
        tree.setWithItemList(cteColumns(columns, roots, valuePaths, physicalWildcard));
        tree.setParenthesedStatement(body);
        WithItem<?> guard = hierarchy.isNoCycle() ? null : guard();

        rewriteProjection(select, source, finalAlias);
        roots.rewriteProjection(select, finalAlias);
        valuePaths.rewriteProjection(select, finalAlias);
        if (leaves.present()) {
            Table probeSource = hierarchySource.scan(PROBE);
            Table probe = new Table(PROBE);
            Table current = new Table(finalAlias);
            Expression childMatch = predicate.render(probe, current);
            leaves.rewriteProjection(
                    select,
                    probeSource,
                    probe,
                    current,
                    childMatch,
                    hierarchySource.rowIdentity(probe),
                    PATH);
        }
        select.setOracleHierarchical(null);
        select.setFromItem(alias(new Table(TREE), finalAlias));
        Expression cycleFilter = new NotExpression(new Column(new Table(finalAlias), CYCLE));
        select.setWhere(
                HierarchyFilter.output(residualWhere, cycleFilter, source, finalAlias, LEVEL));
        select.setWithItemsList(hierarchySource.withItems(tree, guard));
        select.setOrderByElements(null);
        select.setOracleSiblings(false);
        order.apply(tree, select, finalAlias);
        List<Join> finalJoins = new ArrayList<>(2);
        if (physicalWildcard) {
            Table wildcardSource = hierarchySource.scan(WILDCARD_SOURCE);
            Table wildcard = new Table(WILDCARD_SOURCE);
            Join wildcardJoin = new Join().withInner(true).setFromItem(wildcardSource);
            wildcardJoin.addOnExpression(
                    new EqualsTo(
                            hierarchySource.rowIdentity(wildcard),
                            new Column(new Table(finalAlias), ROW_ID)));
            finalJoins.add(wildcardJoin);
        }
        if (guard != null) {
            Join guardJoin = new Join().withCross(true).setFromItem(new Table(GUARD));
            finalJoins.add(guardJoin);
        }
        if (!finalJoins.isEmpty()) select.setJoins(finalJoins);
        return true;
    }

    private static void validate(PlainSelect select, OracleHierarchicalExpression hierarchy)
            throws TranslationException {
        if (select.getJoins() != null && !select.getJoins().isEmpty()) {
            throw unsupported(
                    "CONNECT_BY_SOURCE",
                    "top-level CONNECT BY joins are deferred; put the join in a derived source");
        }
        if (select.getDistinct() != null
                || select.getGroupBy() != null
                || select.getHaving() != null) {
            throw unsupported(
                    "CONNECT_BY_GROUP",
                    "The bounded CONNECT BY contract excludes DISTINCT/GROUP BY/HAVING");
        }
        rejectAggregateOrWindow(select);
        if (select.getLimit() != null || select.getOffset() != null || select.getFetch() != null) {
            throw unsupported(
                    "CONNECT_BY_ROW_LIMIT",
                    "The bounded CONNECT BY contract excludes LIMIT/OFFSET/FETCH");
        }
        if (select.getForMode() != null) {
            throw unsupported(
                    "CONNECT_BY_FOR_UPDATE", "The bounded CONNECT BY contract excludes FOR UPDATE");
        }
        if (hierarchy.getStartExpression() == null) {
            throw unsupported(
                    "CONNECT_BY_START", "The bounded CONNECT BY contract requires START WITH");
        }
        rejectHierarchyState(hierarchy.getStartExpression(), "START WITH");
        rejectHierarchyState(hierarchy.getConnectExpression(), "CONNECT BY");
        for (SelectItem<?> item : select.getSelectItems()) {
            List<Column> levels =
                    ParserAdapter.columns(item.getExpression()).stream()
                            .filter(HierarchyLoweringRule::isLevel)
                            .toList();
            if (!levels.isEmpty()
                    && !(item.getExpression() instanceof Column column && isLevel(column))) {
                throw unsupported(
                        "CONNECT_BY_LEVEL",
                        "LEVEL is initially supported only as a direct SELECT item");
            }
        }
    }

    private static boolean expandWildcardProjection(PlainSelect select, HierarchySource source)
            throws TranslationException {
        List<SelectItem<?>> items = select.getSelectItems();
        boolean found = false;
        for (SelectItem<?> item : items) {
            if (item.getExpression() instanceof AllColumns) {
                found = true;
                break;
            }
        }
        if (!found) return false;

        boolean physical = source.physical();
        if (!physical && source.outputColumns().isEmpty()) {
            throw unsupported(
                    "CONNECT_BY_PROJECTION",
                    "SELECT * requires an explicit derived/CTE source shape; physical schemas are"
                            + " not queried during translation");
        }

        List<SelectItem<?>> expanded = new ArrayList<>();
        Table output = new Table(source.aliasName());
        for (SelectItem<?> item : items) {
            Expression expression = item.getExpression();
            if (!(expression instanceof AllColumns wildcard)) {
                expanded.add(item);
                continue;
            }
            if ((wildcard.getExceptColumns() != null && !wildcard.getExceptColumns().isEmpty())
                    || (wildcard.getReplaceExpressions() != null
                            && !wildcard.getReplaceExpressions().isEmpty())) {
                throw unsupported(
                        "CONNECT_BY_PROJECTION",
                        "hierarchy wildcard EXCEPT/REPLACE is outside the bounded contract");
            }
            if (wildcard instanceof AllTableColumns tableWildcard) {
                Column probe = new Column(tableWildcard.getTable(), "__orafit_probe");
                String invalid =
                        HierarchySupport.invalidQualifier(
                                probe, source.logicalTable(), source.aliasName());
                if (invalid != null) {
                    throw unsupported(
                            "CONNECT_BY_PROJECTION",
                            "hierarchy wildcard qualifier is outside the source: " + invalid);
                }
            }
            if (physical) {
                expanded.add(SelectItem.from(new AllTableColumns(new Table(WILDCARD_SOURCE))));
            } else {
                for (String column : source.outputColumns()) {
                    expanded.add(SelectItem.from(new Column(new Table(output.getName()), column)));
                }
            }
        }
        items.clear();
        items.addAll(expanded);
        return physical;
    }

    private static void rejectAggregateOrWindow(PlainSelect select) throws TranslationException {
        if (SelectTrees.containsAggregateOrWindow(select)) {
            throw unsupported(
                    "CONNECT_BY_AGGREGATE",
                    "CONNECT BY aggregate/window projections are outside the verified bounded"
                            + " subset");
        }
    }

    private static Map<String, String> columns(
            PlainSelect select,
            OracleHierarchicalExpression hierarchy,
            Table source,
            String aliasName,
            HierarchyOrder order)
            throws TranslationException {
        Map<String, String> result = new LinkedHashMap<>();
        for (SelectItem<?> item : select.getSelectItems())
            collect(result, item.getExpression(), source, aliasName);
        collect(result, select.getWhere(), source, aliasName);
        collect(result, hierarchy.getStartExpression(), source, aliasName);
        collect(result, hierarchy.getConnectExpression(), source, aliasName);
        if (order.present()) collect(result, new Column(order.columnName()), source, aliasName);
        if (result.isEmpty())
            throw unsupported("CONNECT_BY_COLUMNS", "CONNECT BY did not expose any source columns");
        return result;
    }

    private static void collect(
            Map<String, String> columns, Expression expression, Table source, String aliasName)
            throws TranslationException {
        for (Column column : ParserAdapter.columns(expression)) {
            if (isLevel(column)
                    || isRownum(column)
                    || HierarchyLeaf.isLeaf(column)
                    || HierarchyLeaf.isCycle(column)) continue;
            validateQualifier(column, source, aliasName);
            String sql = column.getColumnName();
            String key = column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
            if (internalColumn(key)) {
                throw unsupported(
                        "CONNECT_BY_COLUMN",
                        "source column collides with Orafit hierarchy state: " + sql);
            }
            String previous = columns.putIfAbsent(key, sql);
            if (previous != null && !previous.equals(sql)) {
                throw unsupported(
                        "CONNECT_BY_COLUMN",
                        "quoted/case-distinct hierarchy columns are outside the bounded CONNECT BY"
                                + " contract");
            }
        }
    }

    private static PlainSelect anchor(
            HierarchySource hierarchySource,
            Table source,
            Expression start,
            Map<String, String> columns,
            String aliasName,
            HierarchyRoot roots,
            HierarchyPath valuePaths,
            boolean physicalWildcard)
            throws TranslationException {
        Table childSource = hierarchySource.scan(CHILD);
        Table child = new Table(CHILD);
        PlainSelect select = new PlainSelect();
        for (String column : columns.values()) select.addSelectItem(new Column(child, column));
        if (physicalWildcard) select.addSelectItem(hierarchySource.rowIdentity(child));
        select.addSelectItem(new net.sf.jsqlparser.expression.LongValue(1));
        ArrayConstructor path = new ArrayConstructor(hierarchySource.rowIdentity(child));
        path.setArrayKeyword(true);
        select.addSelectItem(path);
        select.addSelectItem(new BooleanValue(false));
        roots.appendAnchor(select, child);
        valuePaths.appendAnchor(select, child);
        select.setFromItem(childSource);
        qualify(start, child, source, aliasName);
        select.setWhere(start);
        return select;
    }

    private static PlainSelect recursive(
            HierarchySource hierarchySource,
            HierarchyPredicate predicate,
            Map<String, String> columns,
            HierarchyRoot roots,
            HierarchyPath valuePaths,
            boolean physicalWildcard) {
        Table childSource = hierarchySource.scan(CHILD);
        Table child = new Table(CHILD);
        Table parentSource = new Table(TREE);
        parentSource.setAlias(new Alias(PARENT, false));
        Table parent = new Table(PARENT);
        PlainSelect select = new PlainSelect();
        for (String column : columns.values()) select.addSelectItem(new Column(child, column));
        if (physicalWildcard) select.addSelectItem(hierarchySource.rowIdentity(child));
        select.addSelectItem(
                new Addition(
                        new Column(parent, LEVEL), new net.sf.jsqlparser.expression.LongValue(1)));
        select.addSelectItem(
                new Function(
                        "array_append",
                        new Column(parent, PATH),
                        hierarchySource.rowIdentity(child)));
        select.addSelectItem(cycle(hierarchySource, parent, child));
        roots.appendRecursive(select, parent);
        valuePaths.appendRecursive(select, child, parent);
        select.setFromItem(childSource);
        Join join = new Join().withInner(true).setFromItem(parentSource);
        join.addOnExpression(predicate.render(child, parent));
        select.setJoins(List.of(join));
        select.setWhere(new NotExpression(new Column(parent, CYCLE)));
        return select;
    }

    private static WithItem<ParenthesedSelect> guard() {
        Table tree = new Table(TREE);
        Function anyCycle = new Function("bool_or", new Column(tree, CYCLE));
        Function safeCycle = new Function("COALESCE", anyCycle, new BooleanValue(false));
        Function check = new Function("orafit.connect_by_cycle_guard", safeCycle);
        PlainSelect select = new PlainSelect();
        select.addSelectItem(check, new Alias("ok", true));
        select.setFromItem(tree);
        WithItem<ParenthesedSelect> item = new WithItem<>();
        item.setAlias(new Alias(GUARD, false));
        item.setParenthesedStatement(new ParenthesedSelect().withSelect(select));
        return item;
    }

    private static List<SelectItem<?>> cteColumns(
            Map<String, String> columns,
            HierarchyRoot roots,
            HierarchyPath valuePaths,
            boolean physicalWildcard) {
        List<SelectItem<?>> result = new ArrayList<>();
        for (String column : columns.values()) result.add(SelectItem.from(new Column(column)));
        if (physicalWildcard) result.add(SelectItem.from(new Column(ROW_ID)));
        result.add(SelectItem.from(new Column(LEVEL)));
        result.add(SelectItem.from(new Column(PATH)));
        result.add(SelectItem.from(new Column(CYCLE)));
        roots.appendCteColumns(result);
        valuePaths.appendCteColumns(result);
        return result;
    }

    private static void rewriteProjection(PlainSelect select, Table source, String aliasName)
            throws TranslationException {
        Table finalTable = new Table(aliasName);
        List<SelectItem<?>> items = select.getSelectItems();
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            if (item.getExpression() instanceof Column column && isLevel(column)) {
                Alias alias = item.getAlias() == null ? new Alias("LEVEL", true) : item.getAlias();
                items.set(i, SelectItem.from(new Column(finalTable, LEVEL), alias));
                continue;
            }
            if (item.getExpression() instanceof Column column && HierarchyLeaf.isLeaf(column))
                continue;
            for (Column column : ParserAdapter.columns(item.getExpression())) {
                if (HierarchyLeaf.isLeaf(column) || HierarchyLeaf.isCycle(column)) continue;
                validateQualifier(column, source, aliasName);
                column.setTable(new Table(aliasName));
            }
        }
    }

    private static Expression cycle(HierarchySource hierarchySource, Table parent, Table child) {
        Function position =
                new Function(
                        "array_position",
                        new Column(parent, PATH),
                        hierarchySource.rowIdentity(child));
        return new IsNullExpression(position).withNot(true);
    }

    private static void qualify(Expression expression, Table target, Table source, String aliasName)
            throws TranslationException {
        for (Column column : ParserAdapter.columns(expression)) {
            if (isLevel(column))
                throw unsupported("CONNECT_BY_STATE", "LEVEL is not allowed in START WITH");
            validateQualifier(column, source, aliasName);
            column.setTable(new Table(target.getName()));
        }
    }

    private static void rejectHierarchyState(Expression expression, String context)
            throws TranslationException {
        for (Column column : ParserAdapter.columns(expression)) {
            if (isLevel(column)
                    || column.getUnquotedColumnName()
                            .toUpperCase(Locale.ROOT)
                            .startsWith("CONNECT_BY_")) {
                throw unsupported(
                        "CONNECT_BY_STATE", context + " cannot depend on hierarchy output state");
            }
        }
    }

    private static void validateQualifier(Column column, Table source, String aliasName)
            throws TranslationException {
        String qualifier = HierarchySupport.invalidQualifier(column, source, aliasName);
        if (qualifier != null) {
            throw unsupported(
                    "CONNECT_BY_COLUMN",
                    "column qualifier is outside the CONNECT BY source: " + qualifier);
        }
    }

    private static boolean internalColumn(String key) {
        return key.equals(LEVEL)
                || key.equals(PATH)
                || key.equals(CYCLE)
                || key.equals(ROW_ID)
                || key.equals(HierarchyOrder.SEQUENCE)
                || key.startsWith(HierarchyRoot.PREFIX)
                || key.startsWith(HierarchyPath.PREFIX);
    }

    private static boolean isRownum(Column column) {
        return (column.getTableName() == null || column.getTableName().isBlank())
                && "ROWNUM".equalsIgnoreCase(column.getUnquotedColumnName());
    }

    private static Table alias(Table table, String aliasName) {
        table.setAlias(new Alias(aliasName, false));
        return table;
    }

    private static boolean isLevel(Column column) {
        return HierarchySupport.unqualified(column, "LEVEL");
    }

    private static TranslationException unsupported(String code, String message) {
        return HierarchySupport.fail(code, message);
    }
}
