package io.github.felixmiddelhoff.spillgate.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every rule and invariant of {@code spec/SPEC.md} must be covered: named in the {@code rules} of a conformance
 * vector, or in a Java file under the test directories of the modules (a {@code // Covers:} comment or a test name).
 * It is a checklist, not proof that a test asserts everything a rule says. Set {@code SPILLGATE_SPECTRACE_WARN=1}
 * to print the uncovered rules without failing.
 */
class SpecTraceTest {
    @Test
    void everyRuleIsCovered() throws IOException {
        Path root = Support.root();
        String spec = Files.readString(root.resolve("spec").resolve("SPEC.md"), StandardCharsets.UTF_8);
        Set<String> defined = Support.definedRules(spec);
        assertTrue(defined.size() > 50, "found only " + defined.size() + " rules in the spec: the extraction is broken");

        Set<String> covered = new TreeSet<>();
        Set<String> unknownInVectors = new TreeSet<>();
        for (JsonElement el : Support.readManifest().getAsJsonArray("vectors")) {
            JsonObject vector = el.getAsJsonObject();
            for (JsonElement r : vector.getAsJsonArray("rules")) {
                String rule = r.getAsString();
                if (!defined.contains(rule)) {
                    unknownInVectors.add(vector.get("id").getAsString() + " names " + rule);
                }
                covered.add(rule);
            }
        }

        Path[] dirs = {
            root.resolve("core/src/test"), root.resolve("zstd/src/test"), root.resolve("brotli/src/test"),
            root.resolve("conformance/src/test"), root.resolve("test-support/src/test"),
        };
        for (Path file : Support.javaFilesBelow(dirs)) {
            covered.addAll(Support.ruleIdsIn(Files.readString(file, StandardCharsets.UTF_8)));
        }

        Set<String> missing = new TreeSet<>(defined);
        missing.removeAll(covered);
        System.out.println("defined: " + defined.size() + ", covered: " + (defined.size() - missing.size())
                + ", uncovered: " + missing.size());
        for (String id : missing) {
            System.out.println("uncovered: " + id);
        }
        for (String entry : unknownInVectors) {
            System.out.println("names a rule the spec does not define: " + entry);
        }
        assertTrue(unknownInVectors.isEmpty(), "vectors name rules the spec does not define: " + unknownInVectors);
        if (!"1".equals(System.getenv("SPILLGATE_SPECTRACE_WARN"))) {
            assertTrue(missing.isEmpty(), missing.size() + " rules are not covered: " + missing);
        }
    }
}
