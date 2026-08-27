package io.github.orafit.compat;

import static io.github.orafit.compat.CompatibilityCase.Mode.REJECT;
import static io.github.orafit.compat.CompatibilityCase.Mode.SAME;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CompatibilityCases {
    private static final Pattern ID = Pattern.compile("^[a-z0-9]+(?:[.-][a-z0-9]+)*$");
    private static final Path ROOT =
            Path.of(System.getProperty("user.dir")).resolve("src/test/resources/oracle/cases");
    private static final List<CompatibilityCase> ALL = load();

    private CompatibilityCases() {}

    public static Stream<CompatibilityCase> all() {
        return ALL.stream();
    }

    public static List<CompatibilityCase> list() {
        return ALL;
    }

    private static List<CompatibilityCase> load() {
        if (!Files.isDirectory(ROOT)) {
            throw new AssertionError("Compatibility case directory not found: " + ROOT);
        }
        try {
            List<Path> files;
            try (Stream<Path> paths = Files.walk(ROOT)) {
                files =
                        paths.filter(path -> path.toString().endsWith(".toml"))
                                .sorted(Comparator.comparing(Path::toString))
                                .toList();
            }
            List<CompatibilityCase> cases = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Path path : files) {
                parse(path, cases, ids);
            }
            if (cases.isEmpty()) {
                throw new AssertionError("Compatibility case inventory is empty");
            }
            return List.copyOf(cases);
        } catch (IOException ex) {
            throw new AssertionError("Could not load compatibility cases", ex);
        }
    }

    private static void parse(Path path, List<CompatibilityCase> cases, Set<String> ids)
            throws IOException {
        TomlParseResult document = Toml.parse(path);
        if (document.hasErrors()) {
            throw new AssertionError(path + ": " + document.errors());
        }
        require(number(document, "version") == 1L, path + ": version must be 1");
        String feature = requiredString(document, "feature", path.toString());
        TomlArray array = document.getArray("case");
        require(array != null, path + ": [[case]] entries required");

        for (int i = 0; i < array.size(); i++) {
            TomlTable table = array.getTable(i);
            String where = path + ":case[" + i + "]";
            String id = requiredString(table, "id", where);
            require(ID.matcher(id).matches(), where + ": invalid id " + id);
            require(ids.add(id), "duplicate compatibility case id: " + id);

            String title = requiredString(table, "title", where);
            String sql = requiredString(table, "sql", where).strip();
            CompatibilityCase.Kind kind =
                    enumValue(CompatibilityCase.Kind.class, requiredString(table, "kind", where));
            CompatibilityCase.Compare compare =
                    enumValue(
                            CompatibilityCase.Compare.class,
                            requiredString(table, "compare", where));

            TomlTable oracle = requiredTable(table, "oracle", where);
            CompatibilityCase.Outcome outcome =
                    enumValue(
                            CompatibilityCase.Outcome.class,
                            requiredString(oracle, "outcome", where + ".oracle"));
            Long rawError = oracle.getLong("error_code");
            Integer oracleError = rawError == null ? null : Math.toIntExact(rawError);

            TomlTable orafit = requiredTable(table, "orafit", where);
            CompatibilityCase.Mode mode =
                    enumValue(
                            CompatibilityCase.Mode.class,
                            requiredString(orafit, "mode", where + ".orafit"));
            String code = optionalString(orafit, "code");
            String reason = optionalString(orafit, "reason");
            String stage = optionalString(orafit, "stage");
            CompatibilityCase.RejectStage rejectStage =
                    stage.isEmpty()
                            ? CompatibilityCase.RejectStage.TRANSLATION
                            : enumValue(CompatibilityCase.RejectStage.class, stage);
            if (mode == REJECT) {
                require(!reason.isBlank(), id + ": reject requires reason");
            } else {
                require(stage.isEmpty(), id + ": reject stage is valid only for reject cases");
            }

            Boolean rawCompareTypeName = table.getBoolean("compare_type_name");
            boolean compareTypeName = rawCompareTypeName != null && rawCompareTypeName;
            if (compareTypeName) {
                require(
                        kind == CompatibilityCase.Kind.QUERY
                                && (compare == CompatibilityCase.Compare.ORDERED
                                        || compare == CompatibilityCase.Compare.UNORDERED)
                                && outcome == CompatibilityCase.Outcome.SUCCESS
                                && mode == SAME,
                        id + ": compare_type_name requires a successful same query");
            }

            String observeSql = optionalString(table, "observe_sql").strip();
            if (!observeSql.isEmpty()) {
                require(
                        (kind == CompatibilityCase.Kind.UPDATE
                                        || kind == CompatibilityCase.Kind.RETURNING)
                                && outcome == CompatibilityCase.Outcome.SUCCESS
                                && mode == SAME,
                        id + ": observe_sql is only valid for successful same DML");
                String upperObserve = observeSql.toUpperCase(Locale.ROOT);
                require(upperObserve.startsWith("SELECT "), id + ": observe_sql must be SELECT");
                require(!observeSql.contains("?"), id + ": observe_sql cannot contain binds");
                require(
                        upperObserve.matches("(?s).*\\bORDER\\s+BY\\b.*"),
                        id + ": observe_sql must include ORDER BY");
            }

            String upper = sql.toUpperCase(Locale.ROOT);
            for (String forbidden : List.of("CREATE TEMP TABLE", "::", "LIMIT ")) {
                require(!upper.contains(forbidden), id + ": non-Oracle syntax " + forbidden);
            }

            List<CompatibilityCase.Bind> binds = parseBinds(table, id);
            cases.add(
                    new CompatibilityCase(
                            id,
                            feature,
                            title,
                            kind,
                            compare,
                            outcome,
                            oracleError,
                            mode,
                            code,
                            rejectStage,
                            compareTypeName,
                            sql,
                            observeSql,
                            binds));
        }
    }

    private static List<CompatibilityCase.Bind> parseBinds(TomlTable table, String id) {
        TomlArray array = table.getArray("bind");
        if (array == null) {
            return List.of();
        }
        List<CompatibilityCase.Bind> binds = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        int max = 0;
        for (int i = 0; i < array.size(); i++) {
            TomlTable bind = array.getTable(i);
            int index = Math.toIntExact(number(bind, "index"));
            require(index >= 1 && indexes.add(index), id + ": invalid/duplicate bind index");
            max = Math.max(max, index);
            CompatibilityCase.BindType type =
                    enumValue(
                            CompatibilityCase.BindType.class,
                            requiredString(bind, "type", id + ".bind"));
            CompatibilityCase.BindMode mode =
                    enumValue(
                            CompatibilityCase.BindMode.class,
                            requiredString(bind, "mode", id + ".bind"));
            Boolean rawNull = bind.getBoolean("null");
            boolean isNull = rawNull != null && rawNull;
            Object rawValue = bind.get("value");
            String value = isNull || rawValue == null ? "" : String.valueOf(rawValue);
            binds.add(new CompatibilityCase.Bind(index, type, mode, isNull, value));
        }
        if (!indexes.isEmpty()) {
            for (int i = 1; i <= max; i++) {
                require(indexes.contains(i), id + ": bind indexes must be contiguous from 1");
            }
        }
        binds.sort(Comparator.comparingInt(CompatibilityCase.Bind::index));
        return List.copyOf(binds);
    }

    private static long number(TomlTable table, String key) {
        Long value = table.getLong(key);
        if (value == null) {
            throw new AssertionError(key + " must be an integer");
        }
        return value;
    }

    private static String requiredString(TomlTable table, String key, String where) {
        String value = table.getString(key);
        require(value != null && !value.isBlank(), where + ": " + key + " required");
        return value;
    }

    private static String optionalString(TomlTable table, String key) {
        String value = table.getString(key);
        return value == null ? "" : value;
    }

    private static TomlTable requiredTable(TomlTable table, String key, String where) {
        TomlTable value = table.getTable(key);
        require(value != null, where + ": [" + key + "] required");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new AssertionError("Unknown " + type.getSimpleName() + ": " + value, ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
