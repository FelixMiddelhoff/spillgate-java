# Design of spillgate-java

This is the contract between the parts of the port. The specification (`spec/SPEC.md`) and the
conformance corpus (`conformance/`) are normative. The closest models are the Rust port
(FelixMiddelhoff/spillgate-rust, local `D:\Claude\spillgate-rust`: `src/*.rs`, `src/zstd/*.rs`, tests
in `tests/` and `src/*_tests.rs`) and the TypeScript port (`D:\Claude\spillgate-js\packages\*`). Port
the **semantics and the tests**, not the language idioms. Both ports went through an independent
security review; their bug lists are the first things to regression-test here (see "Known traps").

## Toolchain

JDK 17 (`C:\Projekte\jdk-17.0.12+7`) and Maven 3.9.9 (`D:\Claude\tools\maven\apache-maven-3.9.9`).
In a shell: `export JAVA_HOME="C:\\Projekte\\jdk-17.0.12+7"; export PATH="/c/Projekte/jdk-17.0.12+7/bin:/d/Claude/tools/maven/apache-maven-3.9.9/bin:$PATH"`.
Java 17 baseline (`maven.compiler.release` 17), `-Xlint:all -Werror`. Tests are JUnit 5.
Layout: Maven multi-module; artifacts `spillgate` (core), `spillgate-zstd`, `spillgate-brotli`,
plus the unpublished `spillgate-test-support` and `spillgate-conformance`. Package
`io.github.felixmiddelhoff.spillgate` (+ `.zstd`, `.brotli`, `.testsupport`). JPMS: each
published module has a `module-info.java` (`io.github.felixmiddelhoff.spillgate`, `...zstd`,
`...brotli`); the core exports only the public package.

## Principles

- Core has **no dependencies** (JDK only). `spillgate-brotli` depends on `org.brotli:dec` (Google's
  pure Java decoder, version pinned exactly). `spillgate-zstd` is an own decoder, no dependency.
- Gzip and deflate use `java.util.zip.Inflater` (the JDK's zlib) behind own framing, as the Python
  port does with `zlib.decompressobj`: bounded output per call, exact input accounting through
  `Inflater.getRemaining()`. Everything around it (gzip header and trailer, zlib detection,
  CRC-32/Adler-32 policy, trailing data) is own code, because the spec is stricter than the JDK.
  Never use `GZIPInputStream`, `InflaterInputStream`, `readAllBytes` on unbounded input, or
  `available()`.
- Exact input accounting: codecs read through `CodecInput` and `consume` only what they used.
- No allocation from stream values beyond decoder windows within their caps (SG-R-MEM-01);
  native memory (the `Inflater` is native) is released in `close()` and on every terminal condition
  (call `end()`), and a `Cleaner` is NOT relied upon.
- Arithmetic on sizes from the stream uses `long` and `Math.*Exact`/explicit checks; array indices
  are validated; no unchecked narrowing casts of untrusted values.
- Every part has tests that name the rules they cover: a comment `// Covers: SG-R-XXX-nn` or the
  test name; the spectrace test scans `core/src/test`, `zstd/src/test`, `brotli/src/test`,
  `conformance/src/test` for `SG-R-`/`SG-I-` IDs.

## Public API (core)

```java
public final class Limits { static Limits maxOutput(long); withMaxLayers(int) withMaxRatio(long) withRatioGrace(long) withMaxInput(long) withoutRatioLimit() }   // done
public enum Kind { ... }                       // done: specName(), httpStatus()
public final class SpillgateException extends IOException { kind() layer() limit() repeat() static quote(byte[]) }  // done
public interface Codec / CodecReader / CodecInput   // done (the extension contract)

public final class Decoder {                   // immutable, thread safe: share one per process
    static Decoder of(Limits limits) throws SpillgateException;            // = builder(limits).build()
    static DecoderBuilder builder(Limits limits);
    SpillgateInputStream reader(InputStream source, String... contentEncoding) throws SpillgateException;
    SpillgateInputStream reader(InputStream source, CancelToken token, String... contentEncoding) throws SpillgateException;
    byte[] decodeAll(InputStream source, String... contentEncoding) throws IOException;  // whole body or an exception, never partial data
    static byte[] decodeAll(InputStream source, Limits limits, String... contentEncoding) throws IOException;
}
public final class DecoderBuilder {
    DecoderBuilder strictZlib(boolean on);                                  // SG-R-CDC-03
    DecoderBuilder codec(String name, Codec codec);                         // SG-R-CDC-09; errors (INVALID_OPTION) surface in build()
    Decoder build() throws SpillgateException;                              // INVALID_LIMITS, INVALID_OPTION
}
public final class SpillgateInputStream extends InputStream {   // read(), read(byte[],int,int), close() (idempotent), available() == 0
    // errors: a SpillgateException (an IOException) from read; sticky; a clean end (-1) is sticky
}
public final class CancelToken { void cancel(); boolean isCanceled(); }   // SG-R-API-04
```

- `contentEncoding` is a varargs of header field values as received (`"gzip, br", "zstd"`).
- Reads after `close()` throw `IOException("stream closed")` (not a SpillgateException), as
  `InputStream` convention. `available()` returns 0 (documented: the JDK's `available()` lies).
- `read(byte[], off, len)` returns at most 64 KiB per call (documented); `skip`, `readAllBytes`,
  `readNBytes`, `transferTo` work through `read` and are therefore bounded by the limiter.
- Source `IOException` (not SpillgateException) from the caller's stream is `SOURCE_ERROR` with the
  original as cause; `SpillgateException` thrown by a lower layer passes through unchanged.
  `InterruptedIOException` is NOT retried; a source returning 0 from `read(byte[],..)` with
  `len > 0` 100 times in a row is `INTERNAL` (SG-R-RDR-03); a source returning more than requested
  or a negative count other than -1 is `INTERNAL` (SG-R-RDR-04).
- `Thread.interrupt()` is not cancellation; use `CancelToken` (checked on every `read` and every
  fetch from the source).

## Parts and file ownership

| Part | Files | Interface it provides |
| --- | --- | --- |
| core | `Decoder.java DecoderBuilder.java SpillgateInputStream.java CancelToken.java Header.java InputMeter.java LayerGuard.java` (all package-private except the public ones), core tests `core/src/test/java/.../Core*Test.java`, `module-info.java`, README | the public API above |
| deflate | `GzipCodec.java DeflateCodec.java Crc32.java` (+ tests `Gzip*Test.java`, `Deflate*Test.java`) | `new GzipCodec()`, `new DeflateCodec(boolean strictZlib)` (both `Codec`, package-private classes registered by `DecoderBuilder`) |
| zstd | `zstd/src/main/java/.../zstd/*.java` | `ZstdCodec.create(ZstdOptions)`, `ZstdOptions` (`maxWindowBytes`, default 8 MiB, 1 KiB..1 GiB, invalid -> `INVALID_OPTION`) |
| support | `test-support/**`, `conformance/src/test/**` | corpus generators (no compression library), the conformance runner, spectrace test |
| brotli | `brotli/src/main/java/.../brotli/*.java` | `BrotliCodec.create(BrotliOptions)` (`maxWindowBits` 10..24, default 24) |

### core

- `Header.parse(String[])`: SPEC section 4 exactly; evaluation order is normative (length, element
  grammar left to right, element count, then support check and layer count). Never `String.split`
  with regex on attacker data without the 1024-byte cap applied first; work on the joined bytes.
  Case-insensitivity is ASCII only (`Locale.ROOT` is NOT enough: do not use `toLowerCase`, map
  A-Z manually; the Turkish-i trap).
- `InputMeter implements CodecInput`: wraps the raw `InputStream` for layer 1 and a lower layer's
  guarded stream for the others; counts consumed bytes exactly; fetches at most 4 KiB at a time;
  enforces `maxInput` with the one probe byte (SG-R-INP-01/02), never requesting more than
  `maxInput + 1` bytes in total. The probe (byte `maxInput + 1`) may be shown to the codec, always
  alone and never together with earlier bytes; only what the codec consumes counts, and
  `LayerGuard` checks `consumed > maxInput` at the end of every codec step (it overrides what the
  step returned). Codecs peek for trailing data (gzip, deflate, zstd) or give the probe back
  (`CodecInput.unreadProbe()`, brotli lookahead) so that trailing data is `CORRUPT`; identity
  (`Passthrough`) takes what it passes; converts source failures; never reads the source again after a
  terminal condition (SG-R-LAY-09).
- `LayerGuard`: per layer: output budget (SG-R-LAY-01..03, probe byte for the exact boundary, a bad
  trailer after exactly `maxOutput` bytes is `CORRUPT`), ratio once per codec step
  (`EffectiveLimits.ratioExceeded`), `MAX_STEP` 64 KiB per codec call, sticky errors via
  `SpillgateException.repeat()`, codec failures contained: a `SpillgateException` passes; a
  `RuntimeException`, `StackOverflowError` or `LinkageError` becomes `INTERNAL`; an `OutOfMemoryError`
  or other `VirtualMachineError` releases the layer's state and propagates (documented: the JVM is
  no longer in a state the library can vouch for). A codec returning more than `len` or a negative count other than -1 is
  `INTERNAL`.
- `SpillgateInputStream`: chain of guards, end-to-end ratio (SG-R-RAT-03), identity layers are
  pass-through that still go through the limiter (SG-R-CDC-10). `close()` releases every codec.

### deflate

`GzipCodec`/`DeflateCodec` per SG-R-CDC-02/03 and the Python port's `gzip.py`/`deflate.py` logic:
`Inflater(nowrap=true)` for the raw deflate body (gzip) and for raw fallback; for zlib framing either
parse the 2-byte header yourself and use nowrap + own Adler-32 (recommended, full control of FDICT,
detection and trailer) or `Inflater(false)`. Exact consumed bytes via `getRemaining()` after each
`inflate`; hand the unused remainder back (never consume it). Handle `needsInput`/`needsDictionary`/
`DataFormatException` -> `CORRUPT`. gzip: members concatenated, header checks (magic, CM=8, reserved
flags, FHCRC, FNAME/FCOMMENT <= 512 bytes incl. terminator, extra <= 65535), CRC-32 via
`java.util.zip.CRC32`, ISIZE mod 2^32, trailing garbage or zero padding after a member is corrupt,
truncation corrupt. Call `Inflater.end()` on every terminal condition and in `close()`.
The Rust port's review found: **code 16 after 17/18 repeat bug** (not applicable: zlib is correct),
**bytes before an error must not depend on read size** (applies: when `inflate` throws
`DataFormatException` after producing bytes in the same call, check what the Inflater wrote before
the exception; it does not report it, so use small steps or re-run: decide and test SG-R-RDR-01
with read sizes 1/3/4096 on corrupt streams: the delivered prefix must be identical).

### zstd

Port `zstd/src/main/java` from the Rust `src/zstd/*.rs` (the most reviewed version): frame header
validation and window cap before allocation, dictionaries refused, <= 128 KiB blocks, FSE/Huffman/
sequences, XXH64, deferred checksum/size errors, lazy window growth with the ring position kept
modulo the declared window, one scratch buffer per reader, Huffman weight-1 rule, a block without
sequences ends after the count byte, root-table Huffman/FSE decoding where the Rust port has it.
Java specifics: all arithmetic in `long` or masked ints (no sign extension bugs: `b & 0xff`),
`Integer.compareUnsigned` where needed, array bounds are checked by the JVM but must still be
validated up front to give `CORRUPT` rather than `ArrayIndexOutOfBoundsException` (which would be
`INTERNAL`). Do not allocate `new byte[n]` from a stream value except the window within its cap.

### support and conformance

`test-support` ports the Rust `crates/test-support` (generators from `spec/generators.md`, no
compression library; every manifest hash must match). `conformance/src/test/...` ports the Rust
`tests/conformance.rs` runner (`conformance/manifest.json` is read from the repo root; JSON via a
test-scope dependency, e.g. Gson) and `spectrace`: all 370 vectors with no skips when zstd and
brotli are on the classpath (they are, in the `conformance` module). Runner checks: base read
pattern plus the five alternatives, sticky, `maxInput` bytes taken, `delivered_at_most`,
`delivered_exactly`, layer, ratio prefix tolerance.

## Known traps from the other ports (regression-test each)

1. DEFLATE: code 16 after 17/18 (N/A with zlib, but test the stream `05c0250100000000a0ad04f9ff0004` -> `a`).
2. Bytes delivered before a `corrupt` error identical for all read sizes (stream `4b4c4c043e00` with `deflate`: `aaa` then corrupt).
3. A canceled stream must not look retryable (`InterruptedIOException` is retried by some callers: throw `SpillgateException` only).
4. zstd: block as large as the window, eager window allocation, per-block zero-fill, zero-sequence block with trailing bytes, Huffman weight-1 rule (`28b52ffd00003d000042c00081226300` -> corrupt).
5. brotli: free decoder state after an error, repeat the error on later reads, window cap checked from the first byte before allocation, hostile header can force about 3.3 MiB of tables on top of the window.
6. CPU per input byte for many tiny frames/blocks/members (zstd empty frames, gzip members, deflate dynamic blocks) must stay bounded; add timing tests with generous bounds.

## Decisions fixed here

- Limits are `long`; `maxOutput` up to 2^63-1.
- `Decoder` is thread safe; a stream is not shared across threads (SG-R-RDR-07).
- Built-in names `identity`, `gzip`, `deflate`, `x-gzip` are reserved for codec registration.
- Extension codec names: lower-case token per SG-R-HDR-04 (checked in `build()`).
- No `finalize()`, no `Cleaner`; resource release is `close()` plus release on terminal conditions.
