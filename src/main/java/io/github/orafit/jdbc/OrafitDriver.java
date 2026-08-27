package io.github.orafit.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC entry point for Oracle-oriented applications connecting to PostgreSQL through Orafit.
 *
 * <p>URLs use the {@code jdbc:orafit:} prefix. The remainder is delegated to pgJDBC, while returned
 * connections are wrapped so SQL translation and JDBC compatibility behavior are applied at
 * statement boundaries. pgJDBC itself is intentionally not bundled.
 *
 * <p>Oracle keeps an explicit transaction usable after a statement-level failure. Orafit preserves
 * that behavior by defaulting pgJDBC to {@code autosave=always} with {@code
 * cleanupSavepoints=true}. Explicit pgJDBC connection properties or URL parameters can override
 * these defaults.
 */
public final class OrafitDriver implements Driver {
    /** Prefix accepted by this driver before the underlying PostgreSQL JDBC URL body. */
    public static final String URL_PREFIX = "jdbc:orafit:";

    private static final String PG_PREFIX = "jdbc:postgresql:";
    private static final String AUTOSAVE = "autosave";
    private static final String CLEANUP_SAVEPOINTS = "cleanupSavepoints";
    private static final String STRING_TYPE = "stringtype";
    private static final int[] VERSION =
            versionParts(OrafitDriver.class.getPackage().getImplementationVersion());

    static {
        try {
            DriverManager.registerDriver(new OrafitDriver());
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        String postgresUrl = postgresUrl(url);
        Properties properties = postgresProperties(info);

        Connection delegate = connectPostgres(postgresUrl, properties);
        if (delegate == null) {
            throw new SQLNonTransientConnectionException(
                    "No PostgreSQL JDBC driver accepted " + postgresUrl, "08001");
        }
        return new JdbcRuntime().wrap(delegate);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return new DriverPropertyInfo[0];
        Properties properties = postgresProperties(info);
        String postgresUrl = postgresUrl(url);
        Driver driver;
        try {
            driver = DriverManager.getDriver(postgresUrl);
        } catch (SQLException ignored) {
            driver = postgresDriver();
        }
        return driver.getPropertyInfo(postgresUrl, properties);
    }

    @Override
    public int getMajorVersion() {
        return VERSION[0];
    }

    @Override
    public int getMinorVersion() {
        return VERSION[1];
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("io.github.orafit.jdbc");
    }

    static int[] versionParts(String version) {
        int[] parts = {0, 0};
        if (version == null || version.isBlank()) return parts;
        String[] tokens = version.split("[.-]");
        for (int i = 0; i < parts.length && i < tokens.length; i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i]);
            } catch (NumberFormatException ignored) {
                return new int[] {0, 0};
            }
        }
        return parts;
    }

    private static String postgresUrl(String url) throws SQLException {
        if (!url.startsWith(URL_PREFIX) || url.length() == URL_PREFIX.length()) {
            throw new SQLNonTransientConnectionException(
                    "Invalid Orafit JDBC URL: " + url, "08001");
        }
        return PG_PREFIX + url.substring(URL_PREFIX.length());
    }

    private static Properties postgresProperties(Properties info) {
        Properties properties = new Properties();
        if (info != null) properties.putAll(info);
        properties.putIfAbsent(AUTOSAVE, "always");
        properties.putIfAbsent(CLEANUP_SAVEPOINTS, "true");
        properties.putIfAbsent(STRING_TYPE, "unspecified");
        return properties;
    }

    private static Connection connectPostgres(String url, Properties properties)
            throws SQLException {
        try {
            DriverManager.getDriver(url);
        } catch (SQLException ignored) {
            return postgresDriver().connect(url, properties);
        }
        return DriverManager.getConnection(url, properties);
    }

    private static Driver postgresDriver() throws SQLException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        try {
            return instantiatePostgresDriver(context);
        } catch (ReflectiveOperationException first) {
            try {
                return instantiatePostgresDriver(OrafitDriver.class.getClassLoader());
            } catch (ReflectiveOperationException second) {
                String message =
                        first instanceof ClassNotFoundException
                                ? "PostgreSQL JDBC driver is not available to Orafit"
                                : "Could not instantiate the PostgreSQL JDBC driver";
                SQLNonTransientConnectionException failure =
                        new SQLNonTransientConnectionException(message, "08001", second);
                failure.addSuppressed(first);
                throw failure;
            }
        }
    }

    private static Driver instantiatePostgresDriver(ClassLoader loader)
            throws ReflectiveOperationException {
        return (Driver)
                Class.forName("org.postgresql.Driver", true, loader)
                        .getDeclaredConstructor()
                        .newInstance();
    }
}
