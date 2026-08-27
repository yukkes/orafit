package io.github.orafit.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class CompatibilityScopeTest {
    @Test
    void declaredScopeIsBackedByCanonicalOracleEvidence() throws Exception {
        Path scope =
                Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."))
                        .resolve("src/test/resources/oracle/scope.toml");
        TomlParseResult document = Toml.parse(scope);
        assertTrue(document.errors().isEmpty(), () -> document.errors().toString());
        assertEquals(1L, document.getLong("version"));
        assertEquals("Oracle Database 18c XE", document.getString("target"));

        Map<String, CompatibilityCase.Mode> caseModes = new HashMap<>();
        for (CompatibilityCase test : CompatibilityCases.list()) {
            assertNull(
                    caseModes.put(test.id(), test.mode()), "duplicate canonical case " + test.id());
        }

        TomlArray features = document.getArray("feature");
        assertNotNull(features);
        assertFalse(features.isEmpty());
        Set<String> featureIds = new HashSet<>();
        int evidenceRefs = 0;

        for (int i = 0; i < features.size(); i++) {
            TomlTable feature = features.getTable(i);
            String id = feature.getString("id");
            String ownership = feature.getString("scope");
            String contract = feature.getString("contract");
            TomlArray evidence = feature.getArray("evidence");

            assertNotNull(id, "feature id");
            assertFalse(id.isBlank(), "feature id");
            assertTrue(featureIds.add(id), "duplicate feature id: " + id);
            assertTrue(Set.of("owned", "outside").contains(ownership), id + ": invalid scope");

            if ("outside".equals(ownership)) {
                assertNull(contract, id + ": outside feature must not claim contract");
                assertNull(evidence, id + ": outside feature must not claim evidence");
                continue;
            }

            assertTrue(
                    Set.of("same", "bounded", "reject").contains(contract),
                    id + ": owned feature requires same/bounded/reject");
            if (!"same".equals(contract)) {
                assertNull(evidence, id + ": evidence is reserved for same contracts");
                continue;
            }

            assertNotNull(evidence, id + ": same contract requires evidence");
            assertFalse(evidence.isEmpty(), id + ": same contract requires evidence");
            Set<String> refs = new HashSet<>();
            for (int j = 0; j < evidence.size(); j++) {
                String caseId = evidence.getString(j);
                assertNotNull(caseId, id + ": evidence id");
                assertTrue(refs.add(caseId), id + ": duplicate evidence " + caseId);
                assertEquals(
                        CompatibilityCase.Mode.SAME,
                        caseModes.get(caseId),
                        id + ": evidence must be canonical SAME case: " + caseId);
                evidenceRefs++;
            }
        }

        assertTrue(evidenceRefs > 0);
    }
}
