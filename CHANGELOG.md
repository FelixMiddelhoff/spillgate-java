# Changelog

Versions below 1.0 may still change the API.

## Unreleased (intended as 0.1.0)

First version. The Java implementation of the spillgate specification (Java 17 or newer).

- `Limits` with a required `maxOutput`, ratio, layer and input limits; `Kind` with `specName()` and `httpStatus()`; typed, sticky `SpillgateException` (an `IOException`) with `kind()`, `layer()` and `limit()`.
- `Decoder` (immutable, thread safe) with `reader`, `reader` with a `CancelToken` and `decodeAll`; `SpillgateInputStream` (`available()` is 0, reads of at most 64 KiB, `close()` releases the native memory of the `Inflater`); `Codec`, `CodecReader` and `CodecInput` for extension codecs, run behind the same limiter, with failure containment.
- `spillgate` (core): gzip, deflate and identity on the JDK `Inflater` behind own framing (exact input accounting, bounded steps, no dependencies).
- `spillgate-brotli`: a vendored copy of Google's pure Java decoder behind own framing (`BrotliCodec`, `BrotliOptions`); window read from the first byte and capped before allocation, large-window streams refused as `MEMORY_LIMIT`.
- `spillgate-zstd`: an own decoder (`ZstdCodec`, `ZstdOptions`); header, window and dictionary checks before allocation, 128 KiB block steps, XXH64 checksum, a window that grows with the output.
- JPMS modules `io.github.felixmiddelhoff.spillgate`, `.zstd` and `.brotli`.
- Passes the full conformance corpus of the reference implementation (353 vectors) with brotli and zstd registered and no vector skipped.
- Integration guide in `docs/how-to.md` (snippets compiled and run by the `howto` test module), memory bounds in `docs/memory.md`.
- CI on Linux, macOS and Windows with JDK 17 and 21, CodeQL, Scorecard, nightly fuzz rounds, and a release workflow for Maven Central (Central Portal, signed artifacts, sources and javadoc jars).
