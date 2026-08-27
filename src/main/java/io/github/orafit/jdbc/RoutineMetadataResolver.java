package io.github.orafit.jdbc;

import io.github.orafit.translation.CallPlan;

import java.sql.*;
import java.util.*;

/** Resolves one PostgreSQL procedure signature without caching or Oracle-side type inference. */
final class RoutineMetadataResolver {
    private RoutineMetadataResolver() {}

    static Signature resolve(Connection connection, CallPlan call) throws SQLException {
        Name name = Name.parse(call.routine());
        Map<String, List<Parameter>> grouped = new LinkedHashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows =
                metadata.getProcedureColumns(
                        connection.getCatalog(), name.schema(), name.routine(), null)) {
            while (rows.next()) {
                Mode mode = mode(rows.getShort("COLUMN_TYPE"));
                if (mode == null) continue;
                String specific = rows.getString("SPECIFIC_NAME");
                if (specific == null || specific.isBlank()) specific = name.routine();
                grouped.computeIfAbsent(specific, ignored -> new ArrayList<>())
                        .add(
                                new Parameter(
                                        rows.getString("COLUMN_NAME"),
                                        mode,
                                        rows.getInt("DATA_TYPE"),
                                        rows.getInt("ORDINAL_POSITION")));
            }
        }

        List<Signature> matches = new ArrayList<>();
        for (List<Parameter> raw : grouped.values()) {
            raw.sort(Comparator.comparingInt(Parameter::ordinal));
            Signature signature = match(call, raw);
            if (signature != null) matches.add(signature);
        }
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty())
            throw JdbcProxy.unsupported(
                    "No unique PostgreSQL procedure metadata matches Oracle call "
                            + call.routine());
        throw JdbcProxy.unsupported(
                "ROUTINE_OVERLOAD_AMBIGUOUS: PostgreSQL procedure overload is ambiguous for Oracle"
                        + " call "
                        + call.routine());
    }

    private static Signature match(CallPlan call, List<Parameter> parameters) throws SQLException {
        if (parameters.size() != call.arguments().size()) return null;
        boolean[] used = new boolean[parameters.size()];
        List<Binding> bindings = new ArrayList<>();
        int nextPositional = 0;
        int outputOrdinal = 0;
        int[] outputOrdinals = new int[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).mode().output()) outputOrdinals[i] = ++outputOrdinal;
        }

        for (CallPlan.Argument argument : call.arguments()) {
            int parameterIndex;
            if (argument.named()) {
                parameterIndex = named(parameters, used, argument.name());
                if (parameterIndex < 0) return null;
            } else {
                while (nextPositional < used.length && used[nextPositional]) nextPositional++;
                if (nextPositional >= used.length) return null;
                parameterIndex = nextPositional++;
            }
            used[parameterIndex] = true;
            Parameter parameter = parameters.get(parameterIndex);
            if (parameter.mode().output() && !argument.directBind())
                throw JdbcProxy.unsupported(
                        "UNSAFE_OUT_EXPRESSION: Procedure OUT/INOUT argument must be one JDBC bind:"
                                + " "
                                + parameter.name());
            if (parameter.sqlType() == Types.REF_CURSOR && parameter.mode().output())
                throw JdbcProxy.unsupported(
                        "REF_CURSOR_OUTPUT: REF_CURSOR procedure outputs are not yet supported");
            if (argument.directBind()) {
                bindings.add(
                        new Binding(
                                argument.directBindIndex(),
                                parameter.mode(),
                                parameter.sqlType(),
                                outputOrdinals[parameterIndex]));
            }
        }
        return new Signature(List.copyOf(bindings), outputOrdinal);
    }

    private static int named(List<Parameter> parameters, boolean[] used, String name) {
        for (int i = 0; i < parameters.size(); i++) {
            String parameterName = parameters.get(i).name();
            if (!used[i] && parameterName != null && parameterName.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static Mode mode(short columnType) {
        return switch (columnType) {
            case DatabaseMetaData.procedureColumnIn -> Mode.IN;
            case DatabaseMetaData.procedureColumnOut -> Mode.OUT;
            case DatabaseMetaData.procedureColumnInOut -> Mode.INOUT;
            default -> null;
        };
    }

    record Signature(List<Binding> bindings, int outputCount) {
        Signature {
            bindings = List.copyOf(bindings);
        }

        Binding binding(int jdbcIndex) {
            for (Binding binding : bindings) if (binding.jdbcIndex() == jdbcIndex) return binding;
            return null;
        }

        List<Binding> outputs() {
            return bindings.stream()
                    .filter(binding -> binding.mode().output())
                    .sorted(Comparator.comparingInt(Binding::resultOrdinal))
                    .toList();
        }
    }

    record Binding(int jdbcIndex, Mode mode, int sqlType, int resultOrdinal) {}

    enum Mode {
        IN,
        OUT,
        INOUT;

        boolean input() {
            return this != OUT;
        }

        boolean output() {
            return this != IN;
        }
    }

    private record Parameter(String name, Mode mode, int sqlType, int ordinal) {}

    private record Name(String schema, String routine) {
        static Name parse(String mapped) {
            int dot = mapped.lastIndexOf('.');
            return dot < 0
                    ? new Name(null, mapped)
                    : new Name(mapped.substring(0, dot), mapped.substring(dot + 1));
        }
    }
}
