package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.SpillgateException;

/** Finite State Entropy tables (RFC 8878 section 4.1). Tables are immutable once built. */
final class Fse {
    private Fse() {}

    /** A decoding table: for a state, the symbol, the bits to read and the baseline of the next state. */
    static final class Table {
        final int accuracyLog;
        final int size;
        final byte[] symbol;
        final byte[] nbBits;
        final int[] baseline;

        Table(int accuracyLog) {
            this.accuracyLog = accuracyLog;
            this.size = 1 << accuracyLog;
            this.symbol = new byte[size];
            this.nbBits = new byte[size];
            this.baseline = new int[size];
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

    private static SpillgateException badSum() {
        return Err.corrupt("the probabilities of a table do not add up");
    }

    /**
     * {@code normalized[s]} is the probability of symbol s in units of 1/2**accuracyLog, -1 means "less
     * than one"; the first {@code count} entries are used.
     */
    static Table build(int[] normalized, int count, int accuracyLog) throws SpillgateException {
        if (accuracyLog < 0 || accuracyLog > 9) {
            throw Err.corrupt("a table asks for too large an accuracy");
        }
        if (count > 256 || count > normalized.length) {
            throw badSum();
        }
        Table t = new Table(accuracyLog);
        int size = t.size;
        int[] next = new int[count];
        // symbols with probability "less than one" take the highest states
        int high = size;
        for (int s = 0; s < count; s++) {
            int p = normalized[s];
            if (p == -1) {
                high--;
                if (high < 0) {
                    throw badSum();
                }
                t.symbol[high] = (byte) s;
                next[s] = 1;
            } else {
                if (p < -1 || p > size) {
                    throw badSum();
                }
                next[s] = Math.max(p, 0);
            }
        }
        // the others are spread over 0..high with a fixed stride
        int step = (size >> 1) + (size >> 3) + 3;
        int mask = size - 1;
        int position = 0;
        for (int s = 0; s < count; s++) {
            int p = Math.max(normalized[s], 0);
            for (int i = 0; i < p; i++) {
                t.symbol[position] = (byte) s;
                int guard = 0;
                while (true) {
                    position = (position + step) & mask;
                    if (position < high) {
                        break;
                    }
                    guard++;
                    if (guard > size) {
                        throw badSum();
                    }
                }
            }
        }
        if (position != 0) {
            throw badSum();
        }
        for (int state = 0; state < size; state++) {
            int symbol = t.symbol[state] & 0xff;
            if (symbol >= count) {
                throw badSum();
            }
            int nextState = next[symbol]++;
            if (nextState <= 0) {
                throw badSum();
            }
            int top = 31 - Integer.numberOfLeadingZeros(nextState);
            int bits = accuracyLog - top;
            if (bits < 0) {
                throw badSum();
            }
            t.nbBits[state] = (byte) bits;
            int shifted = nextState << bits;
            if (shifted < size) {
                throw badSum();
            }
            t.baseline[state] = shifted - size;
        }
        return t;
    }

    /** Read a table description from {@code a[from, to)} (RFC 8878 4.1.1). */
    static Parsed read(byte[] a, int from, int to, int maxAccuracyLog, int maxSymbol) throws SpillgateException {
        Bits.Forward bits = new Bits.Forward(a, from, to);
        int accuracyLog = bits.read(4) + 5;
        if (accuracyLog > maxAccuracyLog) {
            throw Err.corrupt("a table asks for too large an accuracy");
        }
        int total = 1 << accuracyLog;
        int[] normalized = new int[maxSymbol + 1];
        int count = 0;
        int sum = 0;
        while (sum < total) {
            int maxValue = total - sum + 1; // the number of values still possible
            int bitCount = 32 - Integer.numberOfLeadingZeros(maxValue);
            int raw = bits.read(bitCount);
            int lowThreshold = (1 << bitCount) - 1 - maxValue;
            int mask = (1 << (bitCount - 1)) - 1;
            int small = raw & mask;
            int value;
            if (small < lowThreshold) {
                bits.unread(1);
                value = small;
            } else if (raw > mask) {
                value = raw - lowThreshold;
            } else {
                value = raw;
            }
            int probability = value - 1;
            sum += probability < 0 ? 1 : probability;
            if (count >= normalized.length) {
                throw Err.corrupt("a table has too many symbols");
            }
            normalized[count++] = probability;
            if (probability == 0) {
                // a run of unused symbols
                int repeat;
                do {
                    repeat = bits.read(2);
                    if (count + repeat > normalized.length) {
                        throw Err.corrupt("a table has too many symbols");
                    }
                    count += repeat; // the entries are already 0
                } while (repeat == 3);
            }
            if (bits.overrun()) {
                throw Err.corrupt("a table description ends too early");
            }
        }
        if (sum != total) {
            throw badSum();
        }
        return new Parsed(build(normalized, count, accuracyLog), bits.bytesUsed());
    }

    /** A table that always yields {@code symbol} (the RLE mode of the sequence section). */
    static Table rle(int symbol) {
        Table t = new Table(0);
        t.symbol[0] = (byte) symbol;
        return t;
    }
}
