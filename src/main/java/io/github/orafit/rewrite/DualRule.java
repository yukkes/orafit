package io.github.orafit.rewrite;

import io.github.orafit.parse.ParserAdapter;

import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;

/** Routes Oracle's public DUAL object to the extension-owned compatibility view. */
final class DualRule {
    boolean rewrite(Statement statement) {
        boolean changed = rewriteTables(ParserAdapter.nodes(statement, Table.class));
        if (statement instanceof Update update && update.getWhere() != null)
            changed |= rewriteNested((ASTNodeAccess) update.getWhere());
        if (statement instanceof Delete delete && delete.getWhere() != null)
            changed |= rewriteNested((ASTNodeAccess) delete.getWhere());
        return changed;
    }

    static boolean rewriteNested(ASTNodeAccess access) {
        return rewriteTables(ParserAdapter.nodes(access, Table.class));
    }

    private static boolean rewriteTables(Iterable<Table> tables) {
        boolean changed = false;
        for (Table table : tables) {
            if ((table.getSchemaName() == null || table.getSchemaName().isBlank())
                    && "DUAL".equalsIgnoreCase(table.getName())) {
                table.setSchemaName("orafit");
                changed = true;
            }
        }
        return changed;
    }
}
