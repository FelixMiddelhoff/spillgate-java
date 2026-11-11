package io.github.felixmiddelhoff.spillgate.conformance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Helpers shared by {@link ConformanceTest} and {@link SpecTraceTest}. */
final class Support {
    private Support() { }

    private static final Pattern RULE = Pattern.compile("SG-R-[A-Z]+-[0-9]{2}|SG-I-[0-9]{2}");

    /** The repository root (where {@code conformance/} and {@code spec/} live). */
    static Path root() {
        return Path.of(System.getProperty("spillgate.root", "..")).toAbsolutePath().normalize();
    }

    static JsonObject readManifest() {
        try {
            String text = Files.readString(root().resolve("conformance").resolve("manifest.json"), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every rule ID ({@code SG-R-AAA-nn}, {@code SG-I-nn}) mentioned anywhere in {@code text}. */
    static Set<String> ruleIdsIn(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = RULE.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    /** The rules and invariants the spec defines: IDs in bold at the start of a line or bullet. */
    static Set<String> definedRules(String spec) {
        Set<String> defined = new TreeSet<>();
        for (String raw : spec.split("\r?\n")) {
            String line = raw.startsWith("- ") ? raw.substring(2) : raw;
            if (line.startsWith("**")) {
                int close = line.indexOf("**", 2);
                if (close >= 0) {
                    defined.addAll(ruleIdsIn(line.substring(2, close)));
                }
            }
        }
        return defined;
    }

    /** All {@code .java} files below the given directories (those that exist). */
    static List<Path> javaFilesBelow(Path... dirs) throws IOException {
        List<Path> out = new java.util.ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }
}
