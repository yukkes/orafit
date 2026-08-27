package io.github.orafit.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Public-boundary state-machine regression tests for reused CallableStatements. */
public final class CallableReuseTest {
    @Test
    void contract() throws Exception {
        functionFailureInvalidatesReturnState();
        procedurePreBindFailureInvalidatesOutState();
        returningFailureInvalidatesUpdateCount();
        clearParametersInvalidatesOutputs();
        System.out.println("CallableReuseTest OK");
    }

    private static void functionFailureInvalidatesReturnState() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(functionConnection());
        CallableStatement call =
                wrapped.prepareCall("BEGIN ? := payroll_pkg.employee_name(?); END;");
        call.registerOutParameter(1, Types.VARCHAR);
        call.setInt(2, 7);

        check(!call.execute(), "first function execute result");
        equal("employee-7", call.getString(1), "first function return value");

        call.setInt(2, 8);
        expectFailure(call, "function delegate failure SQLState");
        expectUnavailable(() -> call.getString(1), "function getter after failed reuse SQLState");
    }

    private static void procedurePreBindFailureInvalidatesOutState() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(procedureOutConnection());
        CallableStatement call = wrapped.prepareCall("BEGIN p(?); END;");
        call.registerOutParameter(1, Types.VARCHAR);

        check(!call.execute(), "first OUT procedure execute result");
        equal("out-1", call.getString(1), "first procedure OUT value");

        expectFailure(call, "procedure OUT pre-bind failure SQLState");
        expectUnavailable(
                () -> call.getString(1), "procedure getter after failed pre-bind SQLState");
    }

    private static void returningFailureInvalidatesUpdateCount() throws Exception {
        Connection wrapped = new JdbcRuntime().wrap(returningConnection());
        CallableStatement call =
                wrapped.prepareCall("INSERT INTO t(v) VALUES (?) RETURNING id INTO ?");
        call.setString(1, "first");
        call.registerOutParameter(2, Types.INTEGER);

        equal(1, call.executeUpdate(), "first RETURNING update count");
        equal(1, call.getUpdateCount(), "first RETURNING getUpdateCount");
        equal(7, call.getInt(2), "first RETURNING OUT value");

        call.setString(1, "second");
        expectFailure(call, "RETURNING delegate failure SQLState");
        equal(-1, call.getUpdateCount(), "RETURNING update count after failed reuse");
        equal(-1L, call.getLargeUpdateCount(), "RETURNING large update count after failed reuse");
        expectUnavailable(() -> call.getInt(2), "RETURNING getter after failed reuse SQLState");
    }

    private static void clearParametersInvalidatesOutputs() throws Exception {
        CallableStatement function =
                new JdbcRuntime()
                        .wrap(functionConnection())
                        .prepareCall("BEGIN ? := payroll_pkg.employee_name(?); END;");
        function.registerOutParameter(1, Types.VARCHAR);
        function.setInt(2, 7);
        check(!function.execute(), "function execute before clearParameters");
        equal("employee-7", function.getString(1), "function value before clearParameters");
        function.clearParameters();
        expectUnavailable(
                () -> function.getString(1), "function getter after clearParameters SQLState");

        CallableStatement procedure =
                new JdbcRuntime().wrap(procedureOutConnection()).prepareCall("BEGIN p(?); END;");
        procedure.registerOutParameter(1, Types.VARCHAR);
        check(!procedure.execute(), "procedure execute before clearParameters");
        equal("out-1", procedure.getString(1), "procedure value before clearParameters");
        procedure.clearParameters();
        expectUnavailable(
                () -> procedure.getString(1), "procedure getter after clearParameters SQLState");

        CallableStatement returning =
                new JdbcRuntime()
                        .wrap(returningConnection())
                        .prepareCall("INSERT INTO t(v) VALUES (?) RETURNING id INTO ?");
        returning.setString(1, "first");
        returning.registerOutParameter(2, Types.INTEGER);
        equal(1, returning.executeUpdate(), "RETURNING execute before clearParameters");
        equal(7, returning.getInt(2), "RETURNING value before clearParameters");
        returning.clearParameters();
        equal(-1, returning.getUpdateCount(), "RETURNING update count after clearParameters");
        equal(
                -1L,
                returning.getLargeUpdateCount(),
                "RETURNING large update count after clearParameters");
        expectUnavailable(
                () -> returning.getInt(2), "RETURNING getter after clearParameters SQLState");
    }

    private static void expectFailure(CallableStatement call, String message) throws Exception {
        try {
            call.execute();
            throw new AssertionError("second execution attempt must fail");
        } catch (SQLException ex) {
            equal("40001", ex.getSQLState(), message);
        }
    }

    private static void expectUnavailable(SqlCall action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError("failed re-execution must invalidate prior output state");
        } catch (SQLException ex) {
            equal("HY010", ex.getSQLState(), message);
        }
    }

    private static Connection functionConnection() {
        int[] executions = {0};
        int[] cursor = {0};
        ResultSet result =
                proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "next" -> ++cursor[0] == 1;
                                    case "getObject" -> "employee-7";
                                    default -> defaultValue(method.getReturnType());
                                });
        PreparedStatement prepared =
                proxy(
                        PreparedStatement.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("execute")) {
                                if (++executions[0] == 2)
                                    throw new SQLException("simulated retry failure", "40001");
                                cursor[0] = 0;
                                return true;
                            }
                            if (method.getName().equals("getResultSet")) return result;
                            return defaultValue(method.getReturnType());
                        });
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "prepareStatement" -> prepared;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static Connection procedureOutConnection() {
        int[] setNullCalls = {0};
        int[] cursor = {0};
        ResultSet output =
                proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "next" -> ++cursor[0] == 1;
                                    case "getObject" -> "out-1";
                                    default -> defaultValue(method.getReturnType());
                                });
        PreparedStatement prepared =
                proxy(
                        PreparedStatement.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "setNull" -> {
                                        if (++setNullCalls[0] == 2)
                                            throw new SQLException(
                                                    "simulated OUT pre-bind failure", "40001");
                                        yield null;
                                    }
                                    case "execute" -> {
                                        cursor[0] = 0;
                                        yield true;
                                    }
                                    case "getResultSet" -> output;
                                    default -> defaultValue(method.getReturnType());
                                });
        DatabaseMetaData metadata =
                proxy(
                        DatabaseMetaData.class,
                        (proxy, method, args) -> {
                            if (!method.getName().equals("getProcedureColumns"))
                                return defaultValue(method.getReturnType());
                            int[] row = {-1};
                            return proxy(
                                    ResultSet.class,
                                    (resultProxy, resultMethod, resultArgs) ->
                                            switch (resultMethod.getName()) {
                                                case "next" -> ++row[0] == 0;
                                                case "getShort" ->
                                                        (short) DatabaseMetaData.procedureColumnOut;
                                                case "getString" ->
                                                        "SPECIFIC_NAME".equals(resultArgs[0])
                                                                ? "p_1"
                                                                : "p_value";
                                                case "getInt" ->
                                                        "DATA_TYPE".equals(resultArgs[0])
                                                                ? Types.VARCHAR
                                                                : 1;
                                                default ->
                                                        defaultValue(resultMethod.getReturnType());
                                            });
                        });
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getMetaData" -> metadata;
                            case "prepareStatement" -> prepared;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static Connection returningConnection() {
        int[] executions = {0};
        int[] cursor = {0};
        ResultSet output =
                proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "next" -> ++cursor[0] == 1;
                                    case "getObject" -> 7;
                                    default -> defaultValue(method.getReturnType());
                                });
        PreparedStatement prepared =
                proxy(
                        PreparedStatement.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "execute" -> {
                                        if (++executions[0] == 2)
                                            throw new SQLException(
                                                    "simulated RETURNING retry failure", "40001");
                                        cursor[0] = 0;
                                        yield true;
                                    }
                                    case "getResultSet" -> output;
                                    default -> defaultValue(method.getReturnType());
                                });
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "prepareStatement" -> prepared;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    @FunctionalInterface
    private interface SqlCall {
        void run() throws SQLException;
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
        throw new AssertionError("unknown primitive " + type);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
