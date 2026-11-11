package io.github.felixmiddelhoff.spillgate.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class GeneratorsTest {
    private static final Path ROOT = Path.of("..", "conformance");

    @Test
    void crc32OfZerosMatchesTheTableCrc() {
        for (long n : new long[] {0, 1, 2, 7, 8, 255, 256, 1000, 65537, 1 << 20}) {
            assertEquals(Generators.crc32(new byte[(int) n]), Generators.crc32OfZeros(n), "n = " + n);
        }
    }

    @Test
    void knownChecksums() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xcbf43926, Generators.crc32(check));
        CRC32 jdk = new CRC32();
        jdk.update(check);
        assertEquals((int) jdk.getValue(), Generators.crc32(check));
        assertEquals(0x11e60398, Generators.adler32("Wikipedia".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Generators.adler32(new byte[10]), Generators.adler32OfZeros(10));
    }

    @Test
    void multiGibZeroPayloadsBuildQuickly() {
        long start = System.nanoTime();
        byte[] gz = Generators.generate(new Spec.Zeros("gzip", 20_000_000L, 0, 0));
        assertTrue(gz.length > 4_000_000);
        byte[] br = Generators.brotliZeros(8L << 30, 24);
        byte[] zs = Generators.zstdZeros(8L << 30, 27);
        assertTrue(br.length < 16384 && zs.length < 1 << 20);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 20_000, "generators are too slow");
    }

    @Test
    void everyManifestInputMatchesItsHash() throws IOException {
        JsonObject manifest = JsonParser.parseString(Files.readString(ROOT.resolve("manifest.json"))).getAsJsonObject();
        JsonArray vectors = manifest.getAsJsonArray("vectors");
        int checked = 0;
        List<String> bad = new ArrayList<>();
        for (JsonElement el : vectors) {
            JsonObject vector = el.getAsJsonObject();
            String id = vector.get("id").getAsString();
            JsonObject input = vector.getAsJsonObject("input");
            String want = input.get("sha256").getAsString();
            byte[] stream;
            if (input.has("generator")) {
                stream = Generators.generate(JsonSpec.parse(input.getAsJsonObject("generator")));
            } else {
                stream = Files.readAllBytes(ROOT.resolve(input.get("file").getAsString()));
            }
            checked++;
            if (!Generators.sha256Hex(stream).equals(want)) {
                bad.add(id);
            }
        }
        System.out.println("manifest inputs checked: " + checked + ", mismatches: " + bad);
        assertEquals(353, checked);
        assertTrue(bad.isEmpty(), "mismatching inputs: " + bad);
    }
}
