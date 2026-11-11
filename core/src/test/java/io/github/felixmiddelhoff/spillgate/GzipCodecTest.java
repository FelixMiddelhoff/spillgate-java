package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.DeflateTestKit.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class GzipCodecTest {
    private static final int[] READS = {1, 2, 3, 5, 4096};
    private static final int[] CHUNKS = {1, 3, 4096};

    private static Result run(byte[] in, int read, int chunk) {
        return drive(new GzipCodec(), in, read, chunk);
    }

    private static Result run(byte[] in) {
        return run(in, 4096, 4096);
    }

    private static byte[] zeros(int n, int v) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) v);
        return b;
    }

    // Covers: SG-R-CDC-02
    @Test
    void roundTripAndConcatenatedMembers() {
        byte[] a = text(10000, 1);
        byte[] b = random(3000, 2);
        byte[] c = new byte[0];
        byte[] in = concat(gzip(a), gzip(c), gzip(b), gzip(c));
        for (int read : READS) {
            for (int chunk : CHUNKS) {
                Result r = run(in, read, chunk);
                assertNull(r.error, r.toString());
                assertArrayEquals(concat(a, b), r.output);
                assertEquals(in.length, r.consumed);
            }
        }
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-06
    @Test
    void emptyInputAndEveryTruncationAreCorrupt() {
        assertEquals(Kind.CORRUPT, run(new byte[0]).error);
        byte[] one = gzip(text(2000, 3));
        byte[] in = concat(one, gzip(text(10, 4)));
        for (int cut = 0; cut < in.length; cut++) {
            if (cut == one.length) {
                continue; // a clean boundary
            }
            Result r = run(Arrays.copyOf(in, cut), 4096, 4096);
            assertEquals(Kind.CORRUPT, r.error, "cut " + cut);
        }
        assertNull(run(Arrays.copyOf(in, one.length)).error);
    }

    // Covers: SG-R-CDC-02
    @Test
    void bytesAfterAMemberThatDoNotStartAMemberAreCorrupt() {
        byte[] data = text(500, 1);
        byte[] g = gzip(data);
        for (byte[] tail : new byte[][] {{0}, new byte[100], {0x1f}, {0x1f, (byte) 0x8b}, {0x1f, (byte) 0x8b, 8},
            {'x', 'y', 'z'}, {0x1f, (byte) 0x8b, 9, 0, 0, 0, 0, 0, 0, 0}}) {
            for (int chunk : CHUNKS) {
                Result r = run(concat(g, tail), 4096, chunk);
                assertEquals(Kind.CORRUPT, r.error, Arrays.toString(tail));
                assertArrayEquals(data, r.output); // delivered before the next header is judged
            }
        }
    }

    // Covers: SG-R-CDC-02
    @Test
    void headerRules() {
        byte[] data = text(300, 5);
        // reserved flag bits
        for (int bit : new int[] {0x20, 0x40, 0x80}) {
            assertEquals(Kind.CORRUPT, run(gzipMember(bit, new byte[0], data)).error);
        }
        // FTEXT is fine
        assertNull(run(gzipMember(1, new byte[0], data)).error);
        // wrong magic and method
        byte[] g = gzip(data);
        for (int i = 0; i < 3; i++) {
            byte[] bad = g.clone();
            bad[i] ^= 1;
            assertEquals(Kind.CORRUPT, run(bad).error);
        }
        // FEXTRA with 0, 1 and 65535 bytes
        for (int xlen : new int[] {0, 1, 65535}) {
            byte[] extra = concat(new byte[] {(byte) xlen, (byte) (xlen >> 8)}, zeros(xlen, 7));
            for (int chunk : CHUNKS) {
                Result r = run(gzipMember(4, extra, data), 4096, chunk);
                assertNull(r.error, "xlen " + xlen + " " + r);
                assertArrayEquals(data, r.output);
            }
        }
        // truncated extra
        byte[] ex = concat(new byte[] {10, 0}, zeros(5, 1));
        byte[] cut = Arrays.copyOf(gzipMember(4, ex, new byte[0]), 10 + ex.length);
        assertEquals(Kind.CORRUPT, run(cut, 100, 100).error);
    }

    // Covers: SG-R-CDC-02
    @Test
    void nameAndCommentBoundedTo512BytesIncludingTerminator() {
        byte[] data = text(100, 6);
        for (int flag : new int[] {8, 16}) {
            for (int nameLen : new int[] {0, 1, 100, 511, 512, 513, 5000}) {
                byte[] name = concat(zeros(nameLen, 'n'), new byte[] {0});
                Result r = run(gzipMember(flag, name, data));
                if (nameLen <= 511) {
                    assertNull(r.error, flag + " " + nameLen);
                    assertArrayEquals(data, r.output);
                } else {
                    assertEquals(Kind.CORRUPT, r.error, flag + " " + nameLen);
                }
            }
            // unterminated until the end of input
            assertEquals(Kind.CORRUPT, run(concat(new byte[] {0x1f, (byte) 0x8b, 8, (byte) flag, 0, 0, 0, 0, 0, 0}, zeros(50, 'a'))).error);
        }
        // both name and comment, each at the limit
        byte[] both = concat(zeros(511, 'a'), new byte[] {0}, zeros(511, 'b'), new byte[] {0});
        assertNull(run(gzipMember(8 | 16, both, data)).error);
    }

    private static byte[] withHcrc(int flags, byte[] mid, byte[] data, int xor) {
        byte[] fixed = {0x1f, (byte) 0x8b, 8, (byte) (flags | 2), 0, 0, 0, 0, 0, (byte) 0xff};
        CRC32 c = new CRC32();
        c.update(fixed);
        c.update(mid);
        int h = (int) (c.getValue() & 0xffff) ^ xor;
        byte[] member = gzipMember(flags | 2, concat(mid, new byte[] {(byte) h, (byte) (h >> 8)}), data);
        return member;
    }

    // Covers: SG-R-CDC-02
    @Test
    void headerCrcIsVerified() {
        byte[] data = text(100, 7);
        byte[] mid = concat(new byte[] {'f', 'o', 'o', 0});
        for (int chunk : CHUNKS) {
            Result ok = run(withHcrc(8, mid, data, 0), 4096, chunk);
            assertNull(ok.error, ok.toString());
            assertArrayEquals(data, ok.output);
            assertEquals(Kind.CORRUPT, run(withHcrc(8, mid, data, 1), 4096, chunk).error);
            assertEquals(Kind.CORRUPT, run(withHcrc(8, mid, data, 0x8000), 4096, chunk).error);
        }
        // only FHCRC
        assertNull(run(withHcrc(0, new byte[0], data, 0)).error);
        assertEquals(Kind.CORRUPT, run(withHcrc(0, new byte[0], data, 4)).error);
        // extra + name + comment + hcrc
        byte[] all = concat(new byte[] {3, 0, 1, 2, 3}, new byte[] {'a', 0}, new byte[] {'b', 0});
        assertNull(run(withHcrc(4 | 8 | 16, all, data, 0)).error);
        assertEquals(Kind.CORRUPT, run(withHcrc(4 | 8 | 16, all, data, 2)).error);
    }

    // Covers: SG-R-CDC-02
    @Test
    void trailerCrcAndIsizeAreChecked() {
        byte[] data = text(4000, 8);
        byte[] g = gzip(data);
        for (int i = 1; i <= 8; i++) {
            byte[] bad = g.clone();
            bad[bad.length - i] ^= 0x10;
            for (int read : READS) {
                Result r = run(bad, read, 4096);
                assertEquals(Kind.CORRUPT, r.error, "trailer byte " + i);
                assertArrayEquals(data, r.output, "all body bytes arrive first, read " + read);
            }
        }
        // a bad trailer in the second member
        byte[] bad2 = concat(g, g);
        bad2[bad2.length - 3] ^= 1;
        Result r = run(bad2, 1, 1);
        assertEquals(Kind.CORRUPT, r.error);
        assertArrayEquals(concat(data, data), r.output);
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-07
    @Test
    void isizeIsComparedModulo2Pow32AndNeverAnAllowance() {
        // The size counter is a long masked to 32 bits; check the masking with a member whose ISIZE
        // is claimed larger than it is: corrupt, whatever the declared value.
        byte[] data = text(100, 9);
        byte[] g = gzip(data);
        byte[] bad = g.clone();
        bad[bad.length - 1] = 0x7f; // ISIZE claims about 2 GiB
        assertEquals(Kind.CORRUPT, run(bad).error);
        bad = g.clone();
        bad[bad.length - 4] = (byte) (bad[bad.length - 4] + 1); // 101 instead of 100
        assertEquals(Kind.CORRUPT, run(bad).error);
    }

    // Covers: SG-R-CDC-02
    @Test
    void exactInputAccountingAtTheMemberBoundary() {
        byte[] g = gzip(text(3000, 10));
        for (int chunk : CHUNKS) {
            Result r = run(concat(g, new byte[] {9, 9}), 4096, chunk);
            assertEquals(Kind.CORRUPT, r.error);
            // the body and trailer are consumed; the garbage is looked at as a header, 1 byte taken
            assertTrue(r.consumed >= g.length && r.consumed <= g.length + 2, "consumed " + r.consumed);
            Result ok = run(g, 4096, chunk);
            assertEquals(g.length, ok.consumed);
        }
    }

    // Covers: SG-R-CDC-02
    @Test
    void mutatedHeaderBytesExhaustive() {
        byte[] data = text(200, 11);
        byte[] extra = concat(new byte[] {2, 0, 5, 6}, new byte[] {'n', 0}, new byte[] {'c', 0});
        byte[] member = withHcrc(4 | 8 | 16, extra, data, 0);
        int headerLen = 10 + extra.length + 2;
        byte[] baseline = run(member).output;
        assertArrayEquals(data, baseline);
        for (int pos = 0; pos < headerLen; pos++) {
            for (int v = 0; v < 256; v++) {
                if ((member[pos] & 0xff) == v) {
                    continue;
                }
                byte[] m = member.clone();
                m[pos] = (byte) v;
                Result r = run(m, 64, 5);
                // any change to a covered header byte is refused, except fields outside the HCRC
                // coverage: none (the HCRC covers every header byte before itself) and the HCRC bytes
                // themselves, whose change must also be refused. The one allowed exception is a
                // change that still yields a consistent header, which would need a CRC collision.
                if (r.error == null) {
                    assertArrayEquals(data, r.output);
                    // a changed byte can stay consistent only if all checks passed on purpose
                    throw new AssertionError("accepted a mutated header at " + pos + " value " + v);
                }
                assertEquals(Kind.CORRUPT, r.error, "pos " + pos + " v " + v);
                // (a mutation can turn header bytes into deflate data that yields a few bytes before failing)
            }
        }
    }

    // Covers: SG-R-CDC-02, SG-I-09
    @Test
    void corruptBodyDeliversTheSamePrefixForEveryReadSizeAndChunking() {
        byte[] g = gzip(text(3000, 12));
        for (int pos = 10; pos < g.length - 8; pos += 7) {
            byte[] bad = g.clone();
            bad[pos] ^= 0x55;
            Result ref = run(bad, 1, 1);
            for (int read : new int[] {2, 3, 5, 4096, 70000}) {
                for (int chunk : new int[] {1, 5, 4096}) {
                    Result r = run(bad, read, chunk);
                    assertEquals(ref.error, r.error, pos + " " + read + " " + chunk);
                    assertArrayEquals(ref.output, r.output, pos + " " + read + " " + chunk);
                }
            }
        }
    }
}
