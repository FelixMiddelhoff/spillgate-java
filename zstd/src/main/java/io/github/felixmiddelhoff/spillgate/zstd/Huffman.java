package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.SpillgateException;

/** Huffman-coded literals (RFC 8878 section 4.2). */
final class Huffman {
    private Huffman() {}

    private static final int MAX_HUFFMAN_BITS = 11;

    /** A decoding table: {@code entries[bits & mask]} is {@code symbol << 4 | length}. */
    static final class Table {
        final int maxBits;
        final int[] entries;

        Table(int maxBits, int[] entries) {
            this.maxBits = maxBits;
            this.entries = entries;
        }
    }

    /** A table and the number of bytes its description used. */
    static final class Parsed {
        final Table table;
        final int used;

        Parsed(Table table, int used) {
            this.table = table;
            this.used = used;
        }
    }

    private static SpillgateException err(String message) {
        return Err.corrupt(message);
    }

    /** Read a Huffman tree description (RFC 8878 4.2.1) from the start of {@code a[from, to)}. */
    static Parsed read(byte[] a, int from, int to) throws SpillgateException {
        if (from < 0 || to > a.length || to <= from) {
            throw err("an empty Huffman tree description");
        }
        int header = a[from] & 0xff;
        int rest = from + 1;
        byte[] weights = new byte[257];
        int count = 0;
        int used;
        if (header >= 128) {
            // direct representation: header - 127 weights of 4 bits each, two per byte (high nibble first)
            int n = header - 127;
            int payload = (n + 1) / 2;
            used = 1 + payload;
            if (rest + payload > to) {
                throw err("a Huffman tree description ends too early");
            }
            for (int i = 0; i < n; i++) {
                int b = a[rest + i / 2] & 0xff;
                weights[count++] = (byte) ((i % 2 == 0) ? (b >> 4) : (b & 15));
            }
        } else {
            // FSE-compressed weights, header bytes
            used = 1 + header;
            if (header == 0 || rest + header > to) {
                throw err("a Huffman tree description ends too early");
            }
            count = decodeWeights(a, rest, rest + header, weights);
        }
        if (count > 255) {
            throw err("too many Huffman weights");
        }

        // the last weight is implied: the sum of 2**(w-1) must reach a power of two
        long total = 0;
        for (int i = 0; i < count; i++) {
            int w = weights[i];
            if (w > 0) {
                total += 1L << (w - 1);
            }
        }
        if (total == 0) {
            throw err("a Huffman tree has no codes");
        }
        int maxBits = 64 - Long.numberOfLeadingZeros(total); // smallest b with 2**b > total
        long power = 1L << maxBits;
        long remainder = power - total;
        if (Long.bitCount(remainder) != 1) {
            throw err("Huffman weights do not complete a tree");
        }
        weights[count++] = (byte) (Long.numberOfTrailingZeros(remainder) + 1); // log2(rest) + 1
        // libzstd refuses a tree with fewer than two weight-1 symbols (the implied one included) or an
        // odd number of them; accepting it would be a parser differential.
        int ones = 0;
        for (int i = 0; i < count; i++) {
            if (weights[i] == 1) {
                ones++;
            }
        }
        if (ones < 2 || ones % 2 == 1) {
            throw err("Huffman weights do not form a valid tree");
        }
        if (maxBits > MAX_HUFFMAN_BITS) {
            throw err("a Huffman code is too long");
        }
        return new Parsed(build(weights, count, maxBits), used);
    }

    /** FSE-compressed weights: two interleaved states over a backward bit stream. Returns the count. */
    private static int decodeWeights(byte[] a, int from, int to, byte[] weights) throws SpillgateException {
        Fse.Parsed parsed = Fse.read(a, from, to, 6, 15);
        Fse.Table table = parsed.table;
        int start = from + parsed.used;
        if (start > to) {
            throw err("a Huffman tree description ends too early");
        }
        Bits.Backward bits = new Bits.Backward(a, start, to);
        long state1 = bits.read(table.accuracyLog);
        long state2 = bits.read(table.accuracyLog);
        int n = 0;
        while (true) {
            if (state1 >= table.size) {
                throw err("a table state is out of range");
            }
            int s1 = (int) state1;
            n = push(weights, n, table.symbol[s1]);
            state1 = table.baseline[s1] + bits.read(table.nbBits[s1] & 0xff);
            if (bits.remaining < 0) {
                if (state2 >= table.size) {
                    throw err("a table state is out of range");
                }
                return push(weights, n, table.symbol[(int) state2]);
            }
            if (state2 >= table.size) {
                throw err("a table state is out of range");
            }
            int s2 = (int) state2;
            n = push(weights, n, table.symbol[s2]);
            state2 = table.baseline[s2] + bits.read(table.nbBits[s2] & 0xff);
            if (bits.remaining < 0) {
                if (state1 >= table.size) {
                    throw err("a table state is out of range");
                }
                return push(weights, n, table.symbol[(int) state1]);
            }
            if (n > 255) {
                throw err("too many Huffman weights");
            }
        }
    }

    private static int push(byte[] weights, int n, byte weight) throws SpillgateException {
        if (n >= 256) {
            throw err("too many Huffman weights");
        }
        weights[n] = weight;
        return n + 1;
    }

    private static Table build(byte[] weights, int count, int maxBits) throws SpillgateException {
        int size = 1 << maxBits;
        int[] entries = new int[size];
        // canonical codes, shortest codes take the highest bit patterns (symbols sorted by weight)
        int next = 0;
        for (int w = 1; w <= maxBits; w++) {
            for (int symbol = 0; symbol < count; symbol++) {
                if (weights[symbol] != w) {
                    continue;
                }
                int length = maxBits + 1 - w;
                int span = 1 << (maxBits - length);
                int value = (symbol << 4) | length;
                int end = next + span;
                if (end > size) {
                    throw err("a Huffman tree is not complete");
                }
                java.util.Arrays.fill(entries, next, end, value);
                next = end;
            }
        }
        if (next != size) {
            throw err("a Huffman tree is not complete");
        }
        return new Table(maxBits, entries);
    }

    /** Decode {@code count} literals from the backward-coded stream {@code a[from, to)} into {@code out}. */
    static void decodeStream(Table table, byte[] a, int from, int to, byte[] out, int outOff, int count)
            throws SpillgateException {
        Bits.Backward bits = new Bits.Backward(a, from, to);
        int maxBits = table.maxBits;
        int[] entries = table.entries;
        for (int i = 0; i < count; i++) {
            int index = (int) bits.peek(maxBits);
            int entry = entries[index];
            bits.remaining -= entry & 15;
            out[outOff + i] = (byte) (entry >> 4);
        }
        if (bits.remaining != 0) {
            throw err("a Huffman stream was not consumed exactly");
        }
    }
}
