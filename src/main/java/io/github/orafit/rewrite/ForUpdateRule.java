package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;

/** Lowers the bounded Oracle {@code FOR UPDATE OF alias.column} shape to PostgreSQL relations. */
final class ForUpdateRule {
    boolean rewrite(Statement statement) throws TranslationException {
        boolean changed = false;
        for (PlainSelect select : ParserAdapter.nodes(statement, PlainSelect.class)) {
            Table target = select.getForUpdateTable();
            if (target == null) continue;
            String qualifier = target.getSchemaName();
            if (qualifier == null
                    || qualifier.isBlank()
                    || target.getDatabaseName() != null
                    || target.getName() == null)
                throw unsupported(target, "target must be qualified as alias.column");

            String relation = resolve(select, qualifier, target.getUnquotedSchemaName());
            if (relation == null)
                throw unsupported(target, "qualifier does not resolve to exactly one relation");
            select.setForUpdateTable(new Table(relation, false));
            changed = true;
        }
        return changed;
    }

    private static String resolve(PlainSelect select, String raw, String name) {
        String match = matchingName(select.getFromItem(), raw, name);
        if (select.getJoins() != null)
            for (Join join : select.getJoins()) {
                String candidate = matchingName(join.getFromItem(), raw, name);
                if (candidate == null) continue;
                if (match != null) return null;
                match = candidate;
            }
        return match;
    }

    private static String matchingName(FromItem item, String raw, String name) {
        if (!(item instanceof Table table)) return null;
        Alias alias = table.getAlias();
        String candidateRaw = alias == null ? table.getName() : alias.getName();
        String candidate = alias == null ? table.getUnquotedName() : alias.getUnquotedName();
        if (name == null
                || candidate == null
                || quoted(raw) != quoted(candidateRaw)
                || !(quoted(raw) ? name.equals(candidate) : name.equalsIgnoreCase(candidate)))
            return null;
        return candidateRaw;
    }

    private static boolean quoted(String identifier) {
        return identifier != null && identifier.startsWith("\"") && identifier.endsWith("\"");
    }

    private static TranslationException unsupported(Table target, String reason) {
        return new TranslationException(
                "FOR_UPDATE_TARGET",
                "Cannot preserve Oracle FOR UPDATE OF target " + target + ": " + reason);
    }
}
