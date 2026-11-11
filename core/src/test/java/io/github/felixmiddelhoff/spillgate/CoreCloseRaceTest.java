package io.github.felixmiddelhoff.spillgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * close() from a watchdog thread while another thread reads is the one supported way to share a
 * stream between threads: the reader sees bytes, a clean end or "stream closed", never a
 * NullPointerException or an INTERNAL error that the close caused.
 */
class CoreCloseRaceTest {
    @Test
    void close_during_read_never_surfaces_an_npe_or_internal() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
            g.write(new byte[3 << 20]);
        }
        byte[] gz = bo.toByteArray();
        Decoder d = Decoder.of(Limits.maxOutput(4 << 20).withoutRatioLimit());
        Map<String, Integer> outcomes = new TreeMap<>();
        Random rnd = new Random(11);
        int[] readSizes = {70000, 100, 1};
        for (int i = 0; i < 3000; i++) {
            SpillgateInputStream s = d.reader(new ByteArrayInputStream(gz), "gzip");
            int spin = rnd.nextInt(20000);
            Thread closer = new Thread(() -> {
                for (int k = 0; k < spin; k++) {
                    Thread.onSpinWait();
                }
                s.close();
            });
            closer.start();
            byte[] buf = new byte[70000];
            int size = readSizes[i % readSizes.length];
            String outcome;
            try {
                while (s.read(buf, 0, size) > 0) {
                    // read on
                }
                outcome = "ended";
            } catch (SpillgateException e) {
                outcome = "SG-" + e.kind();
                assertTrue(e.kind() != Kind.INTERNAL, "INTERNAL after a concurrent close: " + e);
            } catch (IOException e) {
                outcome = e.getMessage();
                assertEquals("stream closed", e.getMessage());
            }
            // no NullPointerException (or any other RuntimeException) escapes: it would fail the test here
            closer.join();
            outcomes.merge(outcome, 1, Integer::sum);
            // after close() every read is the plain "stream closed"
            try {
                s.read(buf, 0, 1);
                throw new AssertionError("read after close succeeded");
            } catch (SpillgateException e) {
                throw new AssertionError("a SpillgateException after close: " + e);
            } catch (IOException e) {
                assertEquals("stream closed", e.getMessage());
            }
        }
        System.out.println("close-race outcomes: " + outcomes);
    }
}
