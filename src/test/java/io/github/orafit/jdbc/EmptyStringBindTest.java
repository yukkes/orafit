package io.github.orafit.jdbc;

import io.github.orafit.translation.BindLineage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Verifies the Oracle zero-length character bind boundary without a database. */
public final class EmptyStringBindTest {
    @Test
    void contract() throws Throwable {
        List<String> calls = new ArrayList<>();
        PreparedStatement delegate =
                (PreparedStatement)
                        Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (proxy, method, methodArgs) -> {
                                    if (method.getName().equals("setNull")) {
                                        calls.add("setNull:" + methodArgs[0] + ":" + methodArgs[1]);
                                    } else if (method.getName().equals("setString")
                                            || method.getName().equals("setNString")) {
                                        calls.add(
                                                method.getName()
                                                        + ":"
                                                        + methodArgs[0]
                                                        + ":"
                                                        + methodArgs[1]);
                                    } else if (method.getName().equals("setObject")) {
                                        calls.add(
                                                "setObject:"
                                                        + methodArgs[0]
                                                        + ":"
                                                        + methodArgs[1]
                                                        + (methodArgs.length > 2
                                                                ? ":" + methodArgs[2]
                                                                : ""));
                                    }
                                    return defaultValue(method.getReturnType());
                                });

        Method setString = PreparedStatement.class.getMethod("setString", int.class, String.class);
        JdbcBinds.mapSetter(
                delegate, BindLineage.identity(1), setString, new Object[] {1, ""}, 1, "self-test");
        equal(
                List.of("setNull:1:" + Types.VARCHAR),
                calls,
                "empty setString must become VARCHAR NULL");

        calls.clear();
        JdbcBinds.mapSetter(
                delegate,
                BindLineage.identity(1),
                setString,
                new Object[] {1, "value"},
                1,
                "self-test");
        equal(List.of("setString:1:value"), calls, "non-empty setString must be preserved");

        calls.clear();
        Method setNString =
                PreparedStatement.class.getMethod("setNString", int.class, String.class);
        JdbcBinds.mapSetter(
                delegate,
                BindLineage.identity(1),
                setNString,
                new Object[] {1, ""},
                1,
                "self-test");
        equal(
                List.of("setNull:1:" + Types.NVARCHAR),
                calls,
                "empty setNString must become NVARCHAR NULL");

        calls.clear();
        BindLineage duplicated = new BindLineage(1, List.of(1, 1));
        JdbcBinds.mapSetter(delegate, duplicated, setString, new Object[] {1, ""}, 1, "self-test");
        equal(
                List.of("setNull:1:" + Types.VARCHAR, "setNull:2:" + Types.VARCHAR),
                calls,
                "empty character bind must preserve duplicated bind lineage");

        calls.clear();
        Method untypedSetObject =
                PreparedStatement.class.getMethod("setObject", int.class, Object.class);
        JdbcBinds.mapSetter(
                delegate,
                BindLineage.identity(1),
                untypedSetObject,
                new Object[] {1, null},
                1,
                "self-test");
        equal(
                List.of("setObject:1:null"),
                calls,
                "untyped setObject null must retain PostgreSQL context inference");

        calls.clear();
        Method untypedSetNull = PreparedStatement.class.getMethod("setNull", int.class, int.class);
        JdbcBinds.mapSetter(
                delegate,
                BindLineage.identity(1),
                untypedSetNull,
                new Object[] {1, Types.OTHER},
                1,
                "self-test");
        equal(
                List.of("setNull:1:" + Types.OTHER),
                calls,
                "untyped setNull must retain PostgreSQL context inference");

        calls.clear();
        Method typedSetObject =
                PreparedStatement.class.getMethod(
                        "setObject", int.class, Object.class, SQLType.class);
        JdbcBinds.mapSetter(
                delegate,
                BindLineage.identity(1),
                typedSetObject,
                new Object[] {1, null, null},
                1,
                "self-test");
        equal(
                List.of("setObject:1:null:null"),
                calls,
                "explicit SQLType overload must remain delegated");

        System.out.println("EmptyStringBindTest OK");
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
        throw new AssertionError("unsupported primitive: " + type);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
