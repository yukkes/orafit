package io.github.orafit.jdbc;

import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.ResultMetadataPlan.Column;
import io.github.orafit.translation.ResultMetadataPlan.Kind;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/** Oracle-facing result-set and result-metadata adaptation. */
final class JdbcMetadata {
    private JdbcMetadata() {}

    static Object oracleResult(Object value, ResultMetadataPlan plan, Statement owner) {
        return oracleResult(value, plan, null, owner);
    }

    static Object oracleResult(
            Object value, ResultMetadataPlan plan, int[] bindWidths, Statement owner) {
        if (!(value instanceof ResultSet result) || (!plan.present() && owner == null))
            return value;
        return JdbcProxy.proxy(
                ResultSet.class,
                (proxy, method, args) -> {
                    if (method.getName().equals("getStatement") && owner != null) return owner;
                    if (method.getName().equals("getMetaData"))
                        return oracleMetadata(result, plan, bindWidths);
                    ResultCall call = resultCall(result, plan, method, args);
                    if (call.result() != null) return call.result();
                    Object raw = JdbcProxy.invokeDelegate(call.method(), result, call.args());
                    if (oracleStringGetter(method.getName()) && raw instanceof String text) {
                        raw = oracleStringResult(result, plan, call.args(), text, owner);
                    }
                    if ((method.getName().equals("getBigDecimal")
                                    || method.getName().equals("getObject"))
                            && raw instanceof BigDecimal number) {
                        return oracleNumberResult(result, call.args(), number);
                    }
                    return raw;
                });
    }

    private static String oracleStringResult(
            ResultSet result, ResultMetadataPlan plan, Object[] args, String value, Statement owner)
            throws SQLException {
        if (args == null || args.length == 0) return value;
        int type;
        int width;
        try {
            int index =
                    args[0] instanceof Integer ordinal
                            ? ordinal
                            : result.findColumn((String) args[0]);
            Column column = plan.column(index);
            if (column.padFromSource() && !value.equals(column.paddingFallback())) {
                Statement statement = owner != null ? owner : result.getStatement();
                Connection connection = statement == null ? null : statement.getConnection();
                Integer sourceType = sourceColumnMetadata(connection, column, "DATA_TYPE");
                Integer sourceWidth = sourceColumnMetadata(connection, column, "COLUMN_SIZE");
                if (sourceType != null && sourceWidth != null && sourceType == Types.CHAR)
                    return padCharResult(sourceType, sourceWidth, value);
            }
            ResultSetMetaData metadata = result.getMetaData();
            type = metadata.getColumnType(index);
            width = type == Types.CHAR ? metadata.getColumnDisplaySize(index) : 0;
        } catch (SQLException ignored) {
            return value;
        }
        if (type == Types.NUMERIC || type == Types.DECIMAL) {
            try {
                return oracleNumberResult(result, args, new BigDecimal(value)).toPlainString();
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return padCharResult(type, width, value);
    }

    private static BigDecimal oracleNumberResult(
            ResultSet result, Object[] args, BigDecimal value) {
        BigDecimal oracle = value;
        if (args != null && args.length > 0) {
            try {
                int index =
                        args[0] instanceof Integer ordinal
                                ? ordinal
                                : result.findColumn((String) args[0]);
                ResultSetMetaData metadata = result.getMetaData();
                int type = metadata.getColumnType(index);
                int precision = metadata.getPrecision(index);
                int scale = metadata.getScale(index);
                if ((type == Types.NUMERIC || type == Types.DECIMAL)
                        && precision > 0
                        && scale >= 0
                        && oracle.scale() > scale) {
                    oracle = oracle.setScale(scale, RoundingMode.HALF_UP);
                }
            } catch (SQLException | ClassCastException ignored) {
                // Preserve the value when the delegate cannot describe its result column.
            }
        }
        return OracleNumber.normalize(oracle);
    }

    private static boolean oracleStringGetter(String name) {
        return name.equals("getString") || name.equals("getNString") || name.equals("getObject");
    }

    private static String padCharResult(int type, int width, String value) throws SQLException {
        if (type != Types.CHAR) return value;
        if (width <= 0 || width == Integer.MAX_VALUE) return value;

        int end = value.length();
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        while (bytes > width && end > 0 && value.charAt(end - 1) == ' ') {
            end--;
            bytes--;
        }
        if (bytes > width) {
            throw new SQLException(
                    "Orafit: fixed CHAR value exceeds its Oracle byte width", "22001");
        }
        return value.substring(0, end) + " ".repeat(width - bytes);
    }

    private static boolean oracleCountLabel(String label) {
        return "count(*)".equals(label.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT));
    }

    private static ResultCall resultCall(
            ResultSet resultSet, ResultMetadataPlan plan, Method method, Object[] args) {
        ResultCall original = new ResultCall(method, args, null);
        if (args == null || args.length == 0 || !(args[0] instanceof String label)) return original;
        try {
            ResultSetMetaData metadata = resultSet.getMetaData();
            if (metadata == null) return original;
            if (oracleCountLabel(label)) {
                if (hasColumnLabel(metadata, label)) return original;
                Object[] mapped = args.clone();
                mapped[0] = "count";
                return new ResultCall(method, mapped, null);
            }
            if (hasColumnLabel(metadata, label)) return original;
            int ordinal = planOrdinal(plan, label);
            if (ordinal == 0 || ordinal > metadata.getColumnCount()) return original;
            if (method.getName().equals("findColumn")) {
                return new ResultCall(method, args, ordinal);
            }
            Method indexed = indexedMethod(method);
            if (indexed == null) return original;
            Object[] mapped = args.clone();
            mapped[0] = ordinal;
            return new ResultCall(indexed, mapped, null);
        } catch (SQLException ignored) {
            return original;
        }
    }

    private static Method indexedMethod(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes().clone();
        parameterTypes[0] = int.class;
        try {
            return ResultSet.class.getMethod(method.getName(), parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static int planOrdinal(ResultMetadataPlan plan, String label) {
        int ordinal = 0;
        for (int i = 0; i < plan.columns().size(); i++) {
            String plannedLabel = plan.columns().get(i).label();
            if (plannedLabel == null || !plannedLabel.equalsIgnoreCase(label)) continue;
            if (ordinal != 0) return 0;
            ordinal = i + 1;
        }
        return ordinal;
    }

    private static boolean hasColumnLabel(ResultSetMetaData metadata, String label)
            throws SQLException {
        for (int ordinal = 1; ordinal <= metadata.getColumnCount(); ordinal++) {
            if (label.equalsIgnoreCase(metadata.getColumnLabel(ordinal))) return true;
        }
        return false;
    }

    private record ResultCall(Method method, Object[] args, Integer result) {}

    private static ResultSetMetaData oracleMetadata(
            ResultSet result, ResultMetadataPlan plan, int[] bindWidths) throws SQLException {
        Statement statement = result.getStatement();
        return oracleMetadata(
                result.getMetaData(),
                plan,
                bindWidths,
                statement == null ? null : statement.getConnection());
    }

    static ResultSetMetaData oracleMetadata(
            ResultSetMetaData delegate,
            ResultMetadataPlan plan,
            int[] bindWidths,
            Connection connection) {
        return JdbcProxy.proxy(
                ResultSetMetaData.class,
                (proxy, method, args) -> {
                    if (!JdbcProxy.indexed(args))
                        return JdbcProxy.invokeDelegate(method, delegate, args);
                    int ordinal = (Integer) args[0];
                    Column column = plan.column(ordinal);
                    int type = delegate.getColumnType(ordinal);
                    return switch (method.getName()) {
                        case "getColumnLabel", "getColumnName" ->
                                column.label() != null
                                        ? column.label()
                                        : ((String)
                                                        JdbcProxy.invokeDelegate(
                                                                method, delegate, args))
                                                .toUpperCase(java.util.Locale.ROOT);
                        case "getColumnType" -> jdbcType(column.kind(), type);
                        case "getColumnTypeName" ->
                                typeName(column.kind(), type, delegate.getColumnTypeName(ordinal));
                        case "getPrecision" ->
                                oraclePrecision(
                                        column,
                                        bindWidths,
                                        type,
                                        delegate.getPrecision(ordinal),
                                        connection);
                        case "getScale" ->
                                column.scale() != null
                                                && (column.kind() != Kind.AUTO || numeric(type))
                                        ? column.scale()
                                        : delegate.getScale(ordinal);
                        case "isNullable" ->
                                oracleNullable(connection, column, delegate.isNullable(ordinal));
                        default -> JdbcProxy.invokeDelegate(method, delegate, args);
                    };
                });
    }

    private static int oracleNullable(Connection connection, Column column, int delegate) {
        if (column.nullable() != null) return column.nullable();
        Integer nullable = sourceColumnMetadata(connection, column, "NULLABLE");
        if (nullable != null) return nullable;
        return delegate == ResultSetMetaData.columnNullableUnknown
                ? ResultSetMetaData.columnNullable
                : delegate;
    }

    private static Integer sourceColumnMetadata(
            Connection connection, Column column, String field) {
        if (column.source() == null || connection == null) return null;
        try {
            String schema =
                    column.source().schema() == null
                            ? connection.getSchema()
                            : column.source().schema();
            for (String table : identifierCandidates(column.source().table())) {
                for (String name : identifierCandidates(column.source().column())) {
                    try (ResultSet columns =
                            connection
                                    .getMetaData()
                                    .getColumns(connection.getCatalog(), schema, table, name)) {
                        if (columns.next()) return columns.getInt(field);
                    }
                }
            }
        } catch (SQLException ignored) {
            // Fall through when the delegate cannot describe the source column.
        }
        return null;
    }

    private static String[] identifierCandidates(String identifier) {
        return new String[] {
            identifier,
            identifier.toLowerCase(java.util.Locale.ROOT),
            identifier.toUpperCase(java.util.Locale.ROOT)
        };
    }

    private static int oraclePrecision(
            Column column, int[] bindWidths, int type, int delegate, Connection connection) {
        Integer bind = column.precisionBind();
        if (bind != null
                && bindWidths != null
                && bind > 0
                && bind < bindWidths.length
                && bindWidths[bind] > 0) {
            return bindWidths[bind] + (column.precision() == null ? 0 : column.precision());
        }
        if (delegate == Integer.MAX_VALUE) {
            Integer sourcePrecision = sourceColumnMetadata(connection, column, "COLUMN_SIZE");
            if (sourcePrecision != null && sourcePrecision > 0) return sourcePrecision;
        }
        return column.precision() != null && (column.kind() != Kind.AUTO || numeric(type))
                ? column.precision()
                : precision(type, delegate);
    }

    private static boolean numeric(int type) {
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
                    true;
            default -> false;
        };
    }

    private static int jdbcType(Kind kind, int type) {
        if (kind == Kind.NUMBER) return Types.NUMERIC;
        if (kind == Kind.CHAR) return Types.CHAR;
        if (kind == Kind.VARCHAR2) return Types.VARCHAR;
        if (kind == Kind.DATE || kind == Kind.TIMESTAMP) return Types.TIMESTAMP;
        return switch (type) {
            case Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Types.NUMERIC;
            default -> type;
        };
    }

    private static String typeName(Kind kind, int type, String delegate) {
        if (kind == Kind.NUMBER) return "NUMBER";
        if (kind == Kind.CHAR) return "CHAR";
        if (kind == Kind.VARCHAR2) return "VARCHAR2";
        if (kind == Kind.DATE) return "DATE";
        if (kind == Kind.TIMESTAMP) return "TIMESTAMP";
        return switch (type) {
            case Types.NUMERIC, Types.DECIMAL, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    "NUMBER";
            case Types.CHAR -> "CHAR";
            case Types.VARCHAR, Types.LONGVARCHAR -> "VARCHAR2";
            case Types.DATE -> "DATE";
            case Types.TIMESTAMP -> "TIMESTAMP";
            default -> delegate;
        };
    }

    private static int precision(int type, int delegate) {
        return switch (type) {
            case Types.SMALLINT, Types.INTEGER, Types.BIGINT -> 0;
            case Types.DATE -> 7;
            default -> delegate;
        };
    }
}
