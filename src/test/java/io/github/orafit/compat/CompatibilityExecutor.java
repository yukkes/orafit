package io.github.orafit.compat;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.TranslationException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompatibilityExecutor {
    public enum Backend {
        ORACLE,
        ORAFIT
    }

    private final Backend backend;
    private final Connection connection;

    public CompatibilityExecutor(Backend backend, Connection connection) {
        this.backend = backend;
        this.connection = connection;
    }

    public Observation execute(CompatibilityCase test) {
        try {
            return switch (test.kind()) {
                case QUERY -> executeQuery(test);
                case UPDATE -> executeUpdate(test);
                case CALL -> executeCall(test);
                case RETURNING -> executeReturning(test);
            };
        } catch (Throwable throwable) {
            Throwable error = unwrap(throwable);
            if (error instanceof SQLException sql) {
                return Observation.error(
                        new ErrorObservation(
                                sql.getErrorCode(),
                                sql.getSQLState(),
                                sql.getMessage(),
                                backend == Backend.ORAFIT ? orafitCode(sql) : null));
            }
            return Observation.error(new ErrorObservation(0, null, error.toString(), null));
        }
    }

    public static String translationRejectCode(OrafitEngine engine, CompatibilityCase test) {
        try {
            engine.translate(test.sql());
            return null;
        } catch (TranslationException ex) {
            return ex.code();
        }
    }

    private Observation executeQuery(CompatibilityCase test) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(test.sql())) {
            applyInputs(statement, test.binds());
            try (ResultSet result = statement.executeQuery()) {
                List<List<Object>> rows = rows(result);
                if (test.compare() == CompatibilityCase.Compare.UNORDERED) {
                    rows.sort(Comparator.comparing(Object::toString));
                }
                return Observation.ok().withColumns(columns(result.getMetaData())).withRows(rows);
            }
        }
    }

    private Observation executeUpdate(CompatibilityCase test) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(test.sql())) {
            applyInputs(statement, test.binds());
            Observation result = Observation.ok().withUpdateCount(statement.executeUpdate());
            return observe(test, result);
        }
    }

    private Observation executeCall(CompatibilityCase test) throws Exception {
        try (CallableStatement statement = connection.prepareCall(test.sql())) {
            applyInputs(statement, test.binds());
            registerOutputs(statement, test.binds());
            statement.execute();
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (CompatibilityCase.Bind bind : test.binds()) {
                if (bind.mode() != CompatibilityCase.BindMode.IN) {
                    outputs.put(String.valueOf(bind.index()), callableValue(statement, bind));
                }
            }
            return Observation.ok().withOut(outputs).withUpdateCount(statement.getUpdateCount());
        }
    }

    private Observation executeReturning(CompatibilityCase test) throws Exception {
        if (backend == Backend.ORACLE) {
            return executeOracleReturning(test);
        }
        try (CallableStatement statement = connection.prepareCall(test.sql())) {
            applyInputs(statement, test.binds());
            registerOutputs(statement, test.binds());
            int count = statement.executeUpdate();
            List<Object> returned = new ArrayList<>();
            for (CompatibilityCase.Bind bind : test.binds()) {
                if (bind.mode() != CompatibilityCase.BindMode.IN) {
                    returned.add(callableValue(statement, bind));
                }
            }
            List<List<Object>> rows = new ArrayList<>();
            if (count > 0) {
                rows.add(returned);
            }
            return observe(test, Observation.ok().withUpdateCount(count).withReturningRows(rows));
        }
    }

    private Observation executeOracleReturning(CompatibilityCase test) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(test.sql())) {
            applyInputs(statement, test.binds());
            for (CompatibilityCase.Bind bind : test.binds()) {
                if (bind.mode() != CompatibilityCase.BindMode.IN) {
                    invokeOracle(
                            statement,
                            "registerReturnParameter",
                            new Class<?>[] {int.class, int.class},
                            bind.index(),
                            jdbcType(bind.type()));
                }
            }
            int count = statement.executeUpdate();
            List<List<Object>> returned = new ArrayList<>();
            try (ResultSet result =
                    (ResultSet) invokeOracle(statement, "getReturnResultSet", new Class<?>[0])) {
                if (result != null) {
                    while (result.next()) {
                        List<Object> row = new ArrayList<>();
                        int column = 1;
                        for (CompatibilityCase.Bind bind : test.binds()) {
                            if (bind.mode() != CompatibilityCase.BindMode.IN) {
                                row.add(resultValue(result, column++, jdbcType(bind.type())));
                            }
                        }
                        returned.add(row);
                    }
                }
            }
            return observe(
                    test, Observation.ok().withUpdateCount(count).withReturningRows(returned));
        }
    }

    private Observation observe(CompatibilityCase test, Observation observation)
            throws SQLException {
        if (test.observeSql().isEmpty()) {
            return observation;
        }
        try (PreparedStatement statement = connection.prepareStatement(test.observeSql());
                ResultSet result = statement.executeQuery()) {
            return observation.withPostState(stateRows(result));
        }
    }

    private static Object invokeOracle(
            PreparedStatement statement, String method, Class<?>[] types, Object... arguments)
            throws Exception {
        Class<?> oraclePreparedStatement = Class.forName("oracle.jdbc.OraclePreparedStatement");
        try {
            Method target = oraclePreparedStatement.getMethod(method, types);
            return target.invoke(statement, arguments);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw ex;
        }
    }

    private static void applyInputs(PreparedStatement statement, List<CompatibilityCase.Bind> binds)
            throws Exception {
        for (CompatibilityCase.Bind bind : binds) {
            if (bind.mode() == CompatibilityCase.BindMode.OUT) {
                continue;
            }
            if (bind.isNull()) {
                statement.setNull(bind.index(), jdbcType(bind.type()));
                continue;
            }
            switch (bind.type()) {
                case VARCHAR -> statement.setString(bind.index(), bind.value());
                case INTEGER -> statement.setInt(bind.index(), Integer.parseInt(bind.value()));
                case DECIMAL -> statement.setBigDecimal(bind.index(), new BigDecimal(bind.value()));
                case DATE -> statement.setDate(bind.index(), java.sql.Date.valueOf(bind.value()));
                case TIMESTAMP ->
                        statement.setTimestamp(bind.index(), Timestamp.valueOf(bind.value()));
                case TIMESTAMP_TZ ->
                        statement.setObject(bind.index(), OffsetDateTime.parse(bind.value()));
                case REF_CURSOR ->
                        throw new IllegalArgumentException("REF_CURSOR cannot be an input bind");
            }
        }
    }

    private static void registerOutputs(
            CallableStatement statement, List<CompatibilityCase.Bind> binds) throws SQLException {
        for (CompatibilityCase.Bind bind : binds) {
            if (bind.mode() != CompatibilityCase.BindMode.IN) {
                statement.registerOutParameter(bind.index(), jdbcType(bind.type()));
            }
        }
    }

    private static int jdbcType(CompatibilityCase.BindType type) {
        return switch (type) {
            case VARCHAR -> Types.VARCHAR;
            case INTEGER -> Types.INTEGER;
            case DECIMAL -> Types.NUMERIC;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMP_TZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            case REF_CURSOR -> Types.REF_CURSOR;
        };
    }

    private static List<ColumnObservation> columns(ResultSetMetaData metadata) throws SQLException {
        List<ColumnObservation> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(
                    new ColumnObservation(
                            metadata.getColumnLabel(i),
                            metadata.getColumnName(i),
                            metadata.getColumnType(i),
                            metadata.getColumnTypeName(i),
                            metadata.getPrecision(i),
                            metadata.getScale(i),
                            metadata.isNullable(i)));
        }
        return List.copyOf(columns);
    }

    private static List<List<Object>> rows(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        int count = metadata.getColumnCount();
        List<List<Object>> rows = new ArrayList<>();
        while (result.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                row.add(resultValue(result, i, metadata.getColumnType(i)));
            }
            rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return rows;
    }

    private static List<List<Object>> stateRows(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        int count = metadata.getColumnCount();
        List<List<Object>> rows = new ArrayList<>();
        while (result.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                int type = metadata.getColumnType(i);
                if (isNumeric(type)) {
                    BigDecimal value = result.getBigDecimal(i);
                    if (result.wasNull()) {
                        row.add(null);
                    } else {
                        BigDecimal normalized = value.stripTrailingZeros();
                        row.add(
                                (normalized.scale() < 0 ? normalized.setScale(0) : normalized)
                                        .toPlainString());
                    }
                } else {
                    row.add(resultValue(result, i, type));
                }
            }
            rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return List.copyOf(rows);
    }

    private static Object resultValue(ResultSet result, int index, int jdbcType)
            throws SQLException {
        if (isNumeric(jdbcType)) {
            BigDecimal value = result.getBigDecimal(index);
            return result.wasNull() ? null : value.toPlainString();
        }
        return switch (jdbcType) {
            case Types.DATE, Types.TIMESTAMP -> {
                Timestamp value = result.getTimestamp(index);
                yield result.wasNull() ? null : value.toLocalDateTime().toString();
            }
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                OffsetDateTime value = result.getObject(index, OffsetDateTime.class);
                yield result.wasNull() ? null : value.toString();
            }
            default -> {
                String value = result.getString(index);
                yield result.wasNull() ? null : value;
            }
        };
    }

    private static Object callableValue(CallableStatement statement, CompatibilityCase.Bind bind)
            throws SQLException {
        return switch (bind.type()) {
            case DECIMAL -> {
                BigDecimal value = statement.getBigDecimal(bind.index());
                yield statement.wasNull() ? null : value.toPlainString();
            }
            case INTEGER -> {
                int value = statement.getInt(bind.index());
                yield statement.wasNull() ? null : Integer.toString(value);
            }
            case DATE, TIMESTAMP -> {
                Timestamp value = statement.getTimestamp(bind.index());
                yield statement.wasNull() ? null : value.toLocalDateTime().toString();
            }
            case TIMESTAMP_TZ -> {
                OffsetDateTime value = statement.getObject(bind.index(), OffsetDateTime.class);
                yield statement.wasNull() ? null : value.toString();
            }
            case REF_CURSOR -> {
                Object value = statement.getObject(bind.index());
                if (value == null) {
                    yield null;
                }
                if (!(value instanceof ResultSet result)) {
                    throw new SQLException("REF CURSOR OUT did not return ResultSet");
                }
                try (result) {
                    yield rows(result);
                }
            }
            case VARCHAR -> {
                String value = statement.getString(bind.index());
                yield statement.wasNull() ? null : value;
            }
        };
    }

    private static boolean isNumeric(int type) {
        return type == Types.NUMERIC
                || type == Types.DECIMAL
                || type == Types.INTEGER
                || type == Types.BIGINT
                || type == Types.SMALLINT
                || type == Types.REAL
                || type == Types.FLOAT
                || type == Types.DOUBLE;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    public static String orafitCode(SQLException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof TranslationException translation) {
                return translation.code();
            }
            if (current instanceof SQLFeatureNotSupportedException unsupported
                    && "0A000".equals(unsupported.getSQLState())) {
                String message = unsupported.getMessage();
                int colon = message == null ? -1 : message.indexOf(':');
                if (colon > 0) {
                    String code = message.substring(0, colon);
                    if (code.matches("[A-Z][A-Z0-9_]+")) {
                        return code;
                    }
                }
            }
        }
        return null;
    }

    public record ColumnObservation(
            String label,
            String name,
            int jdbcType,
            String typeName,
            int precision,
            int scale,
            int nullable) {}

    public record ErrorObservation(int code, String sqlState, String message, String orafitCode) {}

    public record Observation(
            String status,
            List<ColumnObservation> columns,
            List<List<Object>> rows,
            Integer updateCount,
            Map<String, Object> out,
            List<List<Object>> returningRows,
            List<List<Object>> postState,
            ErrorObservation error) {

        public static Observation ok() {
            return new Observation(
                    "ok", List.of(), List.of(), null, Map.of(), List.of(), List.of(), null);
        }

        public static Observation error(ErrorObservation error) {
            return new Observation(
                    "error", List.of(), List.of(), null, Map.of(), List.of(), List.of(), error);
        }

        public Observation withColumns(List<ColumnObservation> value) {
            return new Observation(
                    status, value, rows, updateCount, out, returningRows, postState, error);
        }

        public Observation withRows(List<List<Object>> value) {
            return new Observation(
                    status, columns, value, updateCount, out, returningRows, postState, error);
        }

        public Observation withUpdateCount(Integer value) {
            return new Observation(
                    status, columns, rows, value, out, returningRows, postState, error);
        }

        public Observation withOut(Map<String, Object> value) {
            return new Observation(
                    status,
                    columns,
                    rows,
                    updateCount,
                    Collections.unmodifiableMap(new LinkedHashMap<>(value)),
                    returningRows,
                    postState,
                    error);
        }

        public Observation withReturningRows(List<List<Object>> value) {
            return new Observation(
                    status, columns, rows, updateCount, out, List.copyOf(value), postState, error);
        }

        public Observation withPostState(List<List<Object>> value) {
            return new Observation(
                    status, columns, rows, updateCount, out, returningRows, value, error);
        }
    }
}
