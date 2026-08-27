package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Provides one stable row source, explicit shape and identity to the recursive hierarchy engine.
 */
final class HierarchySource {
    private static final String SOURCE = "__orafit_b_hsource";
    private static final String BASE = "__orafit_b_hbase";
    private static final String ROW_ID = "__orafit_b_hrowid";
    private static final String GENERATED_INLINE_ALIAS = "__orafit_inline_";

    private final Table logical;
    private final String aliasName;
    private final Table physical;
    private final List<WithItem<?>> prefix;
    private final List<String> outputColumns;

    private HierarchySource(
            Table logical,
            String aliasName,
            Table physical,
            List<WithItem<?>> prefix,
            List<String> outputColumns) {
        this.logical = logical;
        this.aliasName = aliasName;
        this.physical = physical;
        this.prefix = List.copyOf(prefix);
        this.outputColumns = List.copyOf(outputColumns);
    }

    static HierarchySource resolve(PlainSelect select) throws TranslationException {
        FromItem from = select.getFromItem();
        List<WithItem<?>> existing = select.getWithItemsList();
        boolean hasWith = existing != null && !existing.isEmpty();

        if (from instanceof Table table) {
            String aliasName = aliasName(table);
            WithItem<?> cte = hasWith ? matchingCte(table, existing) : null;
            if (cte == null) {
                return new HierarchySource(
                        table, aliasName, table, hasWith ? existing : List.of(), List.of());
            }
            List<String> outputColumns = cteShape(cte);
            return normalized(
                    table, logicalTable(table, aliasName), aliasName, existing, outputColumns);
        }
        if (!(from instanceof ParenthesedSelect derived)) {
            throw unsupported("CONNECT BY source must be a table, CTE, or derived SELECT");
        }

        String aliasName = aliasName(derived);
        if (aliasName == null
                || aliasName.isBlank()
                || aliasName.toLowerCase(Locale.ROOT).startsWith(GENERATED_INLINE_ALIAS)) {
            throw unsupported("derived CONNECT BY source requires an explicit application alias");
        }
        List<String> outputColumns = selectShape(derived.getSelect());
        return normalized(
                derived,
                new Table(aliasName),
                aliasName,
                hasWith ? existing : List.of(),
                outputColumns);
    }

    Table logicalTable() {
        return logical;
    }

    String aliasName() {
        return aliasName;
    }

    List<String> outputColumns() {
        return outputColumns;
    }

    boolean physical() {
        return physical != null;
    }

    Table scan(String alias) {
        Table table =
                physical == null ? new Table(SOURCE) : new Table(physical.getFullyQualifiedName());
        table.setAlias(new Alias(alias, false));
        return table;
    }

    Expression rowIdentity(Table alias) {
        if (physical == null) {
            return new CastExpression("CAST", new Column(alias, ROW_ID), "TEXT");
        }
        Function row =
                new Function("ROW", new Column(alias, "tableoid"), new Column(alias, "ctid"));
        return new CastExpression("CAST", row, "TEXT");
    }

    List<WithItem<?>> withItems(WithItem<?> tree, WithItem<?> guard) {
        List<WithItem<?>> result = new ArrayList<>(prefix.size() + 2);
        result.addAll(prefix);
        result.add(tree);
        if (guard != null) result.add(guard);
        if (prefix.isEmpty()) {
            tree.setRecursive(true);
        } else {
            for (WithItem<?> item : result) item.setRecursive(false);
            result.get(0).setRecursive(true);
        }
        return result;
    }

    private static HierarchySource normalized(
            FromItem from,
            Table logical,
            String aliasName,
            List<WithItem<?>> existing,
            List<String> outputColumns) {
        FromItem base = normalizedBase(from);
        PlainSelect materialized = new PlainSelect();
        materialized.addSelectItem(new AllTableColumns(new Table(BASE)));
        AnalyticExpression rowNumber = new AnalyticExpression().withName("ROW_NUMBER");
        materialized.addSelectItem(rowNumber, new Alias(ROW_ID, false));
        materialized.setFromItem(base);

        WithItem<ParenthesedSelect> sourceItem = new WithItem<>();
        sourceItem.setAlias(new Alias(SOURCE, false));
        sourceItem.setMaterialized(true);
        sourceItem.setParenthesedStatement(new ParenthesedSelect().withSelect(materialized));

        List<WithItem<?>> prefix = new ArrayList<>(existing.size() + 1);
        prefix.addAll(existing);
        prefix.add(sourceItem);
        return new HierarchySource(logical, aliasName, null, prefix, outputColumns);
    }

    private static WithItem<?> matchingCte(Table table, List<WithItem<?>> withItems) {
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) return null;
        String name = table.getUnquotedName();
        for (WithItem<?> item : withItems) {
            if (item.getUnquotedAliasName() != null
                    && item.getUnquotedAliasName().equalsIgnoreCase(name)) return item;
        }
        return null;
    }

    private static List<String> cteShape(WithItem<?> cte) throws TranslationException {
        if (cte.getWithItemList() != null && !cte.getWithItemList().isEmpty()) {
            return explicitColumns(cte.getWithItemList());
        }
        if (!(cte.getParenthesedStatement() instanceof ParenthesedSelect select)) {
            throw unsupported("CONNECT BY CTE source must be a SELECT");
        }
        return selectShape(select.getSelect());
    }

    private static List<String> selectShape(Select select) throws TranslationException {
        if (!(select instanceof PlainSelect plain)) {
            throw unsupported("CONNECT BY derived source requires one explicit SELECT shape");
        }
        return explicitColumns(plain.getSelectItems());
    }

    private static List<String> explicitColumns(List<SelectItem<?>> items)
            throws TranslationException {
        Set<String> names = new HashSet<>();
        List<String> result = new ArrayList<>(items.size());
        for (SelectItem<?> item : items) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                throw unsupported("CONNECT BY derived source excludes wildcard output shape");
            }
            String name;
            String key;
            if (item.getAlias() != null) {
                name = item.getAlias().getName();
                key = item.getAlias().getUnquotedName();
            } else if (expression instanceof Column column) {
                name = column.getColumnName();
                key = column.getUnquotedColumnName();
            } else {
                throw unsupported("CONNECT BY derived expressions require explicit output aliases");
            }
            if (!names.add(key.toLowerCase(Locale.ROOT))) {
                throw unsupported("CONNECT BY derived source has duplicate output column: " + key);
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private static FromItem normalizedBase(FromItem from) {
        if (from instanceof Table table) {
            Table copy = new Table(table.getFullyQualifiedName());
            copy.setAlias(new Alias(BASE, false));
            return copy;
        }
        ParenthesedSelect select = (ParenthesedSelect) from;
        select.setAlias(new Alias(BASE, false));
        return select;
    }

    private static Table logicalTable(Table table, String aliasName) {
        Table logical = new Table(table.getFullyQualifiedName());
        if (!aliasName.equalsIgnoreCase(table.getUnquotedName())) {
            logical.setAlias(new Alias(aliasName, false));
        }
        return logical;
    }

    private static String aliasName(FromItem from) {
        if (from.getAlias() != null) return from.getAlias().getName();
        return from instanceof Table table ? table.getName() : null;
    }

    private static TranslationException unsupported(String message) {
        return HierarchySupport.fail("CONNECT_BY_SOURCE", message);
    }
}
