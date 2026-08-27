package io.github.orafit.jdbc;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;

/**
 * JDBC execution boundary that translates SQL before delegating ordinary JDBC work to pgJDBC.
 *
 * <p>This class owns connection and statement routing. Prepared, RETURNING, function-call, and
 * procedure execution models live in their dedicated runtime adapters.
 */
public final class JdbcRuntime {
    private final OrafitEngine engine;

    /** Creates a runtime backed by the standard translation engine. */
    public JdbcRuntime() {
        this(new OrafitEngine());
    }

    /** Creates a runtime with an explicit translation engine, primarily for focused tests. */
    public JdbcRuntime(OrafitEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Wraps one pgJDBC connection and preserves delegation for behavior Orafit does not own. */
    public Connection wrap(Connection delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return JdbcProxy.proxy(Connection.class, new ConnectionHandler(delegate));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        private final ColumnTypeResolver columnTypes;

        private ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
            this.columnTypes = new JdbcColumnTypeResolver(delegate);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("nativeSQL")
                    && args != null
                    && args.length == 1
                    && args[0] instanceof String sql) {
                Translation translated = translate(sql, columnTypes);
                rejectSpecialWithoutCall(translated);
                return JdbcProxy.invokeDelegate(method, delegate, new Object[] {translated.sql()});
            }

            if (name.equals("prepareStatement")
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String sql) {
                Translation translated = translate(sql, columnTypes);
                rejectSpecialWithoutCall(translated);
                if (translated.script().present()) {
                    return PreparedRuntime.wrapScript(
                            delegate,
                            translated.script(),
                            translated.metadata(),
                            (Connection) proxy,
                            translated.binds().inputCount(),
                            args);
                }
                Object[] mapped = args.clone();
                mapped[0] = translated.sql();
                PreparedStatement prepared =
                        (PreparedStatement) JdbcProxy.invokeDelegate(method, delegate, mapped);
                return PreparedRuntime.wrap(prepared, translated, (Connection) proxy);
            }

            if (name.equals("prepareCall")
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String sql) {
                Translation translated = translate(sql, columnTypes);
                if (translated.call().present()) {
                    return FunctionCallRuntime.prepare(
                            delegate, translated, args, (Connection) proxy);
                }
                if (translated.returning().present()) {
                    return ReturningRuntime.prepare(delegate, translated, args, (Connection) proxy);
                }
                throw new SQLFeatureNotSupportedException(
                        "CallableStatement requires a supported routine call or scalar"
                                + " RETURNING INTO",
                        "0A000");
            }

            if (name.equals("createStatement")) {
                Statement statement = (Statement) JdbcProxy.invokeDelegate(method, delegate, args);
                return JdbcProxy.proxy(
                        Statement.class,
                        new StatementHandler(statement, (Connection) proxy, columnTypes));
            }

            if (name.equals("getMetaData")) {
                DatabaseMetaData metadata =
                        (DatabaseMetaData) JdbcProxy.invokeDelegate(method, delegate, args);
                return JdbcProxy.proxy(
                        DatabaseMetaData.class,
                        (metadataProxy, metadataMethod, metadataArgs) ->
                                metadataMethod.getName().equals("getConnection")
                                        ? proxy
                                        : JdbcProxy.invokeDelegate(
                                                metadataMethod, metadata, metadataArgs));
            }
            return JdbcProxy.invokeDelegate(method, delegate, args);
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Statement delegate;
        private final Connection owner;
        private final ColumnTypeResolver columnTypes;
        private ResultMetadataPlan metadata = ResultMetadataPlan.none();

        private StatementHandler(
                Statement delegate, Connection owner, ColumnTypeResolver columnTypes) {
            this.delegate = delegate;
            this.owner = owner;
            this.columnTypes = columnTypes;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("getConnection")) return owner;
            if (sqlMethod(method, args)) {
                Translation translated = translate((String) args[0], columnTypes);
                rejectSpecialWithoutCall(translated);
                if (translated.script().present()) {
                    throw JdbcProxy.unsupported(
                            "Anonymous DML blocks require Connection.prepareStatement()");
                }
                metadata = translated.metadata();
                Object[] mapped = args.clone();
                mapped[0] = translated.sql();
                return JdbcMetadata.oracleResult(
                        JdbcProxy.invokeDelegate(method, delegate, mapped),
                        metadata,
                        (Statement) proxy);
            }
            return JdbcMetadata.oracleResult(
                    JdbcProxy.invokeDelegate(method, delegate, args), metadata, (Statement) proxy);
        }
    }

    private Translation translate(String sql) throws SQLException {
        return translate(sql, ColumnTypeResolver.NONE);
    }

    private Translation translate(String sql, ColumnTypeResolver columnTypes) throws SQLException {
        try {
            return engine.translate(sql, columnTypes);
        } catch (TranslationException failure) {
            throw JdbcErrors.fromTranslation(failure);
        }
    }

    private static void rejectSpecialWithoutCall(Translation translation)
            throws SQLFeatureNotSupportedException {
        if (translation.returning().present()) {
            throw new SQLFeatureNotSupportedException(
                    "Oracle RETURNING INTO requires Connection.prepareCall() for OUT parameters",
                    "0A000");
        }
        if (translation.call().present()) {
            throw new SQLFeatureNotSupportedException(
                    "Oracle routine calls require Connection.prepareCall()", "0A000");
        }
    }

    private static boolean sqlMethod(Method method, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) return false;
        return switch (method.getName()) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch" ->
                    true;
            default -> false;
        };
    }
}
