package io.github.felixmiddelhoff.spillgate.zstd;

import static io.github.felixmiddelhoff.spillgate.zstd.Z.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/** Damaged streams: truncation, every bit flip, random mutation. Never anything but a clean refusal. */
class ZstdDamageTest {

    /**
     * Decode damaged input: whatever the outcome class, never a foreign error; what we accept the
     * reference must accept with identical output. Returns true if we accepted.
     */
    private static boolean checkAgrees(byte[] damaged, String what) {
        Outcome o = drainCap(damaged, 1L << 24);
        if (o.overflow) {
            return false; // too much output to compare; no wrong error either
        }
        if (o.kind == null) {
            byte[] expected = oracle(damaged);
            assertNotNull(expected, what + ": accepted a stream the reference refuses (input " + damaged.length + " bytes)");
            assertTrue(
                    Arrays.equals(expected, o.out),
                    what + ": decoded differently from the reference (ours " + o.out.length + ", reference "
                            + expected.length + ")");
            return true;
        }
        assertTrue(o.kind == Kind.CORRUPT || o.kind == Kind.MEMORY_LIMIT, what + ": unexpected error kind " + o.kind);
        return false;
    }

    private static Outcome drainCap(byte[] damaged, long cap) {
        ZstdReader r = reader(new ArrayInput(damaged), cap);
        try {
            return drain(r, 4096, 32L * MIB);
        } finally {
            r.close();
        }
    }

    @Test
    void everyTruncationIsCorrupt() {
        // Covers: SG-R-CDC-06
        byte[] mixedSmall = concat(Arrays.copyOf(text(), 4000), data(5, 4000));
        List<byte[]> streams = List.of(
                checksummed(mixedSmall, 3),
                compress(mixedSmall, 3, params().contentSize(true)),
                concat(windowHeader(17), rawBlocks(data(1, 600), 600, true)),
                concat(windowHeader(17), rleBlocks(500, 200, 9, true)),
                concat(skippable(20), compress(ascii("tail tail tail tail"))));
        byte[] skip20 = skippable(20);
        for (byte[] stream : streams) {
            boolean startsSkippable = stream.length > 28 && Arrays.equals(Arrays.copyOf(stream, 28), skip20);
            for (int cut = 0; cut < stream.length; cut++) {
                Outcome o = run(Arrays.copyOf(stream, cut));
                if (startsSkippable && cut == 28) {
                    assertNull2(o, "a skippable frame alone is a stream");
                    continue;
                }
                assertEquals(Kind.CORRUPT, o.kind, "cut at " + cut + " of " + stream.length);
            }
        }
    }

    private static void assertNull2(Outcome o, String what) {
        assertEquals(null, o.kind, what);
    }

    @Test
    void everyBitFlipNeverThrowsAndNeverYieldsDataTheReferenceWouldNot() {
        // Covers: SG-I-05 SG-R-CDC-08
        byte[] repeated = ascii("flip every bit of this zstd frame, with repeats, repeats, repeats. ".repeat(5));
        // varied statistics so that Huffman literals and FSE sequence tables are present
        byte[] varied = Arrays.copyOf(text(), 900);
        byte[] noise = data(3, 300);
        for (int i = 0; i < noise.length; i++) {
            noise[i] &= 0x0f;
        }
        byte[] rev = new byte[900];
        for (int i = 0; i < 900; i++) {
            rev[i] = text()[899 - i];
        }
        varied = concat(varied, noise, rev);
        List<byte[]> streams = List.of(
                checksummed(repeated, 5),
                compress(repeated, 3, params().contentSize(true)),
                checksummed(varied, 9),
                compress(varied, 19, params().contentSize(false)),
                concat(windowHeader(12), rleBlocks(3000, 1024, 7, false), rawBlocks(ascii("end"), 3, true)));
        int accepted = 0;
        int total = 0;
        for (int index = 0; index < streams.size(); index++) {
            byte[] stream = streams.get(index);
            for (int position = 0; position < stream.length; position++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] damaged = stream.clone();
                    damaged[position] ^= 1 << bit;
                    total++;
                    if (checkAgrees(damaged, "stream " + index + " " + position + ":" + bit)) {
                        accepted++;
                    }
                }
            }
        }
        assertTrue(total > 3000);
        // flips in literal bytes of raw blocks and similar are legitimately accepted, but not all of them
        assertTrue(accepted < total);
    }

    @Test
    void bitFlipsInsideHuffmanAndFseTablesAreCovered() {
        // Covers: SG-I-05 SG-R-CDC-08
        // a payload large enough for four-stream Huffman literals and several sequence blocks
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            long sq = ((long) i * i) / 7 % 10;
            payload[i] = (byte) "abcdefghij".charAt((int) sq);
        }
        byte[] stream = checksummed(payload, 7);
        assertTrue(stream.length < 4000, "" + stream.length);
        int total = 0;
        for (int position = 0; position < stream.length; position++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] damaged = stream.clone();
                damaged[position] ^= 1 << bit;
                total++;
                checkAgrees(damaged, position + ":" + bit);
            }
        }
        assertTrue(total > 100);
    }

    @Test
    void randomMutationsAgreeWithTheReference() {
        // Covers: SG-I-05 SG-R-CDC-08
        String env = System.getenv("SPILLGATE_FUZZ_ROUNDS");
        int rounds = 2000;
        if (env != null) {
            try {
                rounds = Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        long[] seed = {12345};
        java.util.function.DoubleSupplier next = () -> {
            seed[0] = seed[0] * 6364136223846793005L + 1442695040888963407L;
            return (double) (seed[0] >>> 33) / (double) (1L << 31);
        };
        byte[] text = text();
        byte[] mixed = mixed();
        byte[][] bases = {Arrays.copyOf(text, 3000), Arrays.copyOf(mixed, 8000), data(5, 2000), new byte[5000]};
        for (int i = 0; i < rounds; i++) {
            byte[] base = bases[i % 4];
            Params p = params().checksum(i % 2 == 1).contentSize(i % 3 != 0);
            byte[] stream = compress(base, 1 + (i % 12), p);
            int flips = 1 + (int) (next.getAsDouble() * 4.0);
            for (int k = 0; k < flips; k++) {
                int at = (int) (next.getAsDouble() * stream.length);
                stream[Math.min(at, stream.length - 1)] = (byte) (int) (next.getAsDouble() * 256.0);
            }
            byte[] damaged = stream;
            if (next.getAsDouble() < 0.2) {
                damaged = Arrays.copyOf(stream, (int) (next.getAsDouble() * stream.length));
            }
            checkAgrees(damaged, "round " + i);
        }
    }

    @Test
    void theDecoderNeverThrowsOnShortGarbageAfterAValidHeader() {
        // Covers: SG-I-05 SG-R-CDC-08
        // compressed blocks of every small size with every fill byte after a valid header
        for (int size = 0; size < 40; size++) {
            for (int fill : new int[] {0x00, 0x01, 0x02, 0x55, 0x80, 0xfe, 0xff}) {
                byte[] stream = concat(windowHeader(17), blockHeader(true, 2, size), filled(size, fill));
                Outcome o = run(stream);
                assertTrue(o.kind == null || o.kind == Kind.CORRUPT, "size " + size + " fill " + fill);
            }
        }
        // the same with random content
        for (int seed = 0; seed < 300; seed++) {
            int size = 1 + (seed % 200);
            byte[] stream = concat(windowHeader(17), blockHeader(true, 2, size), data(seed, size));
            Outcome o = run(stream);
            assertTrue(o.kind == null || o.kind == Kind.CORRUPT, "seed " + seed);
        }
    }

    @Test
    void everyFrameHeaderPrefixIsHandled() {
        // Covers: SG-I-05 SG-R-CDC-08
        for (int descriptor = 0; descriptor < 256; descriptor++) {
            for (int second : new int[] {0, 1, 0x7f, 0x80, 0xff}) {
                byte[] stream = concat(bytes(0x28, 0xb5, 0x2f, 0xfd, descriptor, second), new byte[12]);
                Outcome o = runCap(stream, 1 << 20);
                assertTrue(
                        o.kind == null || o.kind == Kind.CORRUPT || o.kind == Kind.MEMORY_LIMIT,
                        "descriptor " + descriptor + " second " + second + " " + o);
            }
        }
    }

    @Test
    void deliveredBytesBeforeAnErrorDoNotDependOnTheReadSize() {
        // Covers: SG-R-RDR-01
        byte[] stream = checksummed(mixed(), 5);
        stream[stream.length / 2] ^= 0x40;
        Outcome reference = null;
        for (int size : new int[] {1, 3, 4096, 1 << 17}) {
            ZstdReader r = reader(new ArrayInput(stream, size == 1 ? 1 : 1 << 20), 1 << 24);
            Outcome o = drain(r, size);
            if (reference == null) {
                reference = o;
            }
            assertEquals(reference.kind, o.kind, "read size " + size);
            assertArrayEquals(reference.out, o.out, "read size " + size);
        }
    }
}
