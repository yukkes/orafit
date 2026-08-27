package io.github.orafit.jdbc;

import io.github.orafit.translation.BindLineage;
import io.github.orafit.translation.Translation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/** Metadata-backed procedure adapter for IN, OUT, and INOUT parameters. */
final class ProcedureRuntime {
    private ProcedureRuntime() {}

    static CallableStatement prepare(
            Connection connection,
            Translation translation,
            Object[] prepareCallArgs,
            Connection owner)
            throws SQLException {
        RoutineMetadataResolver.Signature signature =
                RoutineMetadataResolver.resolve(connection, translation.call());
        PreparedStatement delegate =
                JdbcProxy.prepareStatement(
                        connection, translation.sql(), prepareCallArgs, "Oracle procedure call");
        return JdbcProxy.proxy(
                CallableStatement.class,
                new Handler(delegate, translation.binds(), signature, owner));
    }

    private static final class Handler implements InvocationHandler {
        private final PreparedStatement delegate;
        private final BindLineage binds;
        private final RoutineMetadataResolver.Signature signature;
        private final Connection owner;
        private final Set<Integer> registered = new HashSet<>();
        private final Map<Integer, Object> outputValues = new HashMap<>();
        private boolean executed;
        private boolean lastWasNull;

        private Handler(
                PreparedStatement delegate,
                BindLineage binds,
                RoutineMetadataResolver.Signature signature,
                Connection owner) {
            this.delegate = delegate;
            this.binds = binds;
            this.signature = signature;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return owner;
            if (name.equals("getParameterMetaData"))
                throw JdbcProxy.unsupported("Oracle procedure parameter metadata is not modeled");
            if (name.equals("registerOutParameter") && JdbcProxy.indexed(args)) {
                int index = (Integer) args[0];
                RoutineMetadataResolver.Binding binding = signature.binding(index);
                if (binding == null || !binding.mode().output())
                    throw JdbcProxy.badIndex(index, "is not a procedure OUT parameter");
                registered.add(index);
                return null;
            }
            if (JdbcProxy.callableGetter(method, args)) return outputValue(method, args);
            if (JdbcProxy.routineStatus(name))
                return JdbcProxy.routineStatus(name, lastWasNull, -1);

            if (JdbcProxy.parameterSetter(method, args)) {
                int input = (Integer) args[0];
                RoutineMetadataResolver.Binding binding = signature.binding(input);
                if (binding != null && !binding.mode().input()) {
                    throw JdbcProxy.badIndex(
                            input, "is an OUT-only parameter and cannot be assigned");
                }
                JdbcBinds.mapSetter(delegate, binds, method, args, input, "the procedure adapter");
                return null;
            }
            if (name.equals("clearParameters")) {
                outputValues.clear();
                executed = false;
                lastWasNull = false;
                return JdbcProxy.invokeDelegate(method, delegate, args);
            }
            if (JdbcProxy.executeCall(name)) return executeProcedure(name);
            JdbcProxy.rejectRoutineExtras(name, method, "procedure");
            return JdbcProxy.invokeDelegate(method, delegate, args);
        }

        private Object executeProcedure(String methodName) throws SQLException {
            outputValues.clear();
            executed = false;
            lastWasNull = false;
            for (RoutineMetadataResolver.Binding output : signature.outputs()) {
                if (!registered.contains(output.jdbcIndex())) {
                    throw JdbcProxy.badIndex(
                            output.jdbcIndex(), "must be registered before execution");
                }
                if (!output.mode().input()) {
                    for (int position : binds.outputPositions(output.jdbcIndex())) {
                        delegate.setNull(position, output.sqlType());
                    }
                }
            }

            boolean hasResult = delegate.execute();
            if (signature.outputCount() > 0) {
                if (!hasResult)
                    throw new SQLException(
                            "PostgreSQL procedure did not expose OUT values", "HY000");
                try (ResultSet result = delegate.getResultSet()) {
                    if (result == null || !result.next())
                        throw new SQLException("Procedure returned no OUT row", "02000");
                    for (RoutineMetadataResolver.Binding output : signature.outputs()) {
                        outputValues.put(
                                output.jdbcIndex(), result.getObject(output.resultOrdinal()));
                    }
                    if (result.next())
                        throw new SQLException("Procedure returned more than one OUT row", "21000");
                }
            } else if (hasResult) {
                ResultSet unexpected = delegate.getResultSet();
                if (unexpected != null) unexpected.close();
                throw JdbcProxy.unsupported("Procedure returned an unexpected result row");
            }
            executed = true;
            return JdbcProxy.executeOutcome(methodName, 0);
        }

        private Object outputValue(Method method, Object[] args) throws SQLException {
            int index = (Integer) args[0];
            RoutineMetadataResolver.Binding binding = signature.binding(index);
            if (binding == null || !binding.mode().output())
                throw JdbcProxy.badIndex(index, "is not a procedure OUT parameter");
            if (!registered.contains(index))
                throw JdbcProxy.badIndex(index, "has not been registered");
            if (!executed || !outputValues.containsKey(index)) {
                throw new SQLException(
                        "Procedure OUT value is unavailable before execution", "HY010");
            }
            Object value = outputValues.get(index);
            lastWasNull = value == null;
            return JdbcValue.get(value, method, args, "procedure OUT");
        }
    }
}
