package io.github.orafit.translation;

/** Supplies physical column families when Oracle coercion depends on the target database schema. */
public interface ColumnTypeResolver {
    enum Kind {
        NUMBER,
        FIXED_CHAR,
        TEXT,
        DATE,
        TIME,
        TIMESTAMP,
        TIMESTAMP_WITH_TIMEZONE,
        OTHER,
        UNKNOWN
    }

    ColumnTypeResolver NONE = (schema, table, column) -> Kind.UNKNOWN;

    Kind resolve(String schema, String table, String column) throws TranslationException;
}
