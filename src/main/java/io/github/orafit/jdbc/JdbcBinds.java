package io.github.orafit.jdbc;

import io.github.orafit.translation.BindLineage;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** JDBC bind remapping, parameter metadata, and bind-width tracking. */
final class JdbcBinds {
    private JdbcBinds() {}

    static void mapSetter(
            PreparedStatement delegate,
            BindLineage binds,
            Method method,
            Object[] args,
            int input,
            String duplicateContext)
            throws Throwable {
        List<Integer> outputs = outputs(binds, input);
        if (emptyCharacterBind(method, args)) {
            int type = method.getName().equals("setNString") ? Types.NVARCHAR : Types.VARCHAR;
            for (int output : outputs) delegate.setNull(output, type);
            return;
        }
        if (outputs.size() > 1 && duplicateUnsafe(args)) {
            throw JdbcProxy.unsupported(
                    "A stream/reader bind cannot be duplicated safely by " + duplicateContext);
        }
        for (int output : outputs) {
            Object[] mapped = args.clone();
            mapped[0] = output;
            JdbcProxy.invokeDelegate(method, delegate, mapped);
        }
    }

    static ParameterMetaData oracleParameters(ParameterMetaData delegate, BindLineage binds) {
        return JdbcProxy.proxy(
                ParameterMetaData.class,
                (proxy, method, args) -> {
                    if (method.getName().equals("getParameterCount")) return binds.inputCount();
                    if (!JdbcProxy.indexed(args))
                        return JdbcProxy.invokeDelegate(method, delegate, args);
                    Object[] mapped = args.clone();
                    mapped[0] = outputs(binds, (Integer) args[0]).get(0);
                    return JdbcProxy.invokeDelegate(method, delegate, mapped);
                });
    }

    static int bindWidth(Method method, Object[] args) {
        return switch (method.getName()) {
            case "setString", "setNString" -> 32;
            case "setBigDecimal",
                    "setByte",
                    "setShort",
                    "setInt",
                    "setLong",
                    "setFloat",
                    "setDouble" ->
                    40;
            case "setNull" ->
                    args.length > 1 && args[1] instanceof Integer type ? bindTypeWidth(type) : 0;
            case "setObject" ->
                    args.length > 2 && args[2] instanceof Integer type
                            ? bindTypeWidth(type)
                            : args.length > 1 && args[1] instanceof String
                                    ? 32
                                    : args.length > 1 && args[1] instanceof Number ? 40 : 0;
            default -> 0;
        };
    }

    private static boolean emptyCharacterBind(Method method, Object[] args) {
        String name = method.getName();
        return (name.equals("setString") || name.equals("setNString"))
                && args != null
                && args.length > 1
                && args[1] instanceof String value
                && value.isEmpty();
    }

    private static List<Integer> outputs(BindLineage binds, int input) throws SQLException {
        try {
            List<Integer> outputs = binds.outputPositions(input);
            if (!outputs.isEmpty()) return outputs;
        } catch (IllegalArgumentException ex) {
            throw new SQLException(ex.getMessage(), "07009", ex);
        }
        throw JdbcProxy.badIndex(input, "does not map to a PostgreSQL bind");
    }

    private static int bindTypeWidth(int type) {
        return switch (type) {
            case Types.CHAR,
                    Types.VARCHAR,
                    Types.LONGVARCHAR,
                    Types.NCHAR,
                    Types.NVARCHAR,
                    Types.LONGNVARCHAR ->
                    32;
            case Types.TINYINT,
                    Types.SMALLINT,
                    Types.INTEGER,
                    Types.BIGINT,
                    Types.REAL,
                    Types.FLOAT,
                    Types.DOUBLE,
                    Types.NUMERIC,
                    Types.DECIMAL ->
                    40;
            default -> 0;
        };
    }

    private static boolean duplicateUnsafe(Object[] args) {
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof InputStream || args[i] instanceof Reader) return true;
        }
        return false;
    }
}
