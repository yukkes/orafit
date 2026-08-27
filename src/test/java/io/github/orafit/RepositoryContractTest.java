package io.github.orafit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class RepositoryContractTest {
    private static final Path ROOT =
            Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."))
                    .toAbsolutePath()
                    .normalize();
    private static final Path EXTENSION = ROOT.resolve("extension");
    private static final List<String> EXTENSION_MODULES =
            List.of(
                    "00-core.sql",
                    "10-number.sql",
                    "20-datetime.sql",
                    "30-string.sql",
                    "40-regexp.sql",
                    "50-listagg.sql",
                    "60-connect-by.sql",
                    "90-migration.sql");
    private static final List<String> REQUIRED_RUNTIME_APIS =
            List.of(
                    "dual",
                    "nvl",
                    "nvl2",
                    "sysdate",
                    "systimestamp",
                    "to_number",
                    "to_number_bind",
                    "trunc",
                    "round",
                    "greatest_number",
                    "least_number",
                    "to_date",
                    "to_timestamp",
                    "to_timestamp_tz",
                    "add_months",
                    "months_between",
                    "last_day",
                    "days_interval",
                    "to_varchar2",
                    "to_char_format",
                    "concat_varchar2",
                    "apply_byte_length_semantics",
                    "substr",
                    "instr",
                    "regexp_like",
                    "regexp_count",
                    "regexp_instr",
                    "regexp_substr",
                    "regexp_replace",
                    "listagg_check",
                    "listagg_truncate",
                    "connect_by_cycle_guard");

    @Test
    void extensionSourceIsPortableAndCanonical() throws IOException {
        Path sourceDir = EXTENSION.resolve("src");
        List<String> actualModules;
        try (Stream<Path> files = Files.list(sourceDir)) {
            actualModules =
                    files.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(".sql"))
                            .sorted()
                            .toList();
        }
        assertEquals(EXTENSION_MODULES, actualModules, "Unexpected extension source layout");

        String source = readExtensionSource();
        String lower = source.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("create or replace"));
        assertFalse(lower.contains("pg_tle"));
        assertFalse(lower.contains("pgtle"));
        assertFalse(lower.contains("aurora"));
        assertFalse(lower.contains("module_pathname"));
        assertFalse(lower.contains("$libdir"));

        Matcher languages =
                Pattern.compile("(?i)\\blanguage\\s+([a-z_][a-z0-9_]*)").matcher(source);
        Set<String> supported = Set.of("sql", "plpgsql");
        while (languages.find()) {
            String language = languages.group(1).toLowerCase(Locale.ROOT);
            assertTrue(supported.contains(language), "Unsupported extension language: " + language);
        }

        try (Stream<Path> files = Files.list(EXTENSION)) {
            List<String> topLevel =
                    files.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("src"), topLevel, "extension/ must contain canonical SQL only");
        }
    }

    @Test
    void extensionApiIsDemandDriven() throws IOException {
        String source = readExtensionSource();
        for (String api : REQUIRED_RUNTIME_APIS) {
            assertTrue(source.contains("orafit." + api), "Missing runtime API: " + api);
        }

        Pattern unusedConversionBindApi =
                Pattern.compile(
                        "orafit\\.to_(date|timestamp|timestamp_tz|varchar2)_bind",
                        Pattern.CASE_INSENSITIVE);
        Pattern unusedCompareBindApi =
                Pattern.compile("orafit\\.compare_[a-z_]+_bind", Pattern.CASE_INSENSITIVE);
        assertFalse(unusedConversionBindApi.matcher(source).find());
        assertFalse(unusedCompareBindApi.matcher(source).find());
    }

    private static String readExtensionSource() throws IOException {
        StringBuilder source = new StringBuilder();
        for (String module : EXTENSION_MODULES) {
            source.append(Files.readString(EXTENSION.resolve("src").resolve(module))).append('\n');
        }
        return source.toString();
    }
}
