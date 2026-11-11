package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Memory behavior (SG-R-MEM-01..03, SG-I-04, SG-I-08). The surefire configuration of this module runs
 * the tests with a small heap ({@code -Xmx96m}), so an allocation sized from {@code maxOutput} or from
 * a declared size would crash the test run, and the heap deltas below check the streaming claim.
 */
class CoreMemoryTest {
    private static Decoder with(Limits limits, String name, Codec codec) {
        try {
            return Decoder.builder(limits).codec(name, codec).codec("pass", new CoreCodecs.Pass())
                    .codec("stored", new CoreCodecs.Stored()).build();
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    // Covers: SG-R-MEM-02 SG-I-08 SG-I-01 SG-R-LAY-01
    @Test
    void sg_r_mem_02_a_4_gib_bomb_ends_at_the_budget_in_constant_memory() throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() < 256L << 20,
                "the small heap of the surefire configuration is not in effect: " + Runtime.getRuntime().maxMemory());
        long max = 100L << 20;
        Decoder d = with(out(max), "zeros", new CoreCodecs.Zeros(4L << 30));
        long before = usedHeap();
        SpillgateInputStream r = d.reader(in(new byte[0]), "zeros");
        byte[] buf = new byte[1 << 20];
        long total = 0;
        Kind kind = null;
        while (kind == null) {
            try {
                int n = r.read(buf);
                assertNotEquals(-1, n, "the bomb must not look like a clean end");
                assertTrue(n <= 64 * 1024);
                total += n;
            } catch (SpillgateException e) {
                kind = e.kind();
            }
        }
        assertEquals(max, total);
        assertEquals(Kind.OUTPUT_LIMIT, kind);
        r.close();
        long after = usedHeap();
        // generous: 100 MiB went through, the retained difference must be tiny (garbage may be uncollected
        // while the stream runs, so the delta after a full collection is what counts)
        assertTrue(after - before < 16L << 20, "heap grew by " + (after - before) + " bytes");
    }

    // Covers: SG-R-MEM-02 SG-I-08
    @Test
    void sg_r_mem_02_one_byte_reads_cost_constant_memory() throws Exception {
        Decoder d = with(out(2_000_000), "zeros", new CoreCodecs.Zeros(Long.MAX_VALUE));
        long before = usedHeap();
        SpillgateInputStream r = d.reader(in(new byte[0]), "zeros");
        long count = 0;
        try {
            while (true) {
                if (r.read() < 0) {
                    fail("end");
                }
                count++;
            }
        } catch (SpillgateException e) {
            assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        }
        assertEquals(2_000_000, count);
        r.close();
        assertTrue(usedHeap() - before < 16L << 20);
    }

    // Covers: SG-R-MEM-02 SG-R-CDC-07 SG-R-MEM-01 SG-I-04
    @Test
    void sg_r_mem_02_decode_all_never_preallocates_from_max_output() throws Exception {
        // A preallocation of 2^45 or 2^63-1 bytes would be an OutOfMemoryError or a NegativeArraySize
        // error; growing with the output does neither (and the heap here is only 96 MiB).
        for (long max : new long[] {1L << 40, 1L << 45, 1L << 62, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            byte[] out = Decoder.decodeAll(in("small body".getBytes()), Limits.maxOutput(max));
            assertArrayEquals("small body".getBytes(), out);
            Decoder d = with(Limits.maxOutput(max).withoutRatioLimit(), "p", new CoreCodecs.Pass());
            assertArrayEquals("small body".getBytes(), d.decodeAll(in("small body".getBytes()), "p, p"));
            assertArrayEquals("tiny".getBytes(), decoder(Limits.maxOutput(max)).decodeAll(in(stored("tiny".getBytes(), 2)), "stored"));
            assertEquals(0, d.decodeAll(in(new byte[0])).length);
        }
    }

    // Covers: SG-R-MEM-02 SG-R-API-02
    @Test
    void sg_r_mem_02_decode_all_of_a_failing_stream_returns_no_data() {
        Decoder d = with(out(10L << 20), "zeros", new CoreCodecs.Zeros(1L << 40));
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.decodeAll(in(new byte[0]), "zeros"));
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        assertEquals(10L << 20, e.limit());
        // a body of a few MiB works within the small heap
        Decoder ok = with(out(10L << 20), "zeros", new CoreCodecs.Zeros(5L << 20));
        try {
            assertEquals(5 << 20, ok.decodeAll(in(new byte[0]), "zeros").length);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    // Covers: SG-R-MEM-01 SG-R-CDC-07 SG-R-LAY-08 SG-I-04
    @Test
    void sg_r_mem_01_declared_sizes_do_not_size_allocations() {
        // The stored format declares chunk lengths up to 65535; a header that declares a chunk and
        // then ends costs nothing and is corrupt.
        Decoder d = decoder(Limits.maxOutput(1L << 40));
        for (int declared : new int[] {1, 1000, 65535}) {
            byte[] input = {(byte) declared, (byte) (declared >> 8), 'a', 'b', 'c'};
            Outcome o = run(d, input, "stored", 4096);
            assertEquals(Kind.CORRUPT, o.kind);
            assertTrue(o.bytes.length <= 3);
        }
    }

    // Covers: SG-R-MEM-03 SG-I-10
    @Test
    void sg_r_mem_03_a_chain_of_sixteen_layers_can_be_built_and_dropped_many_times() throws Exception {
        Decoder d = decoder(out(1 << 20).withMaxLayers(16));
        String header = chain("pass", 16);
        long before = usedHeap();
        for (int i = 0; i < 2000; i++) {
            SpillgateInputStream r = d.reader(in("abc".getBytes()), header);
            assertArrayEquals("abc".getBytes(), r.readAllBytes());
            if (i % 2 == 0) {
                r.close();
            }
        }
        assertTrue(usedHeap() - before < 16L << 20);
    }

    // Covers: SG-R-MEM-03 SG-R-MEM-02
    @Test
    void the_documented_memory_bound_of_a_stream_holds() throws Exception {
        // 4 KiB input buffer plus 64 KiB scratch per layer: 16 layers stay far below 4 MiB
        Decoder d = decoder(out(1 << 28).withMaxLayers(16));
        String header = chain("pass", 16);
        int streams = 40;
        long before = usedHeap();
        SpillgateInputStream[] open = new SpillgateInputStream[streams];
        for (int i = 0; i < streams; i++) {
            open[i] = d.reader(new Endless(3), header);
            byte[] buf = new byte[1 << 20];
            assertTrue(open[i].read(buf) > 0);
        }
        long held = usedHeap() - before;
        long perStream = held / streams;
        assertTrue(perStream < 4L << 20, "a 16-layer stream holds " + perStream + " bytes");
        for (SpillgateInputStream s : open) {
            s.close();
        }
    }

    // Covers: SG-R-MEM-02 SG-R-LAY-09
    @Test
    void a_huge_read_size_is_capped_to_one_step() throws Exception {
        Decoder d = with(out(1L << 40), "zeros", new CoreCodecs.Zeros(Long.MAX_VALUE));
        SpillgateInputStream r = d.reader(in(new byte[0]), "zeros");
        byte[] huge = new byte[8 << 20];
        long before = usedHeap();
        for (int i = 0; i < 20; i++) {
            assertTrue(r.read(huge) <= 64 * 1024);
        }
        r.close();
        assertTrue(usedHeap() - before < 16L << 20);
    }

    // Covers: SG-R-MEM-02
    @Test
    void the_input_buffer_is_a_fixed_4_kib() throws Exception {
        // A source that offers a gigabyte "virtually" is only ever asked for 4 KiB at a time.
        Endless source = new Endless(1);
        Decoder d = decoder(out(1 << 20));
        SpillgateInputStream r = d.reader(source, "pass, pass");
        byte[] buf = new byte[100_000];
        r.read(buf);
        assertTrue(source.largestRequest.get() <= 4096);
    }
}
