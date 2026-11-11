package io.github.felixmiddelhoff.spillgate.zstd;

import static io.github.felixmiddelhoff.spillgate.zstd.Z.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/** Framing: concatenated and skippable frames, trailing data, sizes, checksums, input accounting. */
class ZstdFramingTest {

    @Test
    void concatenatedAndSkippableFrames() {
        // Covers: SG-R-CDC-05
        byte[] a = compress(ascii("first "));
        byte[] b = compress(ascii("second"));
        Outcome o = run(concat(a, b));
        assertNull(o.kind);
        assertArrayEquals(ascii("first second"), o.out);
        byte[] skip = skippable(5);
        o = run(concat(skip, a, skip, b, skip));
        assertNull(o.kind);
        assertArrayEquals(ascii("first second"), o.out);
        // every skippable magic 0x184D2A50..=0x184D2A5F
        for (int low = 0; low < 16; low++) {
            byte[] s = skippable(3);
            s[0] = (byte) (0x50 + low);
            o = run(concat(s, a));
            assertNull(o.kind, "magic " + low);
            assertArrayEquals(ascii("first "), o.out);
        }
        // a skippable frame alone is a stream with no output
        o = run(skip);
        assertNull(o.kind);
        assertEquals(0, o.out.length);
        // a frame of an empty payload
        o = run(compress(new byte[0]));
        assertNull(o.kind);
        assertEquals(0, o.out.length);
        // many frames, one budget
        o = run(repeat(a, 50));
        assertNull(o.kind);
        assertEquals(6 * 50, o.out.length);
    }

    @Test
    void skippableFramesWithBigDeclaredSizesAreTruncation() {
        // Covers: SG-R-CDC-05 SG-R-CDC-06 SG-R-CDC-07
        byte[] s = concat(bytes(0x50, 0x2a, 0x4d, 0x18), le(0xffffffffL, 4), new byte[100]);
        assertEquals(Kind.CORRUPT, run(s).kind);
        // a skippable frame of 3 MiB is skipped without holding it
        Outcome o = run(concat(skippable(3 * MIB), compress(ascii("after"))));
        assertNull(o.kind);
        assertArrayEquals(ascii("after"), o.out);
    }

    @Test
    void skippableFramesAreMeteredInSmallSteps() throws IOException {
        // Covers: SG-R-CDC-05 SG-R-RAT-02
        // the codec never consumes more than 4 KiB of a skippable frame at once
        byte[] stream = concat(skippable(100_000), compress(ascii("end")));
        ArrayInput in = new ArrayInput(stream, 1 << 20);
        ZstdReader r = reader(in, DEFAULT_MAX_WINDOW);
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        byte[] buf = new byte[16];
        int n;
        while ((n = r.read(buf, 0, buf.length)) >= 0) {
            seen.write(buf, 0, n);
        }
        assertArrayEquals(ascii("end"), seen.toByteArray());
        assertEquals(stream.length, in.consumed());
        // the skipped bytes were consumed in steps of at most 4096 (the compressed frame is read in larger parts)
        ArrayInput skipOnly = new ArrayInput(skippable(100_000), 1 << 20);
        Outcome o = drain(reader(skipOnly, DEFAULT_MAX_WINDOW), 16);
        assertNull(o.kind);
        assertTrue(skipOnly.maxConsume <= 4096, "" + skipOnly.maxConsume);
        assertEquals(100_008, skipOnly.consumed());
    }

    @Test
    void manySkippableFramesAreCheap() {
        // Covers: SG-R-CDC-05 SG-R-RAT-02
        byte[] one = skippable(0);
        byte[] stream = concat(repeat(one, 30_000), compress(ascii("end")));
        Instant started = Instant.now();
        Outcome o = run(stream);
        assertNull(o.kind);
        assertArrayEquals(ascii("end"), o.out);
        assertTrue(Duration.between(started, Instant.now()).toSeconds() < 10);
    }

    @Test
    void dataAfterTheLastFrameIsCorruptAndSoIsAnEmptyInput() {
        // Covers: SG-R-CDC-05 SG-R-CDC-06
        byte[] stream = compress(text());
        byte[][] tails = {
            bytes(0),
            bytes(1, 2, 3),
            bytes(0x28, 0xb5, 0x2f),
            bytes(0x50, 0x2a, 0x4d, 0x18),
            new byte[100],
            bytes(0x28, 0xb5, 0x2f, 0xfc),
        };
        for (byte[] tail : tails) {
            Outcome o = run(concat(stream, tail));
            assertEquals(Kind.CORRUPT, o.kind, Arrays.toString(tail));
            assertArrayEquals(text(), o.out, "the data of the valid frame is delivered first");
        }
        assertEquals(Kind.CORRUPT, run(new byte[0]).kind);
        assertEquals(Kind.CORRUPT, run(new byte[4]).kind);
        // garbage before the first frame
        assertEquals(Kind.CORRUPT, run(concat(bytes(1, 2, 3, 4), stream)).kind);
    }

    @Test
    void aLyingContentSizeNeverRaisesAnAllowanceAndIsCheckedAtTheEnd() {
        // Covers: SG-R-CDC-07 SG-I-04
        byte[] real = filled(1000, 120);
        byte[] big = concat(header(h().windowLog(17).size(1L << 40)), rawBlocks(real, 1000, true));
        Outcome o = run(big);
        assertEquals(Kind.CORRUPT, o.kind); // 1000 produced, 2^40 declared
        assertArrayEquals(real, o.out, "the bytes were produced and delivered first");
        byte[] small = concat(header(h().single().size(10)), rawBlocks(real, 1000, true));
        assertEquals(Kind.CORRUPT, run(small).kind);
        // a content size that is right
        byte[] right = concat(header(h().single().size(1000)), rawBlocks(real, 1000, true));
        o = run(right);
        assertNull(o.kind);
        assertArrayEquals(real, o.out);
    }

    @Test
    void anEmptyLastBlockCannotHideAContentSizeMismatch() {
        // Covers: SG-R-CDC-05
        byte[] frame = concat(header(h().single().size(128)), bytes(0x01, 0x00, 0x00));
        assertEquals(Kind.CORRUPT, run(frame).kind);
    }

    @Test
    void rawRleAndEmptyCompressedBlocks() {
        // Covers: SG-R-CDC-05
        byte[] frame = concat(windowHeader(17), rleBlocks(200_000, 131_072, 7, false), rawBlocks(ascii("tail"), 131_072, true));
        Outcome o = run(frame);
        assertNull(o.kind);
        assertEquals(200_004, o.out.length);
        for (int i = 0; i < 200_000; i++) {
            assertEquals(7, o.out[i]);
        }
        assertArrayEquals(ascii("tail"), Arrays.copyOfRange(o.out, 200_000, 200_004));
        // type 2, size 0, last: a compressed block without a literals section
        assertEquals(Kind.CORRUPT, run(concat(windowHeader(17), bytes(0x05, 0x00, 0x00))).kind);
        // a stream of empty raw blocks
        byte[] empties = concat(
                windowHeader(17), blockHeader(false, 0, 0), blockHeader(false, 0, 0), blockHeader(true, 0, 0));
        o = run(empties);
        assertNull(o.kind);
        assertEquals(0, o.out.length);
    }

    @Test
    void theCodecConsumesExactlyTheInputItUses() {
        // Covers: SG-R-CDC-01 SG-R-RAT-02
        byte[] a = checksummed(text(), 3);
        byte[] b = compress(ascii("b"));
        byte[] stream = concat(skippable(10), a, b);
        for (int step : new int[] {1, 5, 4096, 1 << 20}) {
            ArrayInput in = new ArrayInput(stream, step);
            Outcome o = drain(reader(in, DEFAULT_MAX_WINDOW), 1000);
            assertNull(o.kind);
            assertEquals(text().length + 1, o.out.length);
            assertEquals(stream.length, in.consumed(), "step " + step);
        }
        // with trailing garbage the codec stops at the first byte that is not a frame
        byte[] withTail = concat(a, new byte[3]);
        ArrayInput in = new ArrayInput(withTail, 1 << 20);
        Outcome o = drain(reader(in, DEFAULT_MAX_WINDOW), 1000);
        assertEquals(Kind.CORRUPT, o.kind);
        assertTrue(in.consumed() >= a.length && in.consumed() <= withTail.length);
    }

    @Test
    void readSizesAndInputChunkingDoNotChangeTheResult() throws IOException {
        // Covers: SG-R-RDR-01 SG-I-09
        byte[] payload = mixed();
        byte[] stream = compress(payload, 6, params().checksum(true));
        for (int size : new int[] {1, 7, 4096, 1 << 20}) {
            ZstdReader r = reader(new ArrayInput(stream), 1L << 24);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[size];
            // one byte reads of the whole payload would take long: stop after a prefix there
            long limit = size == 1 ? 5000 : Long.MAX_VALUE;
            while (out.size() < limit) {
                int n = r.read(buf, 0, size);
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            byte[] got = out.toByteArray();
            assertArrayEquals(Arrays.copyOf(payload, got.length), got, "read size " + size);
            if (size != 1) {
                assertEquals(payload.length, got.length, "read size " + size);
            }
        }
        for (int step : new int[] {1, 3, 100, 4096}) {
            Outcome o = drain(reader(new ArrayInput(stream, step), 1L << 24), 8192);
            assertNull(o.kind, "step " + step);
            assertArrayEquals(payload, o.out, "step " + step);
        }
    }

    @Test
    void aFailingSourcePassesThroughAndErrorsAreSticky() throws IOException {
        // Covers: SG-R-RDR-05
        byte[] stream = checksummed(text(), 3);
        for (int ok : new int[] {0, 2, 6, 10, stream.length - 2}) {
            ZstdReader r = reader(new FailingInput(stream, ok), DEFAULT_MAX_WINDOW);
            byte[] buf = new byte[4096];
            IOException first = null;
            while (first == null) {
                try {
                    if (r.read(buf, 0, buf.length) < 0) {
                        throw new AssertionError("a clean end after a failing source, fails after " + ok);
                    }
                } catch (IOException e) {
                    first = e;
                }
            }
            assertEquals("source failed", first.getMessage(), "fails after " + ok);
            IOException again = assertThrows(IOException.class, () -> r.read(buf, 0, 8));
            assertEquals("source failed", again.getMessage());
        }
        ZstdReader corrupt = reader(new ArrayInput(bytes(1, 2, 3, 4)), 1 << 20);
        for (int i = 0; i < 3; i++) {
            SpillgateException e = assertThrows(SpillgateException.class, () -> corrupt.read(new byte[8], 0, 8));
            assertEquals(Kind.CORRUPT, e.kind());
        }
        // a clean end stays a clean end
        byte[] one = compress(ascii("x"));
        ZstdReader done = reader(new ArrayInput(one), 1 << 20);
        Outcome o = drain(done, 8);
        assertNull(o.kind);
        assertArrayEquals(ascii("x"), o.out);
        assertEquals(-1, done.read(new byte[8], 0, 8));
        assertEquals(-1, done.read(new byte[8], 0, 8));
        // an empty buffer is not an end
        ZstdReader again = reader(new ArrayInput(one), 1 << 20);
        assertEquals(0, again.read(new byte[0], 0, 0));
        assertEquals(1, again.read(new byte[8], 0, 8));
    }

    @Test
    void deferredChecksumErrorIsRaisedAfterTheDataAndStays() throws IOException {
        // Covers: SG-R-CDC-05 SG-R-LAY-07
        // The deferred-error rule: the bytes of the last block are delivered, then the error.
        byte[] payload = text();
        byte[] stream = checksummed(payload, 3);
        stream[stream.length - 1] ^= 1;
        Outcome o = run(stream);
        assertEquals(Kind.CORRUPT, o.kind);
        assertArrayEquals(payload, o.out, "all bytes of the last block are delivered before the checksum error");
        // and with tiny reads the error comes only after the last byte, and repeats
        ZstdReader r = reader(new ArrayInput(stream, 7), 1 << 24);
        long total = 0;
        byte[] buf = new byte[3];
        while (true) {
            try {
                int n = r.read(buf, 0, 3);
                assertTrue(n > 0);
                total += n;
            } catch (SpillgateException e) {
                assertEquals(Kind.CORRUPT, e.kind());
                break;
            }
        }
        assertEquals(payload.length, total);
        assertEquals(Kind.CORRUPT, assertThrows(SpillgateException.class, () -> r.read(buf, 0, 3)).kind());
    }

    @Test
    void closeIsIdempotentAndReleasesState() throws IOException {
        // Covers: SG-R-MEM-02
        ZstdReader r = reader(new ArrayInput(compress(text())), 1 << 24);
        assertEquals(3, r.read(new byte[3], 0, 3));
        assertTrue(r.heapBytes() > 0);
        r.close();
        r.close();
        assertEquals(0, r.heapBytes());
        CodecReader c = r;
        assertThrows(IOException.class, () -> c.read(new byte[3], 0, 3));
    }

    @Test
    void capSettingsAreValidated() throws SpillgateException {
        // Covers: SG-R-CDC-09
        for (long bad : new long[] {0, 1, 1023, (1L << 30) + 1, Long.MAX_VALUE, -1, Long.MIN_VALUE}) {
            SpillgateException e = assertThrows(
                    SpillgateException.class, () -> ZstdCodec.create(ZstdOptions.defaults().withMaxWindowBytes(bad)));
            assertEquals(Kind.INVALID_OPTION, e.kind(), "" + bad);
        }
        assertEquals(Kind.INVALID_OPTION, assertThrows(SpillgateException.class, () -> ZstdCodec.create(null)).kind());
        for (long good : new long[] {1024, 1025, DEFAULT_MAX_WINDOW, 1L << 30}) {
            assertEquals(good, ZstdCodec.create(ZstdOptions.defaults().withMaxWindowBytes(good)).maxWindowBytes());
        }
        assertEquals(8 * 1024 * 1024, ZstdOptions.defaults().maxWindowBytes());
        assertEquals(DEFAULT_MAX_WINDOW, ZstdCodec.create(ZstdOptions.defaults()).maxWindowBytes());
    }

    @Test
    void framesWithoutOutputCostBoundedCpuAndNoWindow() {
        // Covers: SG-R-RAT-02 SG-R-MEM-02
        byte[] frame = concat(windowHeader(23), rawBlocks(new byte[0], 1024, true));
        byte[] stream = repeat(frame, 30_000);
        Instant started = Instant.now();
        Count c = count(new ArrayInput(stream), DEFAULT_MAX_WINDOW, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(0, c.total);
        assertTrue(Duration.between(started, Instant.now()).toSeconds() < 10);
        assertTrue(c.peakHeap < MIB, "" + c.peakHeap);
    }

    @Test
    void thirtyThousandTinyCompressedBlocksCostBoundedTime() {
        // Covers: SG-R-RAT-02 SG-R-MEM-02
        byte[] payload = repeat(ascii("hello hello hello hello hello hello hello hello hello hello hello world "), 4);
        byte[] single = compress(payload, 3, params().contentSize(false));
        assertEquals(0, single[4], "expected a plain frame header without content size");
        byte[] block = Arrays.copyOfRange(single, 6, single.length);
        assertEquals(2, (block[0] >> 1) & 3, "expected a compressed block");
        // one frame per block (30000 frames) ...
        byte[] frames = repeat(single, 30_000);
        Instant started = Instant.now();
        Count c = count(new ArrayInput(frames), DEFAULT_MAX_WINDOW, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(30_000L * payload.length, c.total);
        assertTrue(Duration.between(started, Instant.now()).toSeconds() < 10);
        assertTrue(c.peakHeap < MIB, "" + c.peakHeap);
        // ... and 30000 compressed blocks in one frame
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        one.write(single, 0, 6);
        for (int i = 0; i < 29_999; i++) {
            byte[] b = block.clone();
            b[0] &= ~1; // not the last block
            one.writeBytes(b);
        }
        one.writeBytes(block);
        started = Instant.now();
        c = count(new ArrayInput(one.toByteArray()), DEFAULT_MAX_WINDOW, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(30_000L * payload.length, c.total);
        assertTrue(Duration.between(started, Instant.now()).toSeconds() < 10);
        assertTrue(c.peakHeap < MIB, "" + c.peakHeap);
    }

    @Test
    void noBlockSizedZeroFillForATinyBlock() {
        // Covers: SG-R-RAT-02 SG-R-MEM-02
        // one scratch buffer per reader: tiny blocks must not grow the buffers to the block maximum
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.writeBytes(windowHeader(23));
        for (int i = 0; i < 1000; i++) {
            s.writeBytes(blockHeader(false, 0, 3));
            s.writeBytes(bytes(i, 1, 2));
        }
        s.writeBytes(rawBlocks(ascii("z"), 1, true));
        Count c = count(new ArrayInput(s.toByteArray()), DEFAULT_MAX_WINDOW, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(3001, c.total);
        assertTrue(c.peakHeap < 64 * 1024, "" + c.peakHeap);
    }
}
