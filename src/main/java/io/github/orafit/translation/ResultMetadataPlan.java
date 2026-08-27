package io.github.orafit.translation;

import java.util.List;

/**
 * Oracle-facing result metadata derived from the same parsed SQL AST used for translation.
 *
 * <p>Only metadata that Orafit can prove is recorded. Unclassified expressions deliberately fall
 * back to {@link Kind#AUTO}, allowing pgJDBC metadata to remain authoritative rather than guessing
 * an Oracle type.
 *
 * @param columns one entry per projected result column when metadata is owned
 */
public record ResultMetadataPlan(List<Column> columns) {
    public ResultMetadataPlan {
        columns = List.copyOf(columns);
    }

    public static ResultMetadataPlan none() {
        return new ResultMetadataPlan(List.of());
    }

    public boolean present() {
        return !columns.isEmpty();
    }

    /**
     * Out-of-range ordinals return {@link Column#AUTO} so metadata wrappers can delegate safely.
     *
     * @param ordinal one-based JDBC result-column ordinal
     * @return owned metadata or an AUTO fallback
     */
    public Column column(int ordinal) {
        return ordinal > 0 && ordinal <= columns.size() ? columns.get(ordinal - 1) : Column.AUTO;
    }

    /**
     * @param label Oracle-facing column label when explicitly known
     * @param kind Oracle-facing type family, or AUTO to delegate
     * @param precision fixed precision when provable
     * @param scale fixed scale when provable
     * @param precisionBind input bind whose value determines precision when applicable
     * @param source physical source column when metadata derives from a table column
     * @param nullable JDBC nullability constant when provable
     * @param paddingFallback NVL fallback literal that must not inherit source CHAR padding
     */
    public record Column(
            String label,
            Kind kind,
            Integer precision,
            Integer scale,
            Integer precisionBind,
            Source source,
            Integer nullable,
            boolean padFromSource,
            String paddingFallback) {
        static final Column AUTO =
                new Column(null, Kind.AUTO, null, null, null, null, null, false, null);

        public Column(String label, Kind kind, Integer precision, Integer scale) {
            this(label, kind, precision, scale, null, null, null, false, null);
        }

        public Column(
                String label, Kind kind, Integer precision, Integer scale, Integer precisionBind) {
            this(label, kind, precision, scale, precisionBind, null, null, false, null);
        }

        public Column(
                String label,
                Kind kind,
                Integer precision,
                Integer scale,
                Integer precisionBind,
                Source source,
                Integer nullable) {
            this(label, kind, precision, scale, precisionBind, source, nullable, false, null);
        }

        public Column {
            if (kind == null) kind = Kind.AUTO;
        }
    }

    public record Source(String schema, String table, String column) {}

    /** Oracle-facing type families deliberately exposed through JDBC metadata. */
    public enum Kind {
        AUTO,
        NUMBER,
        CHAR,
        VARCHAR2,
        DATE,
        TIMESTAMP
    }
}
