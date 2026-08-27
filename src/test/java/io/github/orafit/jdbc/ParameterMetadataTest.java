package io.github.orafit.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Public-boundary regression for original JDBC parameter identity after SQL rewriting. */
public final class ParameterMetadataTest {
    @Test
    void contract() throws Exception {
        rewrittenPreparedMetadata();
        functionCallableMetadataFailsClosed();
        procedureCallableMetadataFailsClosed();
        returningCallableMetadataFailsClosed();
        System.out.println("ParameterMetadataTest OK");
    }

    private static void rewrittenPreparedMetadata() throws Exception {
        Recorder recorder = new Recorder();
        Connection wrapped = new JdbcRuntime().wrap(recorder.connection());
        PreparedStatement prepared =
                wrapped.prepareStatement("SELECT DECODE(?, 'A', 1, 'B', 2, 0) AS value FROM DUAL");
        ParameterMetaData metadata = prepared.getParameterMetaData();

        check(
                recorder.delegateParameterCount > 1,
                "test precondition: DECODE rewrite must duplicate the source bind");
        equal(
                1,
                metadata.getParameterCount(),
                "application-visible parameter count must stay on the original Oracle SQL");
        equal(
                Types.VARCHAR,
                metadata.getParameterType(1),
                "indexed metadata must map the original bind to its first delegate position");
        try {
            metadata.getParameterType(2);
            throw new AssertionError(
                    "rewritten delegate bind indexes must not become public parameters");
        } catch (SQLException ex) {
            equal("07009", ex.getSQLState(), "invalid original parameter metadata index SQLState");
        }

        prepared.setString(1, "A");
        equal(
                recorder.delegateParameterCount,
                recorder.setIndexes.size(),
                "one original bind must populate every rewritten delegate position");
        equal(
                List.of(1, 2),
                recorder.setIndexes,
                "duplicated bind setter positions must follow rewrite lineage");
    }

    private static void functionCallableMetadataFailsClosed() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(callableConnection());
        CallableStatement call =
                wrapped.prepareCall("BEGIN ? := payroll_pkg.employee_name(?); END;");
        expectUnsupportedMetadata(call, "function CallableStatement parameter metadata");
    }

    private static void procedureCallableMetadataFailsClosed() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(procedureConnection());
        CallableStatement call = wrapped.prepareCall("BEGIN p(?); END;");
        expectUnsupportedMetadata(call, "procedure CallableStatement parameter metadata");
    }

    private static void returningCallableMetadataFailsClosed() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(callableConnection());
        CallableStatement call =
                wrapped.prepareCall("INSERT INTO t(v) VALUES (?) RETURNING id INTO ?");
        expectUnsupportedMetadata(call, "RETURNING CallableStatement parameter metadata");
    }

    private static void expectUnsupportedMetadata(PreparedStatement statement, String message)
            throws Exception {
        try {
            statement.getParameterMetaData();
            throw new AssertionError(
                    message + " must fail closed until Oracle IN/OUT metadata is modeled");
        } catch (SQLException ex) {
            equal("0A000", ex.getSQLState(), message + " SQLState");
        }
    }

    private static Connection callableConnection() {
        PreparedStatement prepared = rawPrepared();
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        method.getName().equals("prepareStatement")
                                ? prepared
                                : defaultValue(method.getReturnType()));
    }

    private static Connection procedureConnection() {
        PreparedStatement prepared = rawPrepared();
        DatabaseMetaData database =
                proxy(
                        DatabaseMetaData.class,
                        (proxy, method, args) -> {
                            if (!method.getName().equals("getProcedureColumns"))
                                return defaultValue(method.getReturnType());
                            int[] cursor = {0};
                            return proxy(
                                    ResultSet.class,
                                    (resultProxy, resultMethod, resultArgs) ->
                                            switch (resultMethod.getName()) {
                                                case "next" -> cursor[0]++ == 0;
                                                case "getShort" ->
                                                        (short) DatabaseMetaData.procedureColumnIn;
                                                case "getString" ->
                                                        "SPECIFIC_NAME".equals(resultArgs[0])
                                                                ? "p_1"
                                                                : "p_value";
                                                case "getInt" ->
                                                        "DATA_TYPE".equals(resultArgs[0])
                                                                ? Types.INTEGER
                                                                : 1;
                                                default ->
                                                        defaultValue(resultMethod.getReturnType());
                                            });
                        });
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "prepareStatement" -> prepared;
                            case "getMetaData" -> database;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static PreparedStatement rawPrepared() {
        ParameterMetaData metadata =
                proxy(
                        ParameterMetaData.class,
                        (proxy, method, args) ->
                                method.getName().equals("getParameterCount")
                                        ? 1
                                        : defaultValue(method.getReturnType()));
        return proxy(
                PreparedStatement.class,
                (proxy, method, args) ->
                        method.getName().equals("getParameterMetaData")
                                ? metadata
                                : defaultValue(method.getReturnType()));
    }

    private static final class Recorder {
        int delegateParameterCount;
        final List<Integer> setIndexes = new ArrayList<>();

        Connection connection() {
            return proxy(
                    Connection.class,
                    (proxy, method, args) -> {
                        if (!method.getName().equals("prepareStatement"))
                            return defaultValue(method.getReturnType());
                        String sql = (String) args[0];
                        delegateParameterCount = count(sql, '?');
                        ParameterMetaData metadata =
                                proxy(
                                        ParameterMetaData.class,
                                        (metadataProxy, metadataMethod, metadataArgs) ->
                                                switch (metadataMethod.getName()) {
                                                    case "getParameterCount" ->
                                                            delegateParameterCount;
                                                    case "getParameterType" ->
                                                            ((Integer) metadataArgs[0]) == 1
                                                                    ? Types.VARCHAR
                                                                    : Types.INTEGER;
                                                    default ->
                                                            defaultValue(
                                                                    metadataMethod.getReturnType());
                                                });
                        return proxy(
                                PreparedStatement.class,
                                (preparedProxy, preparedMethod, preparedArgs) -> {
                                    if (preparedMethod.getName().equals("getParameterMetaData"))
                                        return metadata;
                                    if (preparedMethod.getName().startsWith("set")
                                            && preparedArgs != null
                                            && preparedArgs.length > 0
                                            && preparedArgs[0] instanceof Integer index) {
                                        setIndexes.add(index);
                                    }
                                    return defaultValue(preparedMethod.getReturnType());
                                });
                    });
        }
    }

    private static int count(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == target) count++;
        return count;
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

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
