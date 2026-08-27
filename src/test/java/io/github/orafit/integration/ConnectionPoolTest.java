package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.orafit.jdbc.OrafitDriver;

import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Verifies that Orafit remains active behind common JDBC connection-pool entry points. */
final class ConnectionPoolTest {
    @Test
    void hikariUsesOrafitDriverAndReturnsTranslatedConnections() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(IntegrationSupport.jdbcUrl());
        config.setUsername(IntegrationSupport.jdbcUser());
        config.setPassword(IntegrationSupport.jdbcPassword());
        config.setDriverClassName("io.github.orafit.jdbc.OrafitDriver");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);

        try (HikariDataSource dataSource = new HikariDataSource(config);
                Connection connection = dataSource.getConnection()) {
            assertScalar(connection, "hikari");
        }
    }

    @Test
    void dbcp2UsesOrafitDriverAndReturnsTranslatedConnections() throws Exception {
        try (BasicDataSource dataSource = new BasicDataSource()) {
            dataSource.setUrl(IntegrationSupport.jdbcUrl());
            dataSource.setUsername(IntegrationSupport.jdbcUser());
            dataSource.setPassword(IntegrationSupport.jdbcPassword());
            dataSource.setDriverClassName("io.github.orafit.jdbc.OrafitDriver");
            dataSource.setMaxTotal(1);
            dataSource.setMinIdle(0);

            try (Connection connection = dataSource.getConnection()) {
                assertScalar(connection, "dbcp2");
            }
        }
    }

    @Test
    void contextLoaderFallbackConnectsWhenPgjdbcIsClassloaderIsolated() throws Exception {
        String url = IntegrationSupport.jdbcUrl();
        Class<?> applicationPgjdbc = Class.forName("org.postgresql.Driver");
        URL pgjdbcLocation = applicationPgjdbc.getProtectionDomain().getCodeSource().getLocation();

        List<Driver> removed = new ArrayList<>();
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getName().equals("org.postgresql.Driver")) {
                DriverManager.deregisterDriver(driver);
                removed.add(driver);
            }
        }

        Properties properties = new Properties();
        properties.setProperty("user", IntegrationSupport.jdbcUser());
        if (IntegrationSupport.jdbcPassword() != null) {
            properties.setProperty("password", IntegrationSupport.jdbcPassword());
        }

        Thread thread = Thread.currentThread();
        ClassLoader previousContextLoader = thread.getContextClassLoader();
        try (URLClassLoader isolatedLoader =
                new URLClassLoader(
                        new URL[] {pgjdbcLocation}, ClassLoader.getPlatformClassLoader())) {
            Class<?> isolatedPgjdbc = Class.forName("org.postgresql.Driver", false, isolatedLoader);
            assertNotSame(applicationPgjdbc.getClassLoader(), isolatedPgjdbc.getClassLoader());

            thread.setContextClassLoader(isolatedLoader);
            try (Connection connection = new OrafitDriver().connect(url, properties)) {
                assertScalar(connection, "fallback");
            }
        } finally {
            thread.setContextClassLoader(previousContextLoader);
            for (Driver driver : removed) DriverManager.registerDriver(driver);
        }
    }

    private static void assertScalar(Connection connection, String expected) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT NVL(NULL, '" + expected + "') FROM DUAL")) {
            assertTrue(result.next());
            assertEquals(expected, result.getString(1));
        }
    }
}
