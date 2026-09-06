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
import java.util.ArrayList;
import java.util.List;

/** Dependency-free JDBC proxy smoke test for the bounded compatibility engine. */
public final class JdbcRuntimeTest {
    @Test
    void contract() throws Exception {
        Recorder recorder = new Recorder();
        Connection wrapped = new JdbcRuntime().wrap(recorder.connection());

        PreparedStatement prepared = wrapped.prepareStatement("SELECT NVL(?, 'x') FROM DUAL");
        check(
                recorder.preparedSql.toLowerCase().contains("orafit.nvl"),
                "prepareStatement must translate NVL");
        check(
                recorder.preparedSql.toLowerCase().contains("orafit.dual"),
                "prepareStatement must translate DUAL");
        prepared.setString(1, "value");
        equal(List.of(1), recorder.setIndexes, "PreparedStatement bind index must follow lineage");

        Statement statement = wrapped.createStatement();
        statement.executeQuery("SELECT SUBSTR('ABC', 0, 2) FROM DUAL");
        check(
                recorder.statementSql.toLowerCase().contains("orafit.substr"),
                "Statement SQL must be translated");

        String nativeSql = wrapped.nativeSQL("SELECT UNIQUE id FROM emp");
        check(
                nativeSql.toUpperCase().contains("SELECT DISTINCT"),
                "nativeSQL must expose translated SQL");

        wrapped.prepareStatement(
                "SELECT empno FROM (SELECT empno FROM emp ORDER BY empno DESC) WHERE ROWNUM <= 3");
        check(
                recorder.preparedSql.contains("__orafit_inline_"),
                "anonymous FROM inline view must receive a PostgreSQL-15-safe alias");

        try {
            wrapped.prepareStatement("SELECT CASE WHEN 1 = 1 THEN 1 ELSE '2' END FROM DUAL");
            throw new AssertionError("incompatible CASE result datatypes must fail like Oracle");
        } catch (SQLException ex) {
            equal("42000", ex.getSQLState(), "CASE datatype SQLState");
            equal(932, ex.getErrorCode(), "CASE datatype Oracle vendor code");
        }

        try {
            wrapped.prepareStatement("SELECT NVL(, 1) FROM DUAL");
            throw new AssertionError("structurally invalid Oracle SQL must fail as SQL syntax");
        } catch (SQLException ex) {
            equal("42000", ex.getSQLState(), "parser failure SQLState");
        }

        wrapped.prepareStatement("SELECT id FROM items ORDER BY ?");
        equal(
                "SELECT id FROM items ORDER BY ?",
                recorder.preparedSql,
                "ORDER BY bind must remain a constant value expression");

        CallableStatement returning =
                wrapped.prepareCall("INSERT INTO t(v) VALUES (?) RETURNING id, v INTO ?, ?");
        String preparedUpper = recorder.preparedSql.toUpperCase();
        check(
                preparedUpper.contains("RETURNING ID, V"),
                "RETURNING expressions must reach delegate");
        check(
                preparedUpper.indexOf(" INTO ", preparedUpper.indexOf("RETURNING")) < 0,
                "INTO binds must not reach delegate");
        returning.setString(1, "created");
        returning.registerOutParameter(2, Types.INTEGER);
        returning.registerOutParameter(3, Types.VARCHAR);
        equal(1, returning.executeUpdate(), "RETURNING update count");
        equal(7, returning.getInt(2), "RETURNING numeric OUT value");
        equal("created", returning.getString(3), "RETURNING text OUT value");
        check(!returning.wasNull(), "non-null OUT value must clear wasNull");
        equal(
                List.of(1, 1),
                recorder.setIndexes,
                "RETURNING OUT binds must not become delegate binds");

        recorder.setIndexes.clear();
        CallableStatement function =
                wrapped.prepareCall("BEGIN ? := payroll_pkg.employee_name(?); END;");
        check(
                recorder.preparedSql.toUpperCase().startsWith("SELECT PAYROLL_PKG.EMPLOYEE_NAME"),
                "PL/SQL function call must prepare SELECT");
        function.registerOutParameter(1, Types.VARCHAR);
        function.setInt(2, 7);
        check(
                !function.execute(),
                "function CallableStatement execute must hide internal ResultSet");
        equal("employee-7", function.getString(1), "function return value");
        equal(
                List.of(1),
                recorder.setIndexes,
                "function return bind must be omitted from delegate binds");

        recorder.setIndexes.clear();
        CallableStatement procedure = wrapped.prepareCall("BEGIN payroll_pkg.recalculate(?); END;");
        check(
                recorder.preparedSql.toUpperCase().startsWith("CALL PAYROLL_PKG.RECALCULATE"),
                "PL/SQL procedure call must prepare CALL");
        procedure.setInt(1, 7);
        check(!procedure.execute(), "input-only procedure execute result");
        equal(List.of(1), recorder.setIndexes, "procedure bind mapping");
        try {
            procedure.registerOutParameter(1, Types.INTEGER);
            throw new AssertionError("IN-only procedure parameter must reject OUT registration");
        } catch (SQLException ex) {
            equal("07009", ex.getSQLState(), "procedure OUT SQLState");
        }

        expectPrepareCallReject(
                DatabaseMetaData.procedureColumnOut,
                Types.INTEGER,
                1,
                "BEGIN p(? + 0); END;",
                "UNSAFE_OUT_EXPRESSION");
        expectPrepareCallReject(
                DatabaseMetaData.procedureColumnOut,
                Types.REF_CURSOR,
                1,
                "BEGIN p(?); END;",
                "REF_CURSOR_OUTPUT");
        expectPrepareCallReject(
                DatabaseMetaData.procedureColumnIn,
                Types.INTEGER,
                2,
                "BEGIN p(?); END;",
                "ROUTINE_OVERLOAD_AMBIGUOUS");

        try {
            wrapped.prepareStatement("BEGIN payroll_pkg.recalculate(?); END;");
            throw new AssertionError("routine call through prepareStatement must fail closed");
        } catch (SQLException ex) {
            equal("0A000", ex.getSQLState(), "routine prepareStatement SQLState");
        }

        try {
            wrapped.prepareStatement("SELECT DECODE(status, other_column, 1, 0) FROM emp");
            throw new AssertionError(
                    "ambiguous Oracle DECODE must fail before delegate prepareStatement");
        } catch (SQLException ex) {
            equal("0A000", ex.getSQLState(), "translation failure SQLState");
        }

        System.out.println("JdbcRuntimeTest OK");
    }

    private static void expectPrepareCallReject(
            int mode, int sqlType, int rowCount, String sql, String code) throws Exception {
        Connection wrapped =
                new JdbcRuntime().wrap(new Recorder().connection(mode, sqlType, rowCount));
        try {
            wrapped.prepareCall(sql);
            throw new AssertionError("expected prepareCall rejection " + code);
        } catch (SQLException ex) {
            equal("0A000", ex.getSQLState(), code + " SQLState");
            check(
                    ex.getMessage() != null && ex.getMessage().startsWith(code + ":"),
                    code + " message code");
        }
    }

    private static final class Recorder {
        String preparedSql;
        String statementSql;
        final List<Integer> setIndexes = new ArrayList<>();
        int cursor;

        Connection connection() {
            return connection(DatabaseMetaData.procedureColumnIn, Types.INTEGER, 1);
        }

        Connection connection(int metadataMode, int metadataSqlType, int metadataRowCount) {
            int[] metadataCursor = {0};
            ResultSet metadataRows =
                    proxy(
                            ResultSet.class,
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "next" -> ++metadataCursor[0] <= metadataRowCount;
                                        case "getShort" -> (short) metadataMode;
                                        case "getString" ->
                                                switch ((String) args[0]) {
                                                    case "SPECIFIC_NAME" ->
                                                            "recalculate_" + metadataCursor[0];
                                                    case "COLUMN_NAME" -> "employee_id";
                                                    default -> null;
                                                };
                                        case "getInt" ->
                                                switch ((String) args[0]) {
                                                    case "DATA_TYPE" -> metadataSqlType;
                                                    case "ORDINAL_POSITION" -> 1;
                                                    default -> 0;
                                                };
                                        default -> defaultValue(method.getReturnType());
                                    });
            DatabaseMetaData metadata =
                    proxy(
                            DatabaseMetaData.class,
                            (proxy, method, args) -> {
                                if (method.getName().equals("getProcedureColumns")) {
                                    metadataCursor[0] = 0;
                                    return metadataRows;
                                }
                                return defaultValue(method.getReturnType());
                            });
            ResultSet resultSet =
                    proxy(
                            ResultSet.class,
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "next" -> ++cursor == 1;
                                        case "getObject" -> {
                                            if (preparedSql != null
                                                    && preparedSql
                                                            .toLowerCase()
                                                            .contains("employee_name"))
                                                yield "employee-7";
                                            yield ((Integer) args[0]) == 1 ? 7 : "created";
                                        }
                                        default -> defaultValue(method.getReturnType());
                                    });
            PreparedStatement prepared =
                    proxy(
                            PreparedStatement.class,
                            (proxy, method, args) -> {
                                if (method.getName().startsWith("set")
                                        && args != null
                                        && args.length > 0
                                        && args[0] instanceof Integer i) {
                                    setIndexes.add(i);
                                }
                                if (method.getName().equals("execute")) {
                                    cursor = 0;
                                    return preparedSql != null
                                            && !preparedSql.toUpperCase().startsWith("CALL ");
                                }
                                if (method.getName().equals("getResultSet")) return resultSet;
                                if (method.getName().equals("getUpdateCount")) return -1;
                                return defaultValue(method.getReturnType());
                            });
            Statement statement =
                    proxy(
                            Statement.class,
                            (proxy, method, args) -> {
                                if (args != null
                                        && args.length > 0
                                        && args[0] instanceof String sql) statementSql = sql;
                                return defaultValue(method.getReturnType());
                            });
            return proxy(
                    Connection.class,
                    (proxy, method, args) ->
                            switch (method.getName()) {
                                case "prepareStatement" -> {
                                    if (((String) args[0]).contains("pg_catalog.to_regclass")) {
                                        // Catalog lookup has its own statement and binds; the
                                        // recorder below observes only the application's statement.
                                        yield proxy(
                                                PreparedStatement.class,
                                                (lookup, lookupMethod, lookupArgs) ->
                                                        defaultValue(lookupMethod.getReturnType()));
                                    }
                                    preparedSql = (String) args[0];
                                    yield prepared;
                                }
                                case "createStatement" -> statement;
                                case "nativeSQL" -> args[0];
                                case "getMetaData" -> metadata;
                                default -> defaultValue(method.getReturnType());
                            });
        }
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
