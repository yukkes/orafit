package io.github.orafit.jdbc;

import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.TranslationException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Connection-scoped PostgreSQL column metadata used by schema-dependent Oracle coercion. */
final class JdbcColumnTypeResolver implements ColumnTypeResolver {
    private final Connection connection;
    private final Map<String, Map<String, Kind>> tables = new HashMap<>();

    JdbcColumnTypeResolver(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Kind resolve(String schema, String table, String column) throws TranslationException {
        if (table == null || column == null) return Kind.UNKNOWN;
        try {
            String effectiveSchema = schema == null ? visibleSchema(table) : schema;
            String key = (effectiveSchema + "." + table).toLowerCase(Locale.ROOT);
            Map<String, Kind> columns = tables.get(key);
            if (columns == null) {
                columns = load(effectiveSchema, table);
                tables.put(key, columns);
            }
            return columns.getOrDefault(column.toLowerCase(Locale.ROOT), Kind.UNKNOWN);
        } catch (SQLException failure) {
            throw new TranslationException(
                    "COLUMN_METADATA", "Could not resolve " + table + "." + column, failure);
        }
    }

    private String visibleSchema(String table) throws SQLException {
        if (connection.getMetaData() == null) return connection.getSchema();
        // Resolve the relation through PostgreSQL's search_path, including temporary
        // schemas, rather than assuming current_schema contains every visible table.
        try (var statement =
                connection.prepareStatement(
                        "SELECT n.nspname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = pg_catalog.to_regclass(?)")) {
            statement.setString(1, "\"" + table.replace("\"", "\"\"") + "\"");
            try (ResultSet rows = statement.executeQuery()) {
                if (rows != null && rows.next()) return rows.getString(1);
            }
        }
        return connection.getSchema();
    }

    private Map<String, Kind> load(String schema, String table) throws SQLException {
        Map<String, Kind> result = new HashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        if (metadata == null) return result;
        for (String candidate :
                new String[] {
                    table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)
                }) {
            try (ResultSet columns =
                    metadata.getColumns(connection.getCatalog(), schema, candidate, null)) {
                while (columns != null && columns.next()) {
                    result.put(
                            columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                            kind(columns.getInt("DATA_TYPE")));
                }
            }
            if (!result.isEmpty()) break;
        }
        return result;
    }

    private static Kind kind(int type) {
        return switch (type) {
            case Types.TINYINT,
                    Types.SMALLINT,
                    Types.INTEGER,
                    Types.BIGINT,
                    Types.REAL,
                    Types.FLOAT,
                    Types.DOUBLE,
                    Types.NUMERIC,
                    Types.DECIMAL ->
                    Kind.NUMBER;
            case Types.CHAR, Types.NCHAR -> Kind.FIXED_CHAR;
            case Types.VARCHAR,
                    Types.LONGVARCHAR,
                    Types.NVARCHAR,
                    Types.LONGNVARCHAR,
                    Types.CLOB,
                    Types.NCLOB ->
                    Kind.TEXT;
            case Types.DATE -> Kind.DATE;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> Kind.TIME;
            case Types.TIMESTAMP -> Kind.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> Kind.TIMESTAMP_WITH_TIMEZONE;
            default -> Kind.OTHER;
        };
    }
}
