package io.github.orafit.audit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Guards the frozen historical translator corpus against unsafe behavior regressions. */
final class ReleaseCorpusTest {
    // The historical corpus is immutable; these cases now intentionally require SQL rewriting.
    private static final Set<Integer> INTENTIONAL_REWRITES = Set.of(110, 318, 462);

    @Test
    void historicalTranslatorCorpusStaysSafe() throws IOException {
        Path corpus = Path.of("src", "test", "resources", "regression", "release-corpus.tsv");
        Path report = Path.of("target", "reports", "release-corpus.tsv");
        Summary summary = audit(corpus, report);

        System.out.println(summary.line());
        for (String detail : summary.detailLines()) {
            System.out.println(detail);
        }

        assertAll(
                () -> assertTrue(summary.total() > 0, "release corpus must not be empty"),
                () ->
                        assertTrue(
                                summary.safe(),
                                () ->
                                        "release corpus safety boundary regressed; see "
                                                + report.toAbsolutePath()));
    }

    private static Summary audit(Path corpus, Path report) throws IOException {
        OrafitEngine engine = new OrafitEngine();
        Summary summary = new Summary();
        StringBuilder output = new StringBuilder();
        output.append(
                "id\texpected\tfeature\toutcome\tactualCode\tactualChanged\tinputBinds"
                        + "\toutputBinds\n");

        for (String line : Files.readAllLines(corpus, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Case test = Case.parse(line);
            Result result = evaluate(engine, test);
            summary.add(result.outcome(), test.feature(), result.code(), test.id());
            output.append(test.id())
                    .append('\t')
                    .append(test.status())
                    .append('\t')
                    .append(test.feature())
                    .append('\t')
                    .append(result.outcome())
                    .append('\t')
                    .append(result.code())
                    .append('\t')
                    .append(result.changed())
                    .append('\t')
                    .append(result.inputBinds())
                    .append('\t')
                    .append(result.outputBinds())
                    .append('\n');
        }

        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, output.toString(), StandardCharsets.UTF_8);
        return summary;
    }

    private static Result evaluate(OrafitEngine engine, Case test) {
        try {
            Translation translated = engine.translate(test.sql());
            int inputs = translated.binds().inputCount();
            int outputs = translated.binds().outputToInput().size();

            if (test.status() == Status.REJECT) {
                return new Result(
                        Outcome.UNSAFE_ACCEPT, "-", translated.rewritten(), inputs, outputs);
            }
            if (inputs != test.originalBindCount()) {
                return new Result(
                        Outcome.INTERNAL_ERROR,
                        "INPUT_BIND_COUNT",
                        translated.rewritten(),
                        inputs,
                        outputs);
            }
            if (!coversRequiredInputs(translated)) {
                return new Result(
                        Outcome.BIND_GAP,
                        "MISSING_INPUT_BIND",
                        translated.rewritten(),
                        inputs,
                        outputs);
            }
            if (outputs > test.targetBindCount()) {
                return new Result(
                        Outcome.BIND_GAP,
                        "TARGET_BIND_COUNT",
                        translated.rewritten(),
                        inputs,
                        outputs);
            }
            if (test.changed() && !translated.rewritten()) {
                return new Result(Outcome.UNSAFE_PASSTHROUGH, "-", false, inputs, outputs);
            }
            if (!test.changed()
                    && translated.rewritten()
                    && !INTENTIONAL_REWRITES.contains(test.id())) {
                return new Result(Outcome.UNEXPECTED_REWRITE, "-", true, inputs, outputs);
            }
            return new Result(Outcome.SUPPORTED, "-", translated.rewritten(), inputs, outputs);
        } catch (TranslationException ex) {
            Outcome outcome =
                    test.status() == Status.REJECT
                            ? Outcome.EXPECTED_REJECT
                            : Outcome.MISSING_SUPPORT;
            return new Result(outcome, ex.code(), false, -1, -1);
        } catch (RuntimeException ex) {
            return new Result(Outcome.INTERNAL_ERROR, ex.getClass().getSimpleName(), false, -1, -1);
        }
    }

    private static boolean coversRequiredInputs(Translation translated) {
        Set<Integer> required = new HashSet<>();
        for (int i = 1; i <= translated.binds().inputCount(); i++) {
            required.add(i);
        }
        required.removeAll(translated.returning().outputIndices());
        required.removeAll(translated.call().outputIndices());
        return translated.binds().outputToInput().containsAll(required);
    }

    private enum Outcome {
        SUPPORTED,
        EXPECTED_REJECT,
        MISSING_SUPPORT,
        UNSAFE_PASSTHROUGH,
        UNSAFE_ACCEPT,
        BIND_GAP,
        UNEXPECTED_REWRITE,
        INTERNAL_ERROR
    }

    private enum Status {
        SUPPORTED,
        REJECT
    }

    private record Result(
            Outcome outcome, String code, boolean changed, int inputBinds, int outputBinds) {}

    private record Case(
            int id,
            Status status,
            String feature,
            boolean changed,
            int originalBindCount,
            int targetBindCount,
            String sql) {
        private static Case parse(String line) {
            String[] fields = line.split("\\t", 7);
            if (fields.length != 7) {
                throw new IllegalArgumentException("malformed corpus line: " + line);
            }
            Status status = Status.valueOf(fields[1]);
            int original = integer(fields[4]);
            int target = integer(fields[5]);
            String sql = new String(Base64.getDecoder().decode(fields[6]), StandardCharsets.UTF_8);
            return new Case(
                    Integer.parseInt(fields[0]),
                    status,
                    fields[2],
                    Boolean.parseBoolean(fields[3]),
                    original,
                    target,
                    sql);
        }

        private static int integer(String value) {
            return value.equals("-") ? -1 : Integer.parseInt(value);
        }
    }

    private static final class Summary {
        private final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        private final Map<Outcome, Map<String, Integer>> features = new EnumMap<>(Outcome.class);
        private final Map<Outcome, Map<String, Integer>> codes = new EnumMap<>(Outcome.class);
        private final Map<Outcome, List<Integer>> ids = new EnumMap<>(Outcome.class);

        private void add(Outcome outcome, String feature, String code, int id) {
            counts.merge(outcome, 1, Integer::sum);
            features.computeIfAbsent(outcome, ignored -> new TreeMap<>())
                    .merge(feature, 1, Integer::sum);
            codes.computeIfAbsent(outcome, ignored -> new TreeMap<>()).merge(code, 1, Integer::sum);
            ids.computeIfAbsent(outcome, ignored -> new ArrayList<>()).add(id);
        }

        private int count(Outcome outcome) {
            return counts.getOrDefault(outcome, 0);
        }

        private int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        private boolean safe() {
            return count(Outcome.UNSAFE_ACCEPT) == 0
                    && count(Outcome.UNSAFE_PASSTHROUGH) == 0
                    && count(Outcome.BIND_GAP) == 0
                    && count(Outcome.UNEXPECTED_REWRITE) == 0
                    && count(Outcome.INTERNAL_ERROR) == 0;
        }

        private String line() {
            return "Release corpus: total="
                    + total()
                    + " supported="
                    + count(Outcome.SUPPORTED)
                    + " expectedReject="
                    + count(Outcome.EXPECTED_REJECT)
                    + " missingSupport="
                    + count(Outcome.MISSING_SUPPORT)
                    + " unsafePassthrough="
                    + count(Outcome.UNSAFE_PASSTHROUGH)
                    + " unsafeAccept="
                    + count(Outcome.UNSAFE_ACCEPT)
                    + " bindGap="
                    + count(Outcome.BIND_GAP)
                    + " unexpectedRewrite="
                    + count(Outcome.UNEXPECTED_REWRITE)
                    + " internalError="
                    + count(Outcome.INTERNAL_ERROR);
        }

        private List<String> detailLines() {
            return List.of(
                    detail(Outcome.UNSAFE_ACCEPT),
                    detail(Outcome.UNSAFE_PASSTHROUGH),
                    detail(Outcome.BIND_GAP),
                    "Release corpus INTERNAL_ERROR by code="
                            + codes.getOrDefault(Outcome.INTERNAL_ERROR, Map.of())
                            + " ids="
                            + ids.getOrDefault(Outcome.INTERNAL_ERROR, List.of()),
                    "Release corpus MISSING_SUPPORT by code="
                            + codes.getOrDefault(Outcome.MISSING_SUPPORT, Map.of()));
        }

        private String detail(Outcome outcome) {
            return "Release corpus "
                    + outcome
                    + " by feature="
                    + features.getOrDefault(outcome, Map.of())
                    + " ids="
                    + ids.getOrDefault(outcome, List.of());
        }
    }
}
