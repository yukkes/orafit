package io.github.orafit.jdbc;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/** Shared conversion for synthetic JDBC OUT values. */
final class JdbcValue {
    private JdbcValue() {}

    static Object get(Object value, Method method, Object[] args, String label)
            throws SQLException {
        String name = method.getName();
        if (name.equals("getObject")) {
            if (args.length == 2 && args[1] instanceof Class<?> type)
                return convert(value, type, label);
            return value instanceof BigDecimal ? number(value, label) : value;
        }
        if (value == null) {
            return switch (name) {
                case "getBoolean" -> false;
                case "getByte" -> (byte) 0;
                case "getShort" -> (short) 0;
                case "getInt" -> 0;
                case "getLong" -> 0L;
                case "getFloat" -> 0F;
                case "getDouble" -> 0D;
                default -> null;
            };
        }
        BigDecimal number = value instanceof Number ? number(value, label) : null;
        return switch (name) {
            case "getString", "getNString" ->
                    value instanceof BigDecimal ? number.toPlainString() : String.valueOf(value);
            case "getBoolean" ->
                    value instanceof Boolean b ? b : number != null && number.signum() != 0;
            case "getByte" -> number(value, label).byteValue();
            case "getShort" -> number(value, label).shortValue();
            case "getInt" -> number(value, label).intValue();
            case "getLong" -> number(value, label).longValue();
            case "getFloat" -> number(value, label).floatValue();
            case "getDouble" -> number(value, label).doubleValue();
            case "getBigDecimal" -> number(value, label);
            default -> method.getReturnType().isInstance(value) ? value : unsupported(name, label);
        };
    }

    private static Object convert(Object value, Class<?> type, String label) throws SQLException {
        if (value == null) return null;
        if (type == BigDecimal.class) return number(value, label);
        if (type == String.class)
            return value instanceof BigDecimal
                    ? number(value, label).toPlainString()
                    : String.valueOf(value);
        if (type.isInstance(value)) return value;
        if (Number.class.isAssignableFrom(type)) {
            BigDecimal number = number(value, label);
            if (type == Integer.class) return number.intValue();
            if (type == Long.class) return number.longValue();
            if (type == Short.class) return number.shortValue();
            if (type == Byte.class) return number.byteValue();
            if (type == Double.class) return number.doubleValue();
            if (type == Float.class) return number.floatValue();
        }
        throw new SQLException("Cannot convert " + label + " value to " + type.getName(), "22018");
    }

    private static BigDecimal number(Object value, String label) throws SQLException {
        try {
            BigDecimal decimal =
                    value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
            return OracleNumber.normalize(decimal);
        } catch (NumberFormatException ex) {
            throw new SQLException(label + " value is not numeric: " + value, "22018", ex);
        }
    }

    private static Object unsupported(String name, String label)
            throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException(
                "Unsupported " + label + " getter: " + name, "0A000");
    }
}
