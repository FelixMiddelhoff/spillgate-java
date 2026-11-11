# Vendored brotli decoder

The decoder sources in `src/main/java/io/github/felixmiddelhoff/spillgate/brotli/` (all files except
`BrotliCodec`, `BrotliOptions`, `module-info`, `BrotliReader`, `InputFailure`) come from
google/brotli, `java/org/brotli/dec`, commit `4363f2d74b8af198f78b08e10cd0b3ead48af590`
(2017-05-07; byte-identical to the sources jar of `org.brotli:dec:0.1.2`). MIT license, see
`LICENSE-brotli`. Changes: package renamed; `BitReader` reads from a `CodecInput` (exact accounting,
bounded lookahead, no stale bytes after the end); `State.setInput` takes a `CodecInput`;
`Decode` end-of-stream check moved to the codec; `BrotliInputStream` not vendored.
`org.brotli:dec:0.1.2` stays as a test-scope reference decoder.

## Changes after the first import (spillgate review fixes)

- `HuffmanScratch` (new, ours): the temporary arrays of the Huffman code reader, once per stream.
- `Huffman.buildHuffmanTable` takes a `HuffmanScratch` (no `sorted`, `count`, `offset` allocation per tree).
- `HuffmanTreeGroup.init` reuses and only grows `codes` and `trees` (new field `n` is the tree count);
  `Decode.readMetablockInfo` no longer nulls the groups.
- `Decode.readHuffmanCode` uses the scratch arrays instead of allocating `codeLengths`, `symbols`,
  `codeLengthCodeLengths`; a one-symbol simple code fills the root table directly;
  `decodeContextMap` and `readHuffmanCodeLengths` use scratch tables; `inverseMoveToFrontTransform`
  takes the `mtf` array.
- `BitReader` owns the `HuffmanScratch`.
- `Decode.maybeReallocateRingBuffer`: the ring allocation is wrapped, an `OutOfMemoryError` becomes an
  `InputFailure` carrying `SpillgateException(MEMORY_LIMIT)` (state released).
- `BrotliReader` (ours): a failure of `Decode.decompress` after bytes were written into the caller's
  buffer returns those bytes first and throws on the next call.
