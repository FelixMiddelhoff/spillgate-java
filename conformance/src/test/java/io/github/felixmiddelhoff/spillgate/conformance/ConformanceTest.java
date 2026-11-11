package io.github.felixmiddelhoff.spillgate.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.felixmiddelhoff.spillgate.Decoder;
import io.github.felixmiddelhoff.spillgate.DecoderBuilder;
import io.github.felixmiddelhoff.spillgate.Limits;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliCodec;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliOptions;
import io.github.felixmiddelhoff.spillgate.testsupport.Generators;
import io.github.felixmiddelhoff.spillgate.testsupport.JsonSpec;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdCodec;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared conformance corpus ({@code conformance/SCHEMA.md}) against this implementation. All codecs are on
 * the classpath, so no vector is skipped; the large vectors are generated, not skipped.
 */
class ConformanceTest {
    /** Every vector must give the same result for these read sizes (SG-R-RDR-01). -1 is "MaxOutput + 1, capped". */
    private static final long[][] ALTERNATIVE_PATTERNS = {{1}, {3}, {4096}, {-1}, {1, 7, 4096, 1}};

    /** The cap of the -1 read size. */
    private static final long MAX_READ = 32L * 1024 * 1024;

    /** A source that counts the bytes taken and the reads made, to prove what was taken (SG-R-INP-02, SG-R-HDR-09). */
    private static final class SpySource extends InputStream {
        private final byte[] data;
        private int pos;
        long reads;
        long total;

        SpySource(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            reads++;
            if (pos >= data.length) {
                return -1;
            }
            total++;
            return data[pos++] & 0xff;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            reads++;
            if (len == 0) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, buf, off, n);
            pos += n;
            total += n;
            return n;
        }
    }

    /**
     * One limit value: a JSON number or a decimal string (values above 2^53-1). Negative values are passed through so
     * that the Limits validation sees them; values the API cannot express (fractional, unparsable) become 0.
     */
    private static long limitValue(JsonElement value) {
        if (value != null && value.isJsonPrimitive()) {
            JsonPrimitive p = value.getAsJsonPrimitive();
            try {
                return p.isString() ? Long.parseLong(p.getAsString()) : p.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static int clampInt(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private static Limits buildLimits(JsonObject vector) {
        JsonObject raw = vector.getAsJsonObject("limits");
        // An absent max_output is expressed as 0: both are invalid_limits (SG-R-LIM-01).
        Limits limits = Limits.maxOutput(raw.has("max_output") ? limitValue(raw.get("max_output")) : 0);
        if (raw.has("max_layers")) {
            limits = limits.withMaxLayers(clampInt(limitValue(raw.get("max_layers"))));
        }
        if (raw.has("ratio_grace")) {
            limits = limits.withRatioGrace(limitValue(raw.get("ratio_grace")));
        }
        if (raw.has("max_input")) {
            limits = limits.withMaxInput(limitValue(raw.get("max_input")));
        }
        if (raw.has("max_ratio")) {
            JsonElement v = raw.get("max_ratio");
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && "none".equals(v.getAsString())) {
                limits = limits.withoutRatioLimit();
            } else {
                limits = limits.withMaxRatio(limitValue(v));
            }
        }
        return limits;
    }

    private static List<String> neededCodecs(JsonObject vector) {
        List<String> out = new ArrayList<>();
        if (vector.has("tags")) {
            for (JsonElement t : vector.getAsJsonArray("tags")) {
                String tag = t.getAsString();
                if (tag.startsWith("needs-")) {
                    out.add(tag.substring("needs-".length()));
                }
            }
        }
        return out;
    }

    /** The largest brotli window bits (10..24) whose window, 2^n - 16 bytes, fits {@code limit}. */
    static int windowBitsWithin(long limit) {
        int bits = 10;
        while (bits < 24 && (1L << (bits + 1)) - 16 <= limit) {
            bits++;
        }
        return bits;
    }

    private static DecoderBuilder addCodec(DecoderBuilder builder, String name, long memoryCap) throws SpillgateException {
        switch (name) {
            case "br": {
                BrotliOptions options = BrotliOptions.defaults();
                if (memoryCap != 0) {
                    options = options.withMaxWindowBits(windowBitsWithin(memoryCap));
                }
                return builder.codec("br", BrotliCodec.create(options));
            }
            case "zstd": {
                ZstdOptions options = ZstdOptions.defaults();
                if (memoryCap != 0) {
                    options = options.withMaxWindowBytes(memoryCap);
                }
                return builder.codec("zstd", ZstdCodec.create(options));
            }
            default:
                throw new IllegalStateException("codec " + name + " is not available");
        }
    }

    private static String outcomeOf(IOException error) {
        if (error instanceof SpillgateException e) {
            return e.kind().specName();
        }
        return "other: " + error;
    }

    private static final class ReadResult {
        long delivered;
        String digest;
        IOException error;
    }

    private static ReadResult readWithPattern(InputStream reader, long[] pattern, long maxOutput) throws IOException {
        int[] sizes = new int[pattern.length];
        int largest = 1;
        for (int i = 0; i < pattern.length; i++) {
            sizes[i] = pattern[i] < 0 ? (int) (Math.min(maxOutput, MAX_READ - 1) + 1) : (int) pattern[i];
            largest = Math.max(largest, sizes[i]);
        }
        byte[] buf = new byte[largest];
        MessageDigest digest = Generators.newSha256();
        ReadResult r = new ReadResult();
        for (long i = 0;; i++) {
            int size = sizes[(int) (i % sizes.length)];
            int n;
            try {
                n = reader.read(buf, 0, size);
            } catch (IOException e) {
                r.error = e;
                break;
            }
            if (n < 0) {
                break;
            }
            digest.update(buf, 0, n);
            r.delivered += n;
        }
        r.digest = Generators.hex(digest.digest());
        return r;
    }

    private static boolean skipTiny(JsonObject vector, long[] pattern) {
        JsonObject e = vector.getAsJsonObject("expect");
        long expected = 0;
        for (String k : new String[] {"length", "delivered_exactly", "delivered_at_most"}) {
            if (e.has(k)) {
                expected = limitValue(e.get(k));
                break;
            }
        }
        if (expected <= 4L * 1024 * 1024) {
            return false;
        }
        for (long s : pattern) {
            if (s >= 0 && s < 64) {
                return true;
            }
        }
        return false;
    }

    private static String describe(long[] pattern) {
        return java.util.Arrays.toString(pattern);
    }

    private static void runPattern(JsonObject vector, byte[] stream, long[] pattern) throws IOException {
        String id = vector.get("id").getAsString();
        String label = id + " read pattern " + describe(pattern);
        JsonObject expect = vector.getAsJsonObject("expect");
        String wantOutcome = expect.get("outcome").getAsString();
        SpySource source = new SpySource(stream);

        List<String> encodings = new ArrayList<>();
        for (JsonElement e : vector.getAsJsonArray("encodings")) {
            encodings.add(e.getAsString());
        }
        JsonObject options = vector.has("options") ? vector.getAsJsonObject("options") : new JsonObject();
        boolean strictZlib = options.has("strict_zlib") && options.get("strict_zlib").getAsBoolean();
        long memoryCap = options.has("memory_cap") ? limitValue(options.get("memory_cap")) : 0;
        JsonObject rawLimits = vector.getAsJsonObject("limits");
        long maxOutput = rawLimits.has("max_output") ? limitValue(rawLimits.get("max_output")) : 0;

        boolean expectConstruct = expect.has("phase") && "construct".equals(expect.get("phase").getAsString());
        InputStream reader;
        try {
            DecoderBuilder builder = Decoder.builder(buildLimits(vector)).strictZlib(strictZlib);
            for (String name : neededCodecs(vector)) {
                builder = addCodec(builder, name, memoryCap);
            }
            Decoder decoder = builder.build();
            reader = decoder.reader(source, encodings.toArray(new String[0]));
        } catch (SpillgateException error) {
            if (!expectConstruct) {
                fail(label + ": unexpected construction error " + error);
            }
            String got = error.kind().specName();
            if (!got.equals(wantOutcome)) {
                fail(label + ": construction error " + got + ", want " + wantOutcome + ": " + error);
            }
            if (source.reads != 0) {
                fail(label + ": the source was read before the construction error");
            }
            return;
        }
        if (expectConstruct) {
            reader.close();
            fail(label + ": expected " + wantOutcome + " at construction");
        }

        try {
            ReadResult result = readWithPattern(reader, pattern, maxOutput);
            String outcome = result.error == null ? "ok" : outcomeOf(result.error);
            checkOutcome(vector, label, result, outcome, maxOutput);
            // Sticky (SG-R-LAY-05): further reads return the same outcome and no data.
            for (int i = 0; i < 3; i++) {
                byte[] one = new byte[16];
                String again;
                try {
                    int n = reader.read(one, 0, one.length);
                    again = n < 0 ? "ok" : n + " more bytes";
                } catch (IOException e) {
                    again = outcomeOf(e);
                }
                if (!again.equals(outcome)) {
                    fail(label + ": not sticky: " + outcome + " then " + again);
                }
            }
        } finally {
            reader.close();
        }
        if (rawLimits.has("max_input")) {
            long cap = limitValue(rawLimits.get("max_input"));
            if (source.total > cap + 1) {
                fail(label + ": took " + source.total + " bytes from the source (SG-R-INP-02)");
            }
        }
    }

    private static void checkOutcome(JsonObject vector, String label, ReadResult r, String outcome, long maxOutput) {
        JsonObject expect = vector.getAsJsonObject("expect");
        String want = expect.get("outcome").getAsString();
        if (r.delivered > maxOutput) {
            fail(label + ": delivered " + r.delivered + " > max_output " + maxOutput);
        }
        if (!outcome.equals(want)) {
            fail(label + ": outcome " + outcome + ", want " + want + " (delivered " + r.delivered + "; "
                    + (r.error == null ? "" : r.error) + ")");
        }
        if ("ok".equals(want)) {
            long length = expect.has("length") ? limitValue(expect.get("length")) : -1;
            if (r.delivered != length) {
                fail(label + ": delivered " + r.delivered + ", want " + length);
            }
            if (!expect.get("sha256").getAsString().equals(r.digest)) {
                fail(label + ": wrong data");
            }
            return;
        }
        if (!expect.has("phase") || !"read".equals(expect.get("phase").getAsString())) {
            fail(label + ": a read error but a construction error was expected");
        }
        if (expect.has("delivered_exactly")) {
            long exact = limitValue(expect.get("delivered_exactly"));
            if (r.delivered != exact) {
                fail(label + ": delivered " + r.delivered + ", want exactly " + exact);
            }
        }
        if (expect.has("delivered_at_most")) {
            long most = limitValue(expect.get("delivered_at_most"));
            if (r.delivered > most) {
                fail(label + ": delivered " + r.delivered + ", want at most " + most);
            }
        }
        if (expect.has("layer") && r.error instanceof SpillgateException e) {
            long layer = limitValue(expect.get("layer"));
            if (e.layer() != layer) {
                fail(label + ": error in layer " + e.layer() + ", want " + layer);
            }
        }
    }

    private static byte[] buildInput(JsonObject vector) throws IOException {
        String id = vector.get("id").getAsString();
        JsonObject input = vector.getAsJsonObject("input");
        byte[] stream;
        if (input.has("generator")) {
            stream = Generators.generate(JsonSpec.parse(input.getAsJsonObject("generator")));
        } else if (input.has("file")) {
            stream = Files.readAllBytes(Support.root().resolve("conformance").resolve(input.get("file").getAsString()));
        } else {
            throw new IllegalStateException(id + ": input without file or generator");
        }
        if (!Generators.sha256Hex(stream).equals(input.get("sha256").getAsString())) {
            throw new IllegalStateException(id + ": the input differs from the manifest (generator or file drift)");
        }
        return stream;
    }

    private static void runVector(JsonObject vector) throws IOException {
        byte[] stream = buildInput(vector);
        List<long[]> patterns = new ArrayList<>();
        if (vector.has("read_pattern")) {
            JsonArray a = vector.getAsJsonArray("read_pattern");
            long[] first = new long[a.size()];
            for (int i = 0; i < first.length; i++) {
                first[i] = a.get(i).getAsLong();
            }
            patterns.add(first);
        } else {
            patterns.add(new long[] {4096});
        }
        patterns.addAll(List.of(ALTERNATIVE_PATTERNS));
        for (long[] pattern : patterns) {
            if (!skipTiny(vector, pattern)) {
                runPattern(vector, stream, pattern);
            }
        }
    }

    @Test
    void conformanceCorpus() {
        JsonObject manifest = Support.readManifest();
        int passed = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (JsonElement el : manifest.getAsJsonArray("vectors")) {
            JsonObject vector = el.getAsJsonObject();
            String id = vector.get("id").getAsString();
            try {
                runVector(vector);
                passed++;
            } catch (AssertionError | RuntimeException | IOException e) {
                failures.add(e instanceof AssertionError || e instanceof IllegalStateException
                        ? e.getMessage() : id + ": " + e);
            }
        }
        System.out.println("conformance: " + passed + " passed, " + skipped + " skipped, " + failures.size() + " failed");
        for (String f : failures) {
            System.out.println("FAIL " + f);
        }
        assertTrue(failures.isEmpty(), failures.size() + " vectors failed, first: " + (failures.isEmpty() ? "" : failures.get(0)));
        assertEquals(353, passed + skipped);
        assertEquals(0, skipped);
    }

    @Test
    void theManifestIsWellFormed() throws IOException {
        String spec = Files.readString(Support.root().resolve("spec").resolve("SPEC.md"));
        Set<String> rules = Support.definedRules(spec);
        JsonObject manifest = Support.readManifest();
        assertEquals(1, manifest.get("schema_version").getAsInt());
        JsonArray vectors = manifest.getAsJsonArray("vectors");
        assertTrue(vectors.size() >= 200);
        Set<String> seen = new HashSet<>();
        for (JsonElement el : vectors) {
            JsonObject vector = el.getAsJsonObject();
            String id = vector.get("id").getAsString();
            assertTrue(id.matches("SG-V-[A-Z]+-[0-9]{4}"), "bad vector id " + id);
            assertTrue(seen.add(id), "duplicate " + id);
            assertFalse(vector.get("description").getAsString().isEmpty(), id + ": no description");
            JsonArray named = vector.getAsJsonArray("rules");
            assertNotEquals(0, named.size(), id + ": no rules");
            for (JsonElement rule : named) {
                assertTrue(rules.contains(rule.getAsString()), id + " names an undefined rule " + rule.getAsString());
            }
            String sha = vector.getAsJsonObject("input").get("sha256").getAsString();
            assertTrue(sha.matches("[0-9a-f]{64}"), id + ": bad sha256");
        }
    }
}
