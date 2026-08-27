package io.github.orafit.tools;

import io.github.orafit.OrafitEngine;
import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.parse.SqlGate;
import io.github.orafit.translation.Feature;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Offline pre-check that measures which SQL statements the current Orafit engine accepts or rejects
 * without adding a second SQL parser or compatibility catalog.
 */
public final class CompatibilityAnalyzer {
    private final OrafitEngine engine = new OrafitEngine();
    private final ParserAdapter parser = new ParserAdapter();
    private final SqlGate gate = new SqlGate();

    /** Analyzes explicit files and every {@code *.sql} file below explicit directories. */
    public Report analyze(List<Path> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one SQL file or directory is required");
        }

        List<Path> files = collectFiles(inputs);
        MutableReport report = new MutableReport(files.size());
        for (Path file : files) analyzeFile(file, report);
        return report.freeze();
    }

    private void analyzeFile(Path file, MutableReport report) throws IOException {
        String script = Files.readString(file, StandardCharsets.UTF_8);
        if (script.isBlank()) return;

        try {
            List<Statement> statements = parser.parseStatements(script);
            if (statements.isEmpty()) return;
            int ordinal = 0;
            for (Statement statement : statements) {
                ordinal++;
                analyzeStatement(file, ordinal, statement.toString(), statement, report);
            }
        } catch (TranslationException ex) {
            analyzeStatement(file, 1, script, null, report);
        }
    }

    private void analyzeStatement(
            Path file, int ordinal, String sql, Statement parsed, MutableReport report) {
        if (sql == null || sql.isBlank()) return;
        report.statements++;

        SqlGate.Scan scan;
        try {
            scan = gate.scan(sql);
        } catch (TranslationException ex) {
            report.reject(file, ordinal, ex.code());
            return;
        }
        report.features(scan.features());

        if (parsed != null
                && !applicationStatement(parsed)
                && !scan.features().contains(Feature.PLSQL)
                && !looksLikeApplicationSql(scan.sql())) {
            report.outside(file, ordinal, parsed.getClass().getSimpleName());
            return;
        }
        if (parsed == null && !looksLikeApplicationSql(scan.sql()) && scan.features().isEmpty()) {
            report.outside(file, ordinal, "UNCLASSIFIED_SCRIPT");
            return;
        }

        try {
            Translation translation = engine.translate(sql);
            report.accepted++;
            if (translation.rewritten()) report.rewritten++;
            else report.passthrough++;
        } catch (TranslationException ex) {
            report.reject(file, ordinal, ex.code());
        }
    }

    private static boolean applicationStatement(Statement statement) {
        return statement instanceof Select
                || statement instanceof Insert
                || statement instanceof Update
                || statement instanceof Delete
                || statement instanceof Merge;
    }

    private static boolean looksLikeApplicationSql(String sql) {
        return SqlGate.firstWord(sql, "SELECT")
                || SqlGate.firstWord(sql, "WITH")
                || SqlGate.firstWord(sql, "INSERT")
                || SqlGate.firstWord(sql, "UPDATE")
                || SqlGate.firstWord(sql, "DELETE")
                || SqlGate.firstWord(sql, "MERGE")
                || SqlGate.firstWord(sql, "BEGIN")
                || SqlGate.firstWord(sql, "DECLARE");
    }

    private static List<Path> collectFiles(List<Path> inputs) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path input : inputs) {
            if (input == null) continue;
            Path path = input.toAbsolutePath().normalize();
            if (!Files.exists(path)) throw new IOException("SQL input does not exist: " + input);
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                            .filter(CompatibilityAnalyzer::isSqlFile)
                            .sorted()
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        return List.copyOf(files);
    }

    private static boolean isSqlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sql");
    }

    /** Command-line entry point for the packaged analyzer. */
    public static void main(String[] args) {
        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args) {
        boolean details = false;
        List<Path> inputs = new ArrayList<>();
        for (String arg : args) {
            if ("--details".equals(arg)) details = true;
            else if ("--help".equals(arg) || "-h".equals(arg)) {
                usage();
                return 0;
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option: " + arg);
                usage();
                return 2;
            } else {
                inputs.add(Path.of(arg));
            }
        }
        if (inputs.isEmpty()) {
            usage();
            return 2;
        }

        try {
            Report report = new CompatibilityAnalyzer().analyze(inputs);
            print(report, details);
            return 0;
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Orafit analyzer: " + ex.getMessage());
            return 2;
        }
    }

    private static void usage() {
        System.out.println(
                "Usage: java -jar orafit-jdbc-<version>.jar [--details] <sql-file-or-directory>...");
        System.out.println("Directories are scanned recursively for *.sql files.");
    }

    private static void print(Report report, boolean details) {
        System.out.println("Orafit compatibility analysis");
        System.out.printf(Locale.ROOT, "Files:       %,d%n", report.files());
        System.out.printf(Locale.ROOT, "Statements:  %,d%n", report.statements());
        System.out.printf(
                Locale.ROOT,
                "Accepted:    %,d (%s)%n",
                report.accepted(),
                percent(report.accepted(), report.statements()));
        System.out.printf(Locale.ROOT, "  pass-through: %,d%n", report.passthrough());
        System.out.printf(Locale.ROOT, "  rewritten:    %,d%n", report.rewritten());
        System.out.printf(
                Locale.ROOT,
                "Rejected:    %,d (%s)%n",
                report.rejected(),
                percent(report.rejected(), report.statements()));
        System.out.printf(Locale.ROOT, "Outside:     %,d%n", report.outside());

        if (!report.rejectionCodes().isEmpty()) {
            System.out.println("\nTop blockers:");
            report.rejectionCodes().entrySet().stream()
                    .sorted(countOrder())
                    .forEach(
                            entry ->
                                    System.out.printf(
                                            Locale.ROOT,
                                            "  %-32s %,d%n",
                                            entry.getKey(),
                                            entry.getValue()));
        }
        if (!report.featureCounts().isEmpty()) {
            System.out.println("\nRecognized Oracle features:");
            report.featureCounts().entrySet().stream()
                    .sorted(featureCountOrder())
                    .forEach(
                            entry ->
                                    System.out.printf(
                                            Locale.ROOT,
                                            "  %-32s %,d%n",
                                            entry.getKey().name(),
                                            entry.getValue()));
        }
        if (details && !report.nonAccepted().isEmpty()) {
            System.out.println("\nNon-accepted statements:");
            for (Finding finding : report.nonAccepted()) {
                System.out.printf(
                        Locale.ROOT,
                        "  %s#%d %-8s %s%n",
                        finding.file(),
                        finding.ordinal(),
                        finding.status(),
                        finding.code());
            }
        }
        System.out.println(
                "\nAccepted means the current engine did not reject the statement; public support "
                        + "claims remain defined by COMPATIBILITY.md and canonical Oracle evidence.");
    }

    private static Comparator<Map.Entry<String, Integer>> countOrder() {
        return Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey);
    }

    private static Comparator<Map.Entry<Feature, Integer>> featureCountOrder() {
        return Comparator.<Map.Entry<Feature, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(entry -> entry.getKey().name());
    }

    private static String percent(int value, int total) {
        if (total == 0) return "0.0%";
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    /** Immutable aggregate returned to tests and future integrations. */
    public record Report(
            int files,
            int statements,
            int accepted,
            int passthrough,
            int rewritten,
            int rejected,
            int outside,
            Map<String, Integer> rejectionCodes,
            Map<Feature, Integer> featureCounts,
            List<Finding> nonAccepted) {
        public Report {
            rejectionCodes = Map.copyOf(rejectionCodes);
            featureCounts = Map.copyOf(featureCounts);
            nonAccepted = List.copyOf(nonAccepted);
        }
    }

    /** One rejected or outside statement, without logging the SQL text itself. */
    public record Finding(Path file, int ordinal, String status, String code) {}

    private static final class MutableReport {
        private final int files;
        private int statements;
        private int accepted;
        private int passthrough;
        private int rewritten;
        private int rejected;
        private int outside;
        private final Map<String, Integer> rejectionCodes = new java.util.HashMap<>();
        private final Map<Feature, Integer> featureCounts = new EnumMap<>(Feature.class);
        private final List<Finding> nonAccepted = new ArrayList<>();

        private MutableReport(int files) {
            this.files = files;
        }

        private void features(Set<Feature> features) {
            for (Feature feature : features) featureCounts.merge(feature, 1, Integer::sum);
        }

        private void reject(Path file, int ordinal, String code) {
            rejected++;
            rejectionCodes.merge(code, 1, Integer::sum);
            nonAccepted.add(new Finding(file, ordinal, "REJECTED", code));
        }

        private void outside(Path file, int ordinal, String type) {
            outside++;
            nonAccepted.add(new Finding(file, ordinal, "OUTSIDE", type));
        }

        private Report freeze() {
            return new Report(
                    files,
                    statements,
                    accepted,
                    passthrough,
                    rewritten,
                    rejected,
                    outside,
                    rejectionCodes,
                    featureCounts,
                    nonAccepted);
        }
    }
}
