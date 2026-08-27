package io.github.orafit.compat;

import java.util.List;

public record CompatibilityCase(
        String id,
        String feature,
        String title,
        Kind kind,
        Compare compare,
        Outcome oracleOutcome,
        Integer oracleErrorCode,
        Mode mode,
        String orafitCode,
        RejectStage rejectStage,
        boolean compareTypeName,
        String sql,
        String observeSql,
        List<Bind> binds) {

    public enum Kind {
        QUERY,
        UPDATE,
        CALL,
        RETURNING
    }

    public enum Compare {
        ORDERED,
        UNORDERED,
        SHAPE,
        UPDATE,
        CALL,
        RETURNING,
        ERROR
    }

    public enum Outcome {
        SUCCESS,
        ERROR
    }

    public enum Mode {
        SAME,
        REJECT
    }

    public enum RejectStage {
        TRANSLATION,
        RUNTIME
    }

    public enum BindType {
        VARCHAR,
        INTEGER,
        DECIMAL,
        DATE,
        TIMESTAMP,
        TIMESTAMP_TZ,
        REF_CURSOR
    }

    public enum BindMode {
        IN,
        OUT,
        INOUT
    }

    public record Bind(int index, BindType type, BindMode mode, boolean isNull, String value) {}

    @Override
    public String toString() {
        return id;
    }
}
