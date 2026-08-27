package io.github.orafit.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/** End-to-end smoke test from jdbc:orafit: URL to translated delegate SQL. */
public final class OrafitDriverTest {
    @Test
    void contract() throws Exception {
        FakePostgresDriver delegate = new FakePostgresDriver();
        DriverManager.registerDriver(delegate);
        try {
            OrafitDriver driver = new OrafitDriver();
            int[] releaseCandidate = OrafitDriver.versionParts("0.1.0-rc.1");
            equal(0, releaseCandidate[0], "driver major version parsing");
            equal(1, releaseCandidate[1], "driver minor version parsing");
            check(driver.acceptsURL("jdbc:orafit://db:5432/app"), "Orafit URL must be accepted");
            check(
                    !driver.acceptsURL("jdbc:postgresql://db:5432/app"),
                    "PostgreSQL URL must not be claimed");

            Properties properties = new Properties();
            properties.setProperty("user", "app");
            Connection connection =
                    driver.connect("jdbc:orafit://db:5432/app?sslmode=require", properties);
            equal("jdbc:postgresql://db:5432/app?sslmode=require", delegate.url, "delegate URL");
            equal("app", delegate.properties.getProperty("user"), "delegate property");
            equal("always", delegate.properties.getProperty("autosave"), "Oracle autosave default");
            equal(
                    "true",
                    delegate.properties.getProperty("cleanupSavepoints"),
                    "autosave cleanup default");
            equal(
                    "unspecified",
                    delegate.properties.getProperty("stringtype"),
                    "PostgreSQL context-inferred string bind default");

            PreparedStatement prepared =
                    connection.prepareStatement("SELECT NVL(?, 'x') FROM DUAL");
            check(
                    delegate.sql.toLowerCase().contains("orafit.nvl"),
                    "driver path must translate NVL");
            check(
                    delegate.sql.toLowerCase().contains("orafit.dual"),
                    "driver path must translate DUAL");
            prepared.setInt(1, 7);
            equal(List.of(1), delegate.binds, "driver path bind mapping");

            Properties nativeTransactions = new Properties();
            nativeTransactions.setProperty("autosave", "never");
            nativeTransactions.setProperty("cleanupSavepoints", "false");
            driver.connect("jdbc:orafit://db:5432/app", nativeTransactions);
            equal(
                    "never",
                    delegate.properties.getProperty("autosave"),
                    "explicit autosave override");
            equal(
                    "false",
                    delegate.properties.getProperty("cleanupSavepoints"),
                    "explicit cleanup override");
        } finally {
            DriverManager.deregisterDriver(delegate);
        }
        System.out.println("OrafitDriverTest OK");
    }

    private static final class FakePostgresDriver implements Driver {
        String url;
        Properties properties;
        String sql;
        final List<Integer> binds = new ArrayList<>();

        @Override
        public Connection connect(String candidate, Properties info) {
            if (!acceptsURL(candidate)) return null;
            url = candidate;
            properties = new Properties();
            properties.putAll(info);
            PreparedStatement prepared =
                    proxy(
                            PreparedStatement.class,
                            (proxy, method, args) -> {
                                if (method.getName().startsWith("set")
                                        && args != null
                                        && args[0] instanceof Integer index) {
                                    binds.add(index);
                                }
                                return defaultValue(method.getReturnType());
                            });
            return proxy(
                    Connection.class,
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            sql = (String) args[0];
                            return prepared;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        @Override
        public boolean acceptsURL(String candidate) {
            return candidate != null && candidate.startsWith("jdbc:postgresql:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 42;
        }

        @Override
        public int getMinorVersion() {
            return 7;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("fake-postgres");
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
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
        if (!expected.equals(actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
