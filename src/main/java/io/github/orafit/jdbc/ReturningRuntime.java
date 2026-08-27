package io.github.orafit.jdbc;

import io.github.orafit.translation.BindLineage;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.ReturningPlan;
import io.github.orafit.translation.Translation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** CallableStatement adapter for the scalar Oracle RETURNING INTO contract. */
final class ReturningRuntime {
    private ReturningRuntime() {}

    static CallableStatement prepare(
            Connection connection,
            Translation translation,
            Object[] prepareCallArgs,
            Connection owner)
            throws SQLException {
        PreparedStatement delegate =
                JdbcProxy.prepareStatement(
                        connection, translation.sql(), prepareCallArgs, "RETURNING INTO");
        return JdbcProxy.proxy(
                CallableStatement.class,
                new Handler(
                        delegate,
                        translation.binds(),
                        translation.returning(),
                        translation.metadata(),
                        owner));
    }

    private static final class Handler implements InvocationHandler {
        private final PreparedStatement delegate;
        private final BindLineage binds;
        private final ReturningPlan returning;
        private final ResultMetadataPlan metadata;
        private final Connection owner;
        private final int[] bindWidths;
        private final Set<Integer> registered = new HashSet<>();
        private final Map<Integer, Object> outputValues = new HashMap<>();
        private boolean lastOutputWasNull;
        private int updateCount = -1;

        private Handler(
                PreparedStatement delegate,
                BindLineage binds,
                ReturningPlan returning,
                ResultMetadataPlan metadata,
                Connection owner) {
            this.delegate = delegate;
            this.binds = binds;
            this.returning = returning;
            this.metadata = metadata;
            this.owner = owner;
            this.bindWidths = new int[binds.inputCount() + 1];
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return owner;
            if (name.equals("getMetaData")) {
                Object value = JdbcProxy.invokeDelegate(method, delegate, args);
                return value instanceof ResultSetMetaData raw
                        ? JdbcMetadata.oracleMetadata(raw, metadata, bindWidths, owner)
                        : value;
            }
            if (name.equals("getParameterMetaData")) {
                throw new SQLFeatureNotSupportedException(
                        "Oracle RETURNING INTO parameter metadata is not modeled", "0A000");
            }
            if (name.equals("clearParameters")) {
                Arrays.fill(bindWidths, 0);
                outputValues.clear();
                updateCount = -1;
                lastOutputWasNull = false;
                return JdbcProxy.invokeDelegate(method, delegate, args);
            }
            if (name.equals("registerOutParameter") && JdbcProxy.indexed(args)) {
                int index = (Integer) args[0];
                if (!returning.isOutput(index)) {
                    throw JdbcProxy.badIndex(index, "is not a RETURNING OUT parameter");
                }
                registered.add(index);
                return null;
            }
            if (JdbcProxy.callableGetter(method, args)) return outputValue(method, args);
            if (JdbcProxy.routineStatus(name))
                return JdbcProxy.routineStatus(name, lastOutputWasNull, updateCount);

            if (JdbcProxy.parameterSetter(method, args)) {
                int input = (Integer) args[0];
                if (returning.isOutput(input)) {
                    throw JdbcProxy.badIndex(input, "is an OUT parameter and cannot be assigned");
                }
                JdbcBinds.mapSetter(
                        delegate, binds, method, args, input, "the supported Orafit contract");
                bindWidths[input] = JdbcBinds.bindWidth(method, args);
                return null;
            }
            if (name.equals("executeQuery")) {
                throw new SQLFeatureNotSupportedException(
                        "Use execute(), executeUpdate(), or executeLargeUpdate() for RETURNING"
                                + " INTO",
                        "0A000");
            }
            if (JdbcProxy.executeCall(name)) return executeReturning(name);
            if (JdbcProxy.batchCall(name)) {
                throw new SQLFeatureNotSupportedException(
                        "Batch RETURNING INTO is not supported", "0A000");
            }
            if (method.getDeclaringClass() == CallableStatement.class
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String) {
                throw new SQLFeatureNotSupportedException(
                        "Named CallableStatement parameters are not supported for RETURNING INTO",
                        "0A000");
            }
            if (method.getDeclaringClass() == CallableStatement.class) {
                throw new SQLFeatureNotSupportedException(
                        "CallableStatement method is outside scalar RETURNING INTO: " + name,
                        "0A000");
            }
            return JdbcMetadata.oracleResult(
                    JdbcProxy.invokeDelegate(method, delegate, args),
                    metadata,
                    bindWidths,
                    (Statement) proxy);
        }

        private Object executeReturning(String methodName) throws SQLException {
            for (ReturningPlan.Output output : returning.outputs()) {
                if (!registered.contains(output.parameterIndex())) {
                    throw JdbcProxy.badIndex(
                            output.parameterIndex(), "must be registered before execution");
                }
            }
            outputValues.clear();
            lastOutputWasNull = false;
            updateCount = -1;
            int rows = 0;
            boolean hasResult = delegate.execute();
            if (hasResult) {
                try (ResultSet result = delegate.getResultSet()) {
                    if (result != null && result.next()) {
                        rows = 1;
                        for (ReturningPlan.Output output : returning.outputs()) {
                            outputValues.put(
                                    output.parameterIndex(),
                                    result.getObject(output.resultOrdinal()));
                        }
                        if (result.next()) {
                            outputValues.clear();
                            throw new SQLException(
                                    "ORA-01422: exact fetch returns more than requested number of"
                                            + " rows",
                                    "21000",
                                    1422);
                        }
                    }
                }
            } else if (delegate.getUpdateCount() > 0) {
                throw new SQLException(
                        "PostgreSQL delegate did not expose the RETURNING result set", "HY000");
            }
            for (ReturningPlan.Output output : returning.outputs()) {
                outputValues.putIfAbsent(output.parameterIndex(), null);
            }
            updateCount = rows;
            return JdbcProxy.executeOutcome(methodName, rows);
        }

        private Object outputValue(Method method, Object[] args) throws SQLException {
            int index = (Integer) args[0];
            if (!returning.isOutput(index)) {
                throw JdbcProxy.badIndex(index, "is not a RETURNING OUT parameter");
            }
            if (!registered.contains(index)) {
                throw JdbcProxy.badIndex(index, "has not been registered");
            }
            if (!outputValues.containsKey(index)) {
                throw new SQLException("OUT value is unavailable before execution", "HY010");
            }
            Object value = outputValues.get(index);
            lastOutputWasNull = value == null;
            return JdbcValue.get(value, method, args, "RETURNING INTO");
        }
    }
}
