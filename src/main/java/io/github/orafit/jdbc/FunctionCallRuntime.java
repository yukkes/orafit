package io.github.orafit.jdbc;

import io.github.orafit.translation.BindLineage;
import io.github.orafit.translation.CallPlan;
import io.github.orafit.translation.Translation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.*;

/** Callable adapter for scalar function returns; procedures are isolated in ProcedureRuntime. */
final class FunctionCallRuntime {
    private FunctionCallRuntime() {}

    static CallableStatement prepare(
            Connection connection,
            Translation translation,
            Object[] prepareCallArgs,
            Connection owner)
            throws SQLException {
        if (!translation.call().function())
            return ProcedureRuntime.prepare(connection, translation, prepareCallArgs, owner);
        PreparedStatement delegate =
                JdbcProxy.prepareStatement(
                        connection, translation.sql(), prepareCallArgs, "Oracle function call");
        return JdbcProxy.proxy(
                CallableStatement.class,
                new Handler(delegate, translation.binds(), translation.call(), owner));
    }

    private static final class Handler implements InvocationHandler {
        private final PreparedStatement delegate;
        private final BindLineage binds;
        private final CallPlan call;
        private final Connection owner;
        private boolean returnRegistered;
        private Object returnValue;
        private boolean executed;
        private boolean lastWasNull;

        private Handler(
                PreparedStatement delegate, BindLineage binds, CallPlan call, Connection owner) {
            this.delegate = delegate;
            this.binds = binds;
            this.call = call;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return owner;
            if (name.equals("getParameterMetaData"))
                throw JdbcProxy.unsupported("Oracle function parameter metadata is not modeled");
            if (name.equals("registerOutParameter") && JdbcProxy.indexed(args)) {
                int index = (Integer) args[0];
                if (index != call.returnParameterIndex())
                    throw JdbcProxy.badIndex(index, "is not the function return parameter");
                returnRegistered = true;
                return null;
            }
            if (JdbcProxy.callableGetter(method, args)) return functionValue(method, args);
            if (JdbcProxy.routineStatus(name))
                return JdbcProxy.routineStatus(name, lastWasNull, -1);

            if (JdbcProxy.parameterSetter(method, args)) {
                int input = (Integer) args[0];
                if (input == call.returnParameterIndex()) {
                    throw JdbcProxy.badIndex(
                            input, "is the function return parameter and cannot be assigned");
                }
                JdbcBinds.mapSetter(delegate, binds, method, args, input, "the function adapter");
                return null;
            }
            if (name.equals("clearParameters")) {
                returnValue = null;
                executed = false;
                lastWasNull = false;
                return JdbcProxy.invokeDelegate(method, delegate, args);
            }
            if (JdbcProxy.executeCall(name)) return executeFunction(name);
            JdbcProxy.rejectRoutineExtras(name, method, "function");
            return JdbcProxy.invokeDelegate(method, delegate, args);
        }

        private Object executeFunction(String methodName) throws SQLException {
            if (!returnRegistered)
                throw JdbcProxy.badIndex(
                        call.returnParameterIndex(), "must be registered before execution");
            executed = false;
            returnValue = null;
            lastWasNull = false;
            boolean hasResult = delegate.execute();
            if (!hasResult)
                throw new SQLException(
                        "PostgreSQL function call did not return a result row", "HY000");
            try (ResultSet result = delegate.getResultSet()) {
                if (result == null || !result.next())
                    throw new SQLException("Oracle function returned no row", "02000");
                returnValue = result.getObject(1);
                if (result.next())
                    throw new SQLException(
                            "Oracle scalar function returned more than one row", "21000");
            }
            executed = true;
            return JdbcProxy.executeOutcome(methodName, 0);
        }

        private Object functionValue(Method method, Object[] args) throws SQLException {
            int index = (Integer) args[0];
            if (index != call.returnParameterIndex())
                throw JdbcProxy.badIndex(index, "is not the function return parameter");
            if (!returnRegistered) throw JdbcProxy.badIndex(index, "has not been registered");
            if (!executed)
                throw new SQLException(
                        "Function return value is unavailable before execution", "HY010");
            lastWasNull = returnValue == null;
            return JdbcValue.get(returnValue, method, args, "function return");
        }
    }
}
