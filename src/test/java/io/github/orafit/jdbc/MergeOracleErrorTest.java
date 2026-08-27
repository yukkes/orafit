package io.github.orafit.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/** Verifies Oracle-visible JDBC error behavior for the MERGE ON-column update restriction. */
public final class MergeOracleErrorTest {
    @Test
    void contract() throws Exception {
        Connection delegate =
                proxy(
                        Connection.class,
                        (proxy, method, methodArgs) -> defaultValue(method.getReturnType()));
        Connection wrapped = new JdbcRuntime().wrap(delegate);
        try {
            wrapped.prepareStatement(
                    "MERGE INTO target_table t USING source_table s ON (t.id = s.id) "
                            + "WHEN MATCHED THEN UPDATE SET t.id = t.id + 1");
            throw new AssertionError(
                    "MERGE ON-column update must be rejected before delegate preparation");
        } catch (SQLException ex) {
            equal(38104, ex.getErrorCode(), "Oracle MERGE error code");
        }
        System.out.println("MergeOracleErrorTest OK");
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

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
