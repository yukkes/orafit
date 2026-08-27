package io.github.orafit.translation;

import java.util.Set;

/**
 * Immutable result of translating one application SQL statement.
 *
 * @param sql PostgreSQL SQL to execute
 * @param binds mapping from rendered JDBC bind positions back to application bind positions
 * @param returning plan for Oracle {@code RETURNING ... INTO}, or an empty plan
 * @param call plan for a supported Oracle routine-call envelope, or an empty plan
 * @param metadata Oracle-facing result metadata adjustments, or an empty plan
 * @param script multi-statement DML block plan, or an empty plan
 * @param features Oracle-related features recognized by the lightweight gate
 * @param rewritten whether the SQL text or JDBC-visible execution contract changed
 */
public record Translation(
        String sql,
        BindLineage binds,
        ReturningPlan returning,
        CallPlan call,
        ResultMetadataPlan metadata,
        ScriptPlan script,
        Set<Feature> features,
        boolean rewritten) {

    public Translation {
        if (sql == null) throw new IllegalArgumentException("sql must not be null");
        if (binds == null) throw new IllegalArgumentException("binds must not be null");
        if (returning == null) throw new IllegalArgumentException("returning must not be null");
        if (call == null) throw new IllegalArgumentException("call must not be null");
        if (metadata == null) throw new IllegalArgumentException("metadata must not be null");
        if (script == null) throw new IllegalArgumentException("script must not be null");
        if (returning.present() && call.present()) {
            throw new IllegalArgumentException("RETURNING and call plans are mutually exclusive");
        }
        if (script.present() && (returning.present() || call.present() || metadata.present())) {
            throw new IllegalArgumentException(
                    "Script plans cannot expose RETURNING, call, or result metadata plans");
        }
        features = Set.copyOf(features);
    }
}
