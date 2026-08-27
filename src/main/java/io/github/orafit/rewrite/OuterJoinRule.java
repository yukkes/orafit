package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.OldOracleJoinBinaryExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative Oracle {@code (+)} to ANSI LEFT JOIN rewrite on one parsed AST. */
final class OuterJoinRule {
    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        for (PlainSelect select : SelectTrees.plain(statement)) {
            if (containsPlus(select.getWhere())) changed |= rewriteBlock(select);
        }
        return changed;
    }

    private static boolean rewriteBlock(PlainSelect block) throws TranslationException {
        if (block.getJoins() != null
                && block.getJoins().stream().anyMatch(join -> !join.isSimple())) {
            throw unsupported(
                    "OUTER_JOIN_MIXED", "ANSI JOIN and Oracle (+) cannot be mixed safely");
        }

        List<FromItem> items = new ArrayList<>();
        if (block.getFromItem() == null)
            throw unsupported("OUTER_JOIN_FROM", "Oracle (+) requires a FROM relation");
        items.add(block.getFromItem());
        if (block.getJoins() != null)
            block.getJoins().forEach(join -> items.add(join.getFromItem()));

        Map<String, FromItem> byAlias = new LinkedHashMap<>();
        for (FromItem item : items) {
            String name = alias(item);
            if (byAlias.put(name, item) != null) {
                throw unsupported("OUTER_JOIN_ALIAS", "Oracle (+) relation aliases must be unique");
            }
        }

        List<Expression> retained = new ArrayList<>();
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (Expression predicate : SelectTrees.andTerms(block.getWhere())) {
            if (!containsPlus(predicate)) {
                retained.add(predicate);
                continue;
            }
            if (predicate instanceof OrExpression) {
                throw unsupported("OUTER_JOIN_OR", "Oracle (+) below OR is not supported");
            }

            Scan scan = scan(predicate);
            if (scan.marked.size() != 1) {
                throw unsupported(
                        "OUTER_JOIN_OPTIONAL_SIDE",
                        "Oracle (+) must identify exactly one optional relation");
            }
            String optional = scan.marked.iterator().next();
            if (!byAlias.containsKey(optional)) {
                throw unsupported(
                        "OUTER_JOIN_ALIAS", "Oracle (+) references an unknown optional relation");
            }

            scan.clear(predicate);
            Set<String> mandatory = new LinkedHashSet<>(scan.qualified);
            mandatory.remove(optional);
            if (mandatory.size() > 1) {
                throw unsupported(
                        "OUTER_JOIN_MULTIPLE_PARENT",
                        "One Oracle (+) predicate cannot reference multiple mandatory relations");
            }

            Edge edge = edges.computeIfAbsent(optional, Edge::new);
            if (mandatory.isEmpty()) {
                if (scan.unqualified) {
                    throw unsupported(
                            "OUTER_JOIN_FILTER_QUALIFICATION",
                            "Optional-side (+) filters must use qualified columns");
                }
                edge.conditions.add(predicate);
                continue;
            }

            String parent = mandatory.iterator().next();
            if (!byAlias.containsKey(parent)) {
                throw unsupported(
                        "OUTER_JOIN_ALIAS", "Oracle (+) references an unknown mandatory relation");
            }
            edge.parents.add(parent);
            edge.conditions.add(predicate);
        }

        if (edges.isEmpty())
            throw unsupported("OUTER_JOIN_EDGE", "Oracle (+) produced no join edge");
        for (Edge edge : edges.values()) {
            if (edge.parents.isEmpty()) {
                throw unsupported(
                        "OUTER_JOIN_FILTER_PARENT",
                        "Optional-side (+) filter has no parent join predicate");
            }
        }

        Set<String> optional = edges.keySet();
        List<FromItem> roots = new ArrayList<>();
        for (FromItem item : items) if (!optional.contains(alias(item))) roots.add(item);
        if (roots.isEmpty())
            throw unsupported("OUTER_JOIN_CYCLE", "Oracle (+) join graph contains a cycle");

        block.setFromItem(roots.get(0));
        List<Join> joins = new ArrayList<>();
        Set<String> joined = new LinkedHashSet<>();
        joined.add(alias(roots.get(0)));
        for (int i = 1; i < roots.size(); i++) {
            Join cross = new Join();
            cross.setCross(true);
            cross.setFromItem(roots.get(i));
            joins.add(cross);
            joined.add(alias(roots.get(i)));
        }

        Set<String> pending = new LinkedHashSet<>(edges.keySet());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (String name : new ArrayList<>(pending)) {
                Edge edge = edges.get(name);
                if (!joined.containsAll(edge.parents)) continue;
                Join left = new Join();
                left.setLeft(true);
                left.setFromItem(byAlias.get(edge.optional));
                left.setOnExpressions(List.of(SelectTrees.and(edge.conditions)));
                joins.add(left);
                joined.add(name);
                pending.remove(name);
                progressed = true;
            }
            if (!progressed) {
                throw unsupported("OUTER_JOIN_CYCLE", "Oracle (+) join graph contains a cycle");
            }
        }

        block.setJoins(joins.isEmpty() ? null : joins);
        block.setWhere(SelectTrees.and(retained));
        return true;
    }

    private static String alias(FromItem item) throws TranslationException {
        if (item == null)
            throw unsupported("OUTER_JOIN_FROM", "Oracle (+) requires a FROM relation");
        if (item.getAlias() != null)
            return item.getAlias().getUnquotedName().toLowerCase(Locale.ROOT);
        if (item instanceof Table table) return table.getUnquotedName().toLowerCase(Locale.ROOT);
        throw unsupported(
                "OUTER_JOIN_ALIAS_REQUIRED", "Derived Oracle (+) relations require an alias");
    }

    private static boolean containsPlus(Expression expression) {
        return expression != null && scan(expression).plus > 0;
    }

    private static Scan scan(Expression expression) {
        Scan scan = new Scan();
        for (Column column : ParserAdapter.columns(expression)) scan.inspect(column);
        for (OldOracleJoinBinaryExpression value :
                ParserAdapter.nodes(expression, OldOracleJoinBinaryExpression.class))
            scan.inspect(value);
        return scan;
    }

    private static final class Scan {
        int plus;
        boolean unqualified;
        final Set<String> marked = new LinkedHashSet<>();
        final Set<String> qualified = new LinkedHashSet<>();

        private void inspect(Column column) {
            String table = column.getUnquotedTableName();
            if (table == null || table.isBlank()) {
                unqualified = true;
                return;
            }
            table = table.toLowerCase(Locale.ROOT);
            qualified.add(table);
            if (column.getOldOracleJoinSyntax() != 0) {
                plus++;
                marked.add(table);
            }
        }

        private void inspect(OldOracleJoinBinaryExpression expression) {
            int marker = expression.getOldOracleJoinSyntax();
            if (marker == 0) return;
            plus++;
            Expression side =
                    marker == 1 ? expression.getLeftExpression() : expression.getRightExpression();
            for (Column column : ParserAdapter.columns(side)) {
                String table = column.getUnquotedTableName();
                if (table != null && !table.isBlank()) marked.add(table.toLowerCase(Locale.ROOT));
            }
        }

        void clear(Expression expression) {
            for (Column column : ParserAdapter.columns(expression))
                column.setOldOracleJoinSyntax(0);
            for (OldOracleJoinBinaryExpression value :
                    ParserAdapter.nodes(expression, OldOracleJoinBinaryExpression.class))
                value.setOldOracleJoinSyntax(0);
        }
    }

    private static TranslationException unsupported(String code, String message) {
        return new TranslationException(code, message);
    }

    private static final class Edge {
        final String optional;
        final Set<String> parents = new LinkedHashSet<>();
        final List<Expression> conditions = new ArrayList<>();

        Edge(String optional) {
            this.optional = optional;
        }
    }
}
