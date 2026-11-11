/* Copyright 2015 Google Inc. All Rights Reserved.
   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT

   Modified for spillgate: reads from a CodecInput, small bounded lookahead, exact end accounting. */

package io.github.felixmiddelhoff.spillgate.brotli;

import io.github.felixmiddelhoff.spillgate.CodecInput;
import java.io.IOException;

/** Bit reading helpers. */
final class BitReader {

  private static final int CAPACITY = 1024;
  private static final int SLACK = 16;
  private static final int INT_BUFFER_SIZE = CAPACITY + SLACK;
  private static final int BYTE_BUFFER_SIZE = INT_BUFFER_SIZE << 2;
  /** At least this many bytes are buffered ahead (36 = 9 ints, what the decoder needs). */
  private static final int MIN_BYTES = 36;
  /** Lookahead taken from one window at most. */
  private static final int WANT_BYTES = 256;

  private final byte[] byteBuffer = new byte[BYTE_BUFFER_SIZE];
  private final int[] intBuffer = new int[INT_BUFFER_SIZE];
  private final IntReader intReader = new IntReader();
  /** Temporary arrays of the Huffman code reader (spillgate: once per stream). */
  final HuffmanScratch scratch = new HuffmanScratch();

  private CodecInput input;
  private boolean endOfStreamReached;
  /** Real bytes in byteBuffer. */
  private int validBytes;
  /** Number of valid ints in intBuffer. */
  private int intLimit;

  long accumulator;
  int bitOffset;
  private int intOffset;
  private int tailBytes = 0;

  static void readMoreInput(BitReader br) {
    if (br.endOfStreamReached) {
      if (intAvailable(br) >= -2) {
        return;
      }
      throw new BrotliRuntimeException("No more input");
    }
    if (br.intLimit - br.intOffset >= 9) {
      return;
    }
    br.refill();
  }

  private void refill() {
    int readOffset = intOffset << 2;
    int keep = validBytes - readOffset;
    System.arraycopy(byteBuffer, readOffset, byteBuffer, 0, keep);
    validBytes = keep;
    intOffset = 0;
    try {
      while (validBytes < MIN_BYTES || (validBytes & 3) != 0) {
        int n = input.fill();
        if (n <= 0) {
          endOfStreamReached = true;
          tailBytes = validBytes;
          java.util.Arrays.fill(byteBuffer, validBytes, byteBuffer.length, (byte) 0);
          IntReader.convert(intReader, INT_BUFFER_SIZE);
          intLimit = (tailBytes + 3) >> 2;
          return;
        }
        int take = Math.min(n, WANT_BYTES - validBytes);
        if (take <= 0) {
          break;
        }
        System.arraycopy(input.buffer(), input.position(), byteBuffer, validBytes, take);
        input.consume(take);
        validBytes += take;
      }
    } catch (IOException e) {
      throw new InputFailure(e);
    }
    intLimit = validBytes >> 2;
    IntReader.convert(intReader, intLimit);
  }

  /** The number of real bytes after the current position, or -1 if the position is past the end. */
  static int unusedBytes(BitReader br) {
    int byteOffset = (br.intOffset << 2) + ((br.bitOffset + 7) >> 3) - 8;
    int end = br.endOfStreamReached ? br.tailBytes : br.validBytes;
    return byteOffset > end ? -1 : end - byteOffset;
  }

  boolean atEndOfInput() {
    return endOfStreamReached;
  }

  static void checkHealth(BitReader br, boolean endOfStream) {
    if (!br.endOfStreamReached) {
      return;
    }
    int byteOffset = (br.intOffset << 2) + ((br.bitOffset + 7) >> 3) - 8;
    if (byteOffset > br.tailBytes) {
      throw new BrotliRuntimeException("Read after end");
    }
    if (endOfStream && (byteOffset != br.tailBytes)) {
      throw new BrotliRuntimeException("Unused bytes after end");
    }
  }

  static void fillBitWindow(BitReader br) {
    if (br.bitOffset >= 32) {
      br.accumulator = ((long) br.intBuffer[br.intOffset++] << 32) | (br.accumulator >>> 32);
      br.bitOffset -= 32;
    }
  }

  static int readBits(BitReader br, int n) {
    fillBitWindow(br);
    int val = (int) (br.accumulator >>> br.bitOffset) & ((1 << n) - 1);
    br.bitOffset += n;
    return val;
  }

  static void init(BitReader br, CodecInput input) {
    if (br.input != null) {
      throw new IllegalStateException("Bit reader already has associated input");
    }
    IntReader.init(br.intReader, br.byteBuffer, br.intBuffer);
    br.input = input;
    br.accumulator = 0;
    br.bitOffset = 64;
    br.intOffset = 0;
    br.intLimit = 0;
    br.validBytes = 0;
    br.endOfStreamReached = false;
    prepare(br);
  }

  private static void prepare(BitReader br) {
    readMoreInput(br);
    checkHealth(br, false);
    fillBitWindow(br);
    fillBitWindow(br);
  }

  static void reload(BitReader br) {
    if (br.bitOffset == 64) {
      prepare(br);
    }
  }

  static void jumpToByteBoundary(BitReader br) {
    int padding = (64 - br.bitOffset) & 7;
    if (padding != 0) {
      int paddingBits = BitReader.readBits(br, padding);
      if (paddingBits != 0) {
        throw new BrotliRuntimeException("Corrupted padding bits");
      }
    }
  }

  static int intAvailable(BitReader br) {
    int limit = br.intLimit;
    return limit - br.intOffset;
  }

  static void copyBytes(BitReader br, byte[] data, int offset, int length) {
    if ((br.bitOffset & 7) != 0) {
      throw new BrotliRuntimeException("Unaligned copyBytes");
    }
    // Drain accumulator.
    while ((br.bitOffset != 64) && (length != 0)) {
      data[offset++] = (byte) (br.accumulator >>> br.bitOffset);
      br.bitOffset += 8;
      length--;
    }
    if (length == 0) {
      return;
    }
    int copyInts = Math.min(intAvailable(br), length >> 2);
    if (copyInts > 0) {
      int readOffset = br.intOffset << 2;
      System.arraycopy(br.byteBuffer, readOffset, data, offset, copyInts << 2);
      offset += copyInts << 2;
      length -= copyInts << 2;
      br.intOffset += copyInts;
    }
    if (length == 0) {
      return;
    }
    if (intAvailable(br) > 0) {
      // length = 1..3
      fillBitWindow(br);
      while (length != 0) {
        data[offset++] = (byte) (br.accumulator >>> br.bitOffset);
        br.bitOffset += 8;
        length--;
      }
      checkHealth(br, false);
      return;
    }
    if (br.endOfStreamReached) {
      throw new BrotliRuntimeException("Unexpected end of input");
    }
    // The lookahead is used up: copy straight from the input, consuming exactly what is used.
    try {
      while (length > 0) {
        int n = br.input.fill();
        if (n <= 0) {
          throw new BrotliRuntimeException("Unexpected end of input");
        }
        int k = Math.min(n, length);
        System.arraycopy(br.input.buffer(), br.input.position(), data, offset, k);
        br.input.consume(k);
        offset += k;
        length -= k;
      }
    } catch (IOException e) {
      throw new InputFailure(e);
    }
  }
}
