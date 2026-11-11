package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Layer chaining: depth, identity layers, per-layer budgets, intermediate limits, error layers. */
class CoreChainTest {
    private static SpillgateException failure(InputStream in, byte[] out) {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (true) {
            try {
                int n = in.read(buf);
                assertNotEquals(-1, n, "expected an error, got the end");
                collected.write(buf, 0, n);
            } catch (SpillgateException e) {
                if (out != null) {
                    System.arraycopy(collected.toByteArray(), 0, out, 0, Math.min(out.length, collected.size()));
                }
                lastDelivered = collected.size();
                return e;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static int lastDelivered;

    private static SpillgateInputStream open(Decoder d, byte[] data, String... header) {
        try {
            return d.reader(in(data), header);
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    // Covers: SG-R-HDR-11 SG-R-HDR-06 SG-I-07 SG-R-LAY-01
    @Test
    void sg_r_hdr_11_chain_depth_up_to_the_limit() {
        byte[] payload = data(10_000);
        for (int depth = 1; depth <= 16; depth++) {
            Decoder d = decoder(out(1 << 20).withMaxLayers(16));
            String header = chain("pass", depth);
            for (int size : new int[] {1, 4096, 1 << 20}) {
                Outcome o = run(d, payload, header, size);
                assertNull(o.kind, "depth " + depth + " size " + size);
                assertArrayEquals(payload, o.bytes, "depth " + depth + " size " + size);
            }
            // one layer fewer than the depth in maxLayers is refused at construction
            if (depth >= 2) {
                Decoder strict = decoder(out(1 << 20).withMaxLayers(depth - 1));
                assertEquals(Kind.LAYER_LIMIT, assertThrows(SpillgateException.class, () -> strict.reader(in(payload), header)).kind());
            }
        }
    }

    // Covers: SG-R-HDR-10 SG-R-CDC-10
    @Test
    void sg_r_hdr_10_identity_layers_are_no_ops() {
        Decoder d = decoder(out(1 << 20).withMaxLayers(2));
        byte[] payload = data(3000);
        byte[] framed = stored(payload, 500);
        for (String header : new String[] {
            "stored", "identity, stored", "stored, identity", "identity, stored, identity, identity",
            "identity,identity,identity,identity,identity,identity,stored",
        }) {
            Outcome o = run(d, framed, header, 100);
            assertNull(o.kind, header);
            assertArrayEquals(payload, o.bytes, header);
        }
        // identity is bounded and counted like everything else
        Decoder tight = decoder(out(10));
        Outcome o = run(tight, data(11), "identity, identity", 4);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertEquals(10, o.bytes.length);
    }

    // Covers: SG-R-HDR-12
    @Test
    void sg_r_hdr_12_layers_apply_in_reverse_order_of_the_header() {
        Decoder d = decoder(loose());
        byte[] input = b(1, 2, 200);
        assertArrayEquals(b(4, 6, 146), run(d, input, "dbl, up", 8).bytes);
        assertArrayEquals(b(3, 5, 145), run(d, input, "up, dbl", 8).bytes);
        assertArrayEquals(b(5, 7, 147), run(d, input, "up, up, up, dbl", 8).bytes);
        // stored framing is layer 1 (listed last), the transform is layer 2
        assertArrayEquals(b(2, 3, 201), run(d, stored(input, 2), "up, stored", 8).bytes);
    }

    // Covers: SG-R-LAY-01 SG-R-LAY-02 SG-I-01
    @Test
    void sg_r_lay_01_every_layer_has_its_own_budget() throws Exception {
        // layer 1 = expand10 turns 100 bytes into 1000, layer 2 = shrink10 turns them into 100.
        byte[] source = data(100);
        String header = "shrink10, expand10";
        Outcome ok = run(decoder(out(1000)), source, header, 64);
        assertNull(ok.kind);
        assertEquals(100, ok.bytes.length);
        // the final output (100) is far below maxOutput (999), but layer 1 would produce 1000
        Decoder tight = decoder(out(999));
        for (int size : new int[] {1, 64, 1 << 20}) {
            SpillgateInputStream r = tight.reader(in(source), header);
            SpillgateException e = failure(r, null);
            assertEquals(Kind.OUTPUT_LIMIT, e.kind(), "size " + size);
            assertEquals(1, e.layer());
            assertEquals(999, e.limit());
            assertTrue(lastDelivered <= 100);
            // sticky
            assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, () -> r.read(new byte[size])).kind());
        }
        // layer 1 exactly at the limit is fine
        assertNull(run(decoder(out(1000)), source, header, 7).kind);
    }

    // Covers: SG-R-LAY-01 SG-R-API-05
    @Test
    void sg_r_lay_01_the_second_layer_names_itself() {
        // layer 1 = pass (100 bytes), layer 2 = expand10 (1000 bytes) against maxOutput 500
        Decoder d = decoder(out(500));
        SpillgateException e = failure(open(d, data(100), "expand10, pass"), null);
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        assertEquals(2, e.layer());
        assertEquals(500, e.limit());
        assertEquals(500, lastDelivered);
    }

    // Covers: SG-R-LAY-01 SG-R-LAY-07
    @Test
    void sg_r_lay_01_an_intermediate_layer_exceeding_the_limit_is_output_limit_even_if_later_layers_shrink() {
        Decoder d = decoder(out(50));
        Outcome o = run(d, data(100), "shrink1000, pass", 16);
        // layer 1 = pass: 100 bytes > 50, layer 2 would shrink to 1 byte
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertEquals(1, o.layer);
        Decoder d2 = decoder(out(100));
        o = run(d2, data(100), "shrink1000, pass", 16);
        assertNull(o.kind);
        assertEquals(1, o.bytes.length);
    }

    // Covers: SG-R-LAY-01 SG-R-LAY-05 SG-R-API-05
    @Test
    void errors_of_a_lower_layer_pass_through_with_their_layer() throws Exception {
        Decoder d = decoder(loose());
        // layer 1 (stored) hits garbage
        SpillgateException e = failure(open(d, b(5, 0, 1), "pass, up, stored"), null);
        assertEquals(Kind.CORRUPT, e.kind());
        assertEquals(1, e.layer());
        // layer 2 (stored) gets garbage from a healthy layer 1
        e = failure(open(d, b(5, 0, 1, 2, 3, 4, 5), "stored, pass"), null);
        assertEquals(Kind.CORRUPT, e.kind());
        assertEquals(2, e.layer());
        // layer 3 of 3
        SpillgateInputStream r = open(d, new byte[7], "stored, up, pass");
        e = failure(r, null);
        assertEquals(Kind.CORRUPT, e.kind());
        assertEquals(3, e.layer());
        // sticky after passing through the layers above
        for (int i = 0; i < 3; i++) {
            SpillgateException again = assertThrows(SpillgateException.class, () -> r.read(new byte[8]));
            assertEquals(Kind.CORRUPT, again.kind());
            assertEquals(3, again.layer());
        }
    }

    // Covers: SG-R-RDR-05 SG-R-API-05
    @Test
    void a_source_error_names_layer_one_in_a_long_chain() {
        Decoder d = decoder(loose());
        FailAfter source = new FailAfter(filled(10, 1), () -> new IOException("down"));
        SpillgateInputStream r;
        try {
            r = d.reader(source, "pass, up, dbl, pass");
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
        SpillgateException e = failure(r, null);
        assertEquals(Kind.SOURCE_ERROR, e.kind());
        assertEquals(1, e.layer());
        assertEquals(10, lastDelivered);
    }

    // Covers: SG-R-INP-01 SG-R-API-05
    @Test
    void an_input_limit_in_a_long_chain_names_layer_one() {
        Decoder d = decoder(out(1 << 20).withMaxInput(100));
        SpillgateException e = failure(open(d, filled(1000, 1), "pass, up, dbl"), null);
        assertEquals(Kind.INPUT_LIMIT, e.kind());
        assertEquals(1, e.layer());
        assertEquals(100, e.limit());
        assertEquals(100, lastDelivered);
    }

    // Covers: SG-R-RDR-01 SG-I-09
    @Test
    void chains_are_independent_of_read_sizes_and_source_chunking() {
        byte[] payload = data(20_000);
        byte[] framed = stored(payload, 1500);
        byte[] framedTwice = stored(framed, 999);
        Decoder d = decoder(loose());
        Outcome reference = run(d, framedTwice, "stored, stored", 1 << 20);
        assertNull(reference.kind);
        assertArrayEquals(payload, reference.bytes);
        for (int size : new int[] {1, 2, 5, 4095, 4096, 4097, 65536}) {
            assertEquals(reference, run(d, framedTwice, "stored, stored", size), "size " + size);
            for (int step : new int[] {1, 3, 4096}) {
                Outcome o = runStream(d, new Chunked(framedTwice, step), h("stored, stored"), size);
                assertEquals(reference, o, "size " + size + " step " + step);
            }
        }
    }

    // Covers: SG-R-LAY-01 SG-R-RAT-01 SG-I-01
    @Test
    void a_long_chain_of_shrinking_and_expanding_layers() {
        // 100 -> x10 -> /10 -> x10 -> /10 : every layer stays within 1000
        Decoder d = decoder(out(1000).withMaxLayers(4));
        Outcome o = run(d, data(100), "shrink10, expand10, shrink10, expand10", 33);
        assertNull(o.kind);
        assertEquals(100, o.bytes.length);
        // with the default ratio of 1000 and no grace, 10x layers are fine
        d = decoder(Limits.maxOutput(1000).withRatioGrace(0));
        o = run(d, data(100), "shrink10, expand10, shrink10, expand10", 33);
        assertNull(o.kind);
        assertEquals(100, o.bytes.length);
    }

    // Covers: SG-R-LAY-01 SG-I-01 SG-I-08
    @Test
    void a_hostile_chain_of_expanding_layers_stops_at_the_first_budget() {
        Decoder d = decoder(out(1_000_000).withMaxLayers(16));
        SpillgateException e = failure(open(d, filled(16, 1), chain("expand1000", 8)), null);
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        assertTrue(lastDelivered <= 1_000_000);
        // the failing layer is the last one that was asked for more than its budget; layer 1
        // produced only 16000 bytes, so a later layer is blamed
        assertTrue(e.layer() >= 2, "layer " + e.layer());
    }

    // Covers: SG-R-RDR-05 SG-R-API-01
    @Test
    void nested_decoding_of_a_spillgate_stream_as_a_source() throws Exception {
        // The output of one stream is a valid source for another; its typed error passes through.
        Decoder d = decoder(loose());
        byte[] framed = stored(data(5000), 100);
        SpillgateInputStream inner = d.reader(in(framed), "stored");
        SpillgateInputStream outer = d.reader(inner, "up");
        Outcome o = drain(outer, 777);
        assertNull(o.kind);
        assertEquals(5000, o.bytes.length);
        SpillgateInputStream bad = d.reader(in(b(9, 0)), "stored");
        SpillgateInputStream outer2 = d.reader(bad, "up");
        o = drain(outer2, 777);
        assertEquals(Kind.CORRUPT, o.kind);
        // and a limit of the inner decoder is the inner error
        Decoder tight = decoder(out(10));
        SpillgateInputStream limited = tight.reader(in(data(100)));
        o = drain(d.reader(limited, "pass"), 64);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertEquals(10, o.bytes.length);
    }

    // Covers: SG-R-LAY-01 SG-R-LAY-02
    @Test
    void each_layer_budget_counts_its_own_bytes_in_a_mixed_chain() {
        // layer 1 shrinks 1000 to 100, layer 2 expands to 1000 again: the budget of 1000 holds at both
        byte[] input = data(1000);
        Outcome o = run(decoder(out(1000)), input, "expand10, shrink10", 50);
        assertNull(o.kind);
        assertEquals(1000, o.bytes.length);
        o = run(decoder(out(999)), input, "expand10, shrink10", 50);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertEquals(2, o.layer);
        assertEquals(999, o.bytes.length);
    }

    // Covers: SG-R-HDR-11 SG-R-INP-01
    @Test
    void every_layer_gets_its_own_meter_and_the_input_cap_only_the_first() {
        Decoder d = decoder(out(1 << 20).withMaxInput(3));
        // 3 bytes in, expanded to 30 by layer 1, passed through two more layers that read 30 bytes each
        Outcome o = run(d, filled(3, 1), "pass, pass, expand10", 5);
        assertNull(o.kind);
        assertEquals(30, o.bytes.length);
    }

    // Covers: SG-R-CDC-01
    @Test
    void identity_only_chain_has_one_layer_with_the_budget() {
        Decoder d = decoder(out(5));
        Outcome o = run(d, data(6), h("identity"), 3);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertEquals(1, o.layer);
        assertEquals(5, o.limit);
    }
}
