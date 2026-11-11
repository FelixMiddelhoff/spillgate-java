package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BrotliFuzzTest {
    private static final long LIMIT = 32L << 20;

    /** Only CORRUPT / MEMORY_LIMIT / a clean end; a clean end must agree with the reference decoder. */
    private static void check(byte[] input, int chunk, String what) {
        Support.Outcome o = Support.run(input, 24, chunk, 4096 + (chunk & 7), LIMIT);
        Kind k = o.kind();
        if (k == Kind.OUTPUT_LIMIT) {
            return;
        }
        assertTrue(k == null || k == Kind.CORRUPT || k == Kind.MEMORY_LIMIT, what + ": " + k);
        assertTrue(o.violations() == 0, what + ": input contract violated");
        if (k == null) {
            byte[] ref = Support.reference(input);
            assertTrue(ref != null, what + ": accepted a stream the reference decoder rejects");
            assertArrayEquals(ref, o.bytes(), what);
            assertTrue(o.consumed() == input.length, what + ": consumed " + o.consumed() + " of " + input.length);
        }
    }

    @Test
    void everyBitFlipOfSeveralStreams() {
        // Covers: SG-R-CDC-06 SG-R-CDC-08 SG-I-05
        for (String n : new String[] {
            "text.q5.w16.br", "text.q11.w24.br", "mixed.q0.w16.br", "zeros.q0.w16.br", "one.q5.w16.br", "empty.q5.w16.br",
            "noise.q5.w10.br", "prose.q0.w10.br"
        }) {
            byte[] s = Support.resource(n);
            int maxBits = Math.min(s.length * 8, 12_000);
            Random rnd = new Random(7);
            for (int k = 0; k < maxBits; k++) {
                int bit = s.length * 8 <= maxBits ? k : rnd.nextInt(s.length * 8);
                byte[] t = s.clone();
                t[bit >> 3] ^= (byte) (1 << (bit & 7));
                check(t, k % 3 == 0 ? 1 : Integer.MAX_VALUE, n + " bit " + bit);
            }
        }
    }

    @Test
    void seededMutationFuzz() {
        // Covers: SG-R-CDC-08 SG-I-05
        int rounds = 2000;
        String env = System.getenv("SPILLGATE_FUZZ_ROUNDS");
        if (env != null && !env.isBlank()) {
            rounds = Integer.parseInt(env.trim());
        }
        var goldens = Support.goldens();
        Random rnd = new Random(20260101L);
        for (int i = 0; i < rounds; i++) {
            String[] g = goldens.get(rnd.nextInt(goldens.size()));
            byte[] s = Support.resource(g[0]);
            byte[] t = s.clone();
            int m = 1 + rnd.nextInt(4);
            for (int j = 0; j < m && t.length > 0; j++) {
                switch (rnd.nextInt(5)) {
                    case 0 -> t[rnd.nextInt(t.length)] = (byte) rnd.nextInt(256);
                    case 1 -> t = Arrays.copyOf(t, rnd.nextInt(t.length + 1));
                    case 2 -> {
                        byte[] x = new byte[t.length + 1];
                        int p = rnd.nextInt(t.length + 1);
                        System.arraycopy(t, 0, x, 0, p);
                        x[p] = (byte) rnd.nextInt(256);
                        System.arraycopy(t, p, x, p + 1, t.length - p);
                        t = x;
                    }
                    case 3 -> {
                        int p = rnd.nextInt(t.length);
                        byte[] x = new byte[t.length - 1];
                        System.arraycopy(t, 0, x, 0, p);
                        System.arraycopy(t, p + 1, x, p, t.length - p - 1);
                        t = x;
                    }
                    default -> t[rnd.nextInt(t.length)] ^= (byte) (1 << rnd.nextInt(8));
                }
            }
            check(t, new int[] {1, 3, 37, 4096, Integer.MAX_VALUE}[rnd.nextInt(5)], g[0] + " round " + i);
        }
    }

    @Test
    void hostileFirstBytesAndRandomGarbage() {
        // Covers: SG-R-CDC-08 SG-R-CDC-04
        Random rnd = new Random(99);
        for (int i = 0; i < 3000; i++) {
            byte[] t = new byte[1 + rnd.nextInt(200)];
            rnd.nextBytes(t);
            check(t, Integer.MAX_VALUE, "garbage " + i);
        }
    }
}
