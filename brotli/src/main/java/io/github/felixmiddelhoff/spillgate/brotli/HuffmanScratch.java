package io.github.felixmiddelhoff.spillgate.brotli;

/**
 * Temporary arrays of the Huffman code reader, allocated once per stream (spillgate addition; the
 * upstream decoder allocated them again for every tree). All sizes are bounded by the format: the
 * largest alphabet is the 704 insert-and-copy codes.
 */
final class HuffmanScratch {
  /** The largest alphabet of any prefix code in the format. */
  static final int MAX_ALPHABET = 704;

  final int[] codeLengths = new int[MAX_ALPHABET];
  final int[] sorted = new int[MAX_ALPHABET];
  final int[] count = new int[16];
  final int[] offset = new int[16];
  final int[] symbols = new int[4];
  final int[] codeLengthCodeLengths = new int[18];
  final int[] codeLengthTable = new int[32];
  final int[] contextMapTable = new int[Huffman.HUFFMAN_MAX_TABLE_SIZE];
  final int[] mtf = new int[256];
}
