package io.github.felixmiddelhoff.spillgate.zstd;

import static io.github.felixmiddelhoff.spillgate.zstd.Z.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.luben.zstd.Zstd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;

/** Not a pass or fail test: run with {@code -Dspillgate.bench=true} for decode speed against zstd-jni. */
class ZstdSpeedTest {

    @Test
    @EnabledIfSystemProperty(named = "spillgate.bench", matches = "true")
    void decodeSpeedAgainstTheReference() {
        // word salad: compressible about 3:1 with plenty of short matches and literals, like real text
        byte[][] words = new byte[2000][];
        for (int i = 0; i < words.length; i++) {
            byte[] w = data(i, 2 + (i % 9));
            for (int k = 0; k < w.length; k++) {
                w[k] = (byte) ('a' + (w[k] & 0xff) % 26);
            }
            words[i] = w;
        }
        byte[] picks = data(42, 3_000_000);
        ByteArrayOutputStream pay = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < picks.length; i += 2) {
            pay.writeBytes(words[((picks[i] & 0xff) * 8 + (picks[i + 1] & 0xff) % 8) % words.length]);
            pay.write(' ');
        }
        byte[] payload = pay.toByteArray();
        for (int level : new int[] {1, 3, 9, 19}) {
            byte[] stream = compress(payload, level, params().checksum(true));
            int reps = 5;
            for (int warm = 0; warm < 2; warm++) {
                Count c = count(new ArrayInput(stream), 1L << 27, Long.MAX_VALUE);
                assertNull(c.kind);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                Count c = count(new ArrayInput(stream), 1L << 27, Long.MAX_VALUE);
                assertNull(c.kind);
                assertEquals(payload.length, c.total);
            }
            double ours = (System.nanoTime() - t0) / 1e9 / reps;
            byte[] dst = new byte[payload.length];
            t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                assertEquals(payload.length, Zstd.decompress(dst, stream));
            }
            double theirs = (System.nanoTime() - t0) / 1e9 / reps;
            double mb = payload.length / 1e6;
            System.out.printf(
                    "level %d: %d KB compressed, ours %.0f MB/s, reference %.0f MB/s%n",
                    level, stream.length / 1000, mb / ours, mb / theirs);
        }
    }
}
