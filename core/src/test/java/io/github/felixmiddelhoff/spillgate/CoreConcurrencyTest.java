package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A decoder is immutable and thread safe, streams are independent (SG-R-RDR-07). */
class CoreConcurrencyTest {
    // Covers: SG-R-RDR-07 SG-R-CDC-09
    @Test
    void sg_r_rdr_07_one_shared_decoder_works_on_16_threads() throws Exception {
        Decoder shared = decoder(out(1 << 20).withMaxLayers(8));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                results.add(pool.submit((Callable<String>) () -> {
                    start.await();
                    Rng rng = new Rng(1000 + id);
                    for (int i = 0; i < 150; i++) {
                        byte[] payload = rng.bytes((int) rng.below(3000));
                        byte[] framed = stored(payload, 1 + (int) rng.below(500));
                        int size = new int[] {1, 7, 4096, 1 << 20}[(int) rng.below(4)];
                        switch ((int) rng.below(5)) {
                            case 0: {
                                Outcome o = run(shared, payload, "pass, up", size);
                                byte[] expected = payload.clone();
                                for (int k = 0; k < expected.length; k++) {
                                    expected[k]++;
                                }
                                check(o.kind == null && Arrays.equals(expected, o.bytes), id, i, "up");
                                break;
                            }
                            case 1: {
                                Outcome o = run(shared, framed, "stored", size);
                                check(o.kind == null && Arrays.equals(payload, o.bytes), id, i, "stored");
                                break;
                            }
                            case 2: {
                                byte[] bad = framed.clone();
                                bad[bad.length - 1] ^= 1;
                                Outcome o = run(shared, bad, "pass, stored", size);
                                check(o.kind == Kind.CORRUPT && Arrays.equals(payload, o.bytes), id, i, "corrupt");
                                break;
                            }
                            case 3: {
                                Outcome o = run(shared, new byte[2000], "expand1000, pass", size);
                                check(o.kind == Kind.OUTPUT_LIMIT && o.bytes.length == (1 << 20), id, i, "limit " + o.kind + o.bytes.length);
                                break;
                            }
                            default: {
                                Outcome o = run(shared, payload, "nope", size);
                                check(o.kind == Kind.UNSUPPORTED_ENCODING, id, i, "unsupported");
                                byte[] all = shared.decodeAll(in(payload), "pass");
                                check(Arrays.equals(payload, all), id, i, "decodeAll");
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<String> f : results) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void check(boolean ok, int thread, int iteration, String what) {
        if (!ok) {
            throw new AssertionError("thread " + thread + " iteration " + iteration + ": " + what);
        }
    }

    // Covers: SG-R-RDR-07 SG-R-CDC-09
    @Test
    void sg_r_rdr_07_the_builder_result_can_be_published_to_other_threads_and_streams_do_not_interact() throws Exception {
        // many threads each decode through chains that share the same codec instances
        CoreCodecs.Pass pass = new CoreCodecs.Pass();
        Decoder shared = Decoder.builder(out(1 << 22)).codec("p", pass).build();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                final byte fill = (byte) t;
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        byte[] data = filled(5000 + i, fill);
                        byte[] out = shared.decodeAll(in(data), "p, p, p");
                        if (!Arrays.equals(data, out)) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : fs) {
                assertTrue(f.get(120, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // Covers: SG-R-RDR-07 SG-R-API-04
    @Test
    void a_cancel_token_shared_by_streams_on_many_threads() throws Exception {
        Decoder d = Decoder.builder(Limits.maxOutput(Long.MAX_VALUE).withoutRatioLimit())
                .codec("zeros", new CoreCodecs.Zeros(Long.MAX_VALUE)).build();
        CancelToken token = new CancelToken();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Kind>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                fs.add(pool.submit(() -> {
                    SpillgateInputStream s = d.reader(in(new byte[0]), token, "zeros");
                    byte[] buf = new byte[1 << 16];
                    try {
                        while (true) {
                            s.read(buf);
                        }
                    } catch (SpillgateException e) {
                        return e.kind();
                    }
                }));
            }
            Thread.sleep(100);
            token.cancel();
            for (Future<Kind> f : fs) {
                assertEquals(Kind.CANCELED, f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
