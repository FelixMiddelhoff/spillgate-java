# Vendored brotli decoder

The decoder sources in `src/main/java/io/github/felixmiddelhoff/spillgate/brotli/` (all files except
`BrotliCodec`, `BrotliOptions`, `module-info`, `BrotliReader`, `InputFailure`) come from
google/brotli, `java/org/brotli/dec`, commit `4363f2d74b8af198f78b08e10cd0b3ead48af590`
(2017-05-07; byte-identical to the sources jar of `org.brotli:dec:0.1.2`). MIT license, see
`LICENSE-brotli`. Changes: package renamed; `BitReader` reads from a `CodecInput` (exact accounting,
bounded lookahead, no stale bytes after the end); `State.setInput` takes a `CodecInput`;
`Decode` end-of-stream check moved to the codec; `BrotliInputStream` not vendored.
`org.brotli:dec:0.1.2` stays as a test-scope reference decoder.
