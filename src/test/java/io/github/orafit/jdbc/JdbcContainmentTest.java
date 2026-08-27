package io.github.orafit.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/** Public-boundary regression tests that parent navigation cannot bypass Orafit. */
public final class JdbcContainmentTest {
    private static final VendorAccess VENDOR = new VendorAccess() {};

    @Test
    void contract() throws Exception {
        Connection raw = rawConnection();
        Connection wrapped = new JdbcRuntime().wrap(raw);

        checkSelfWrapper(wrapped, Connection.class, "Connection");
        check(
                wrapped.isWrapperFor(VendorAccess.class),
                "vendor-specific unwrap support must remain delegated");
        check(
                wrapped.unwrap(VendorAccess.class) == VENDOR,
                "vendor-specific unwrap must remain an explicit delegate escape");

        Statement statement = wrapped.createStatement();
        checkSelfWrapper(statement, Statement.class, "Statement");
        checkParent(statement.getConnection(), wrapped, "Statement.getConnection");
        ResultSet rows = statement.executeQuery("SELECT 1 FROM DUAL");
        checkSelfWrapper(rows, ResultSet.class, "ResultSet");
        check(
                rows.getStatement() == statement,
                "ResultSet.getStatement must return the Orafit statement wrapper");
        checkParent(
                rows.getStatement().getConnection(),
                wrapped,
                "ResultSet.getStatement.getConnection");

        PreparedStatement prepared = wrapped.prepareStatement("SELECT 1 FROM DUAL");
        checkSelfWrapper(prepared, PreparedStatement.class, "PreparedStatement");
        check(
                prepared.unwrap(Statement.class) == prepared,
                "PreparedStatement unwrap(Statement.class) must stay on the Orafit wrapper");
        checkParent(prepared.getConnection(), wrapped, "PreparedStatement.getConnection");
        ResultSet preparedRows = prepared.executeQuery();
        checkSelfWrapper(preparedRows, ResultSet.class, "Prepared ResultSet");
        check(
                preparedRows.getStatement() == prepared,
                "Prepared ResultSet.getStatement must return the Orafit statement wrapper");
        checkParent(
                preparedRows.getStatement().getConnection(),
                wrapped,
                "Prepared ResultSet.getStatement.getConnection");

        DatabaseMetaData databaseMeta = wrapped.getMetaData();
        checkSelfWrapper(databaseMeta, DatabaseMetaData.class, "DatabaseMetaData");
        checkParent(databaseMeta.getConnection(), wrapped, "DatabaseMetaData.getConnection");

        CallableStatement function =
                wrapped.prepareCall("BEGIN ? := payroll_pkg.employee_name(?); END;");
        checkSelfWrapper(function, CallableStatement.class, "function CallableStatement");
        checkParent(function.getConnection(), wrapped, "function CallableStatement.getConnection");

        CallableStatement procedure = wrapped.prepareCall("BEGIN p(?); END;");
        checkSelfWrapper(procedure, CallableStatement.class, "procedure CallableStatement");
        checkParent(
                procedure.getConnection(), wrapped, "procedure CallableStatement.getConnection");

        CallableStatement returning =
                wrapped.prepareCall("INSERT INTO t(v) VALUES (?) RETURNING id INTO ?");
        checkSelfWrapper(returning, CallableStatement.class, "RETURNING CallableStatement");
        checkParent(
                returning.getConnection(), wrapped, "RETURNING CallableStatement.getConnection");

        System.out.println("JdbcContainmentTest OK");
    }

    private static <T> void checkSelfWrapper(T value, Class<T> type, String source)
            throws Exception {
        java.sql.Wrapper wrapper = (java.sql.Wrapper) value;
        check(wrapper.isWrapperFor(type), source + " must advertise its JDBC wrapper interface");
        check(
                wrapper.unwrap(type) == value,
                source + " self-unwrapping must stay on the Orafit proxy");
    }

    private static void checkParent(Connection actual, Connection expected, String source)
            throws Exception {
        check(actual == expected, source + " must return the Orafit connection wrapper");
        String translated = actual.nativeSQL("SELECT UNIQUE id FROM emp");
        check(
                translated.toUpperCase().contains("SELECT DISTINCT"),
                source + " must not bypass SQL translation");
    }

    private static Connection rawConnection() {
        Connection[] owner = new Connection[1];
        Statement[] rawStatement = new Statement[1];
        ResultSet rows =
                proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                method.getName().equals("getStatement")
                                        ? rawStatement[0]
                                        : defaultValue(method.getReturnType()));
        rawStatement[0] =
                proxy(
                        Statement.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getConnection" -> owner[0];
                                    case "executeQuery" -> rows;
                                    default -> defaultValue(method.getReturnType());
                                });
        PreparedStatement[] rawPrepared = new PreparedStatement[1];
        ResultSet preparedRows =
                proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                method.getName().equals("getStatement")
                                        ? rawPrepared[0]
                                        : defaultValue(method.getReturnType()));
        rawPrepared[0] =
                proxy(
                        PreparedStatement.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getConnection" -> owner[0];
                                    case "executeQuery" -> preparedRows;
                                    default -> defaultValue(method.getReturnType());
                                });
        DatabaseMetaData metadata =
                proxy(
                        DatabaseMetaData.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("getConnection")) return owner[0];
                            if (!method.getName().equals("getProcedureColumns"))
                                return defaultValue(method.getReturnType());
                            int[] row = {-1};
                            return proxy(
                                    ResultSet.class,
                                    (resultProxy, resultMethod, resultArgs) ->
                                            switch (resultMethod.getName()) {
                                                case "next" -> ++row[0] == 0;
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
        owner[0] =
                proxy(
                        Connection.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "createStatement" -> rawStatement[0];
                                    case "prepareStatement" -> rawPrepared[0];
                                    case "getMetaData" -> metadata;
                                    case "nativeSQL" -> args[0];
                                    case "isWrapperFor" -> {
                                        Class<?> type = (Class<?>) args[0];
                                        yield type == VendorAccess.class
                                                || type.isInstance(owner[0]);
                                    }
                                    case "unwrap" -> {
                                        Class<?> type = (Class<?>) args[0];
                                        if (type == VendorAccess.class) yield VENDOR;
                                        if (type.isInstance(owner[0])) yield owner[0];
                                        throw new SQLException("not a wrapper", "0A000");
                                    }
                                    default -> defaultValue(method.getReturnType());
                                });
        return owner[0];
    }

    private interface VendorAccess {}

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
}
