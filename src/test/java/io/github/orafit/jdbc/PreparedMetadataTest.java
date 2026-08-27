package io.github.orafit.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

/** Public-boundary regression for Oracle metadata before PreparedStatement execution. */
public final class PreparedMetadataTest {
    @Test
    void contract() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(connection());

        PreparedStatement literal = wrapped.prepareStatement("SELECT 'ABC' AS value FROM DUAL");
        ResultSetMetaData literalMetadata = literal.getMetaData();
        equal(Types.CHAR, literalMetadata.getColumnType(1), "prepared Oracle literal JDBC type");
        equal("CHAR", literalMetadata.getColumnTypeName(1), "prepared Oracle literal type name");
        equal(3, literalMetadata.getPrecision(1), "prepared Oracle literal precision");
        equal("VALUE", literalMetadata.getColumnLabel(1), "prepared Oracle literal label");

        PreparedStatement source =
                wrapped.prepareStatement("SELECT id FROM bs_hierarchy WHERE ROWNUM <= 1");
        equal(
                ResultSetMetaData.columnNoNulls,
                source.getMetaData().isNullable(1),
                "prepared source NOT NULL metadata");

        PreparedStatement bind = wrapped.prepareStatement("SELECT ? || 'X' AS value FROM DUAL");
        equal(1, bind.getMetaData().getPrecision(1), "prepared bind precision before assignment");
        bind.setString(1, "abc");
        equal(33, bind.getMetaData().getPrecision(1), "prepared bind precision after assignment");

        System.out.println("PreparedMetadataTest OK");
    }

    private static Connection connection() {
        DatabaseMetaData database =
                proxy(
                        DatabaseMetaData.class,
                        (proxy, method, args) -> {
                            if (!method.getName().equals("getColumns"))
                                return defaultValue(method.getReturnType());
                            int[] cursor = {0};
                            return proxy(
                                    ResultSet.class,
                                    (resultProxy, resultMethod, resultArgs) ->
                                            switch (resultMethod.getName()) {
                                                case "next" -> cursor[0]++ == 0;
                                                case "getInt" -> ResultSetMetaData.columnNoNulls;
                                                default ->
                                                        defaultValue(resultMethod.getReturnType());
                                            });
                        });
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "prepareStatement" -> prepared((String) args[0]);
                            case "getSchema" -> "public";
                            case "getMetaData" -> database;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static PreparedStatement prepared(String sql) {
        boolean source = sql.toLowerCase().contains("bs_hierarchy");
        ResultSetMetaData rawMetadata =
                proxy(
                        ResultSetMetaData.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getColumnCount" -> 1;
                                    case "getColumnType" -> source ? Types.NUMERIC : Types.VARCHAR;
                                    case "getColumnTypeName" -> source ? "numeric" : "text";
                                    case "getPrecision" -> source ? 4 : Integer.MAX_VALUE;
                                    case "getScale" -> 0;
                                    case "getColumnLabel", "getColumnName" ->
                                            source ? "id" : "value";
                                    case "isNullable" -> ResultSetMetaData.columnNullable;
                                    default -> defaultValue(method.getReturnType());
                                });
        return proxy(
                PreparedStatement.class,
                (proxy, method, args) ->
                        method.getName().equals("getMetaData")
                                ? rawMetadata
                                : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new AssertionError(type);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
