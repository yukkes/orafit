package io.github.orafit.rewrite.hierarchy;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

/** Shared identifier checks for CONNECT BY lowering. */
final class HierarchySupport {
    private HierarchySupport() {}

    static TranslationException fail(String code, String message) {
        return new TranslationException(code, message);
    }

    static boolean unqualified(Column column, String name) {
        return (column.getTableName() == null || column.getTableName().isBlank())
                && name.equalsIgnoreCase(column.getUnquotedColumnName());
    }

    static String invalidQualifier(Column column, Table source, String aliasName) {
        String qualifier = column.getUnquotedTableName();
        if (qualifier == null || qualifier.isBlank()) return null;
        String alias = unquote(aliasName);
        return qualifier.equalsIgnoreCase(source.getUnquotedName())
                        || qualifier.equalsIgnoreCase(alias)
                ? null
                : qualifier;
    }

    private static String unquote(String value) {
        if (value != null
                && value.length() >= 2
                && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }
}
