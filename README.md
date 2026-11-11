# spillgate-java

Decode untrusted compressed data under mandatory limits. A decompression bomb ends in a typed
`SpillgateException` instead of exhausting memory. Java port of
[spillgate](https://github.com/FelixMiddelhoff/spillgate): it implements the shared specification
(`spec/SPEC.md`) and passes the shared conformance corpus. Java 17 or newer; the core has no
dependencies.

```java
import io.github.felixmiddelhoff.spillgate.*;
import java.io.IOException;
import java.io.InputStream;

// Share one Decoder per process: it is immutable and thread safe.
// maxOutput is required; there is no unlimited mode.
static final Decoder DECODER = buildDecoder();

static Decoder buildDecoder() {
    try {
        return Decoder.of(Limits.maxOutput(8L * 1024 * 1024));
    } catch (SpillgateException e) {
        throw new IllegalStateException(e);
    }
}

// whole body: all of it, or an exception and no data
static byte[] whole(InputStream body, String contentEncoding) throws IOException {
    return DECODER.decodeAll(body, contentEncoding);
}

// streaming: header problems fail in reader(...), before the source is touched
static void stream(InputStream body, String contentEncoding) throws IOException {
    try (SpillgateInputStream in = DECODER.reader(body, contentEncoding)) {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            // use the bytes; discard everything if a later read fails
        }
    } catch (SpillgateException e) {
        if (e.kind() == Kind.OUTPUT_LIMIT) { /* answer 413 */ }
        int status = e.kind().httpStatus();
        throw e;
    }
}
```

`contentEncoding` is a varargs of header field values as received (`"gzip, br", "zstd"`).

## Modules

| Artifact | Adds | Dependency |
| --- | --- | --- |
| `spillgate` (this core) | `identity`, `gzip`, `deflate` (zlib and raw) | none (JDK `Inflater` behind own framing) |
| `spillgate-brotli` | brotli, `BrotliCodec` | `org.brotli:dec` (pure Java, pinned exactly) |
| `spillgate-zstd` | zstd, `ZstdCodec` (own decoder, no dictionaries) | none |

Extension codecs are registered explicitly on a decoder; there is no global registry:

```java
Decoder decoder = Decoder.builder(Limits.maxOutput(8L << 20).withMaxInput(1L << 20))
        .strictZlib(false)
        .codec("zstd", ZstdCodec.create(zstdOptions))
        .build();
```

## Limits

| Setter | What it bounds | Default |
| --- | --- | --- |
| `Limits.maxOutput(n)` | bytes each layer may produce, `1 .. 2^63-1` (required) | none |
| `withMaxLayers(n)` | non-identity encodings in a chain, `1 .. 16` | 5 |
| `withMaxRatio(n)` | output bytes per consumed input byte, `1 .. 2^31-1` | 1000 |
| `withRatioGrace(n)` | output size below which the ratio is not checked | 64 KiB |
| `withMaxInput(n)` | compressed bytes taken from the source (never more than `n + 1` are requested) | none |
| `withoutRatioLimit()` | the one named opt-out of the ratio check | off |

`Limits` is immutable and validated completely when the decoder is built (`INVALID_LIMITS`); the
decoder keeps its own copy. No numeric value disables a limit.

## Behavior to rely on

- **Typed, sticky errors.** Every refusal is a `SpillgateException` (an `IOException`); `kind()` is the
  outcome class, `layer()` the failing layer (1 reads from the source), `limit()` the limit exceeded.
  After an error every read fails with the same kind; a clean end (`-1`) is sticky too. Streaming
  reads can deliver bytes before a later error: treat any error as failure of the whole body.
- **No partial success.** A limit hit is never a clean end. Exactly `maxOutput` bytes followed by a
  valid end is success; the `maxOutput + 1`-th byte is `OUTPUT_LIMIT` and is never delivered.
- **Chains.** Every layer has its own output budget, ratio meter and input meter; layer 1 is the
  last listed token. `identity` is bounded too. Header problems (`MALFORMED_ENCODING`,
  `UNSUPPORTED_ENCODING`, `LAYER_LIMIT`) fail at `reader(...)`, before any read.
- **Messages** never carry more than 32 bytes of attacker data, quoted and escaped.
- **Read sizes do not matter.** The delivered bytes and the outcome class are the same for every read
  size (except the length of the prefix before a `RATIO_LIMIT`). One `read(byte[], int, int)` returns
  at most 64 KiB.
- **Source failures.** An `IOException` from your `InputStream` is `SOURCE_ERROR` with the original as
  the cause (an `InterruptedIOException` is not retried); a `SpillgateException` from a lower layer
  or from another decoder's stream passes through. A source returning 0 bytes 100 times in a row,
  more bytes than requested, or a negative count other than -1 is `INTERNAL`.
- **Codec failures are contained.** A `RuntimeException`, `StackOverflowError`, `LinkageError`,
  contract violation (too many bytes, writing outside the range it was given, no progress) from a
  codec is `INTERNAL`. An `OutOfMemoryError` (or another `VirtualMachineError`) releases the layer and
  propagates: the JVM is no longer in a state the library can vouch for. The cause of an `INTERNAL`
  error is the codec's throwable; its message is never copied into ours.
- **`close()`** is idempotent and releases every codec (native memory included); it does **not**
  close your source. Reading after `close()` throws a plain `IOException("stream closed")`. A stream
  also releases its codecs at its clean end and at every error. Nothing relies on `finalize` or a
  `Cleaner`: close your streams. `available()` returns 0.
- **Cancellation.** `new CancelToken()` passed to `reader(source, token, ...)`; `token.cancel()` from
  any thread. Checked on every `read` and every fetch from the source (at least once per 4 KiB of
  compressed input); a canceled stream throws `SpillgateException(CANCELED)`, sticky, never
  `InterruptedIOException`. It is not a hard CPU bound: one codec step of at most 64 KiB output still
  runs to its end. `Thread.interrupt()` is not cancellation.
- **Threads.** A `Decoder` is thread safe; share one. A stream is not safe for concurrent use.

## Memory

Apart from codec windows within their caps, one stream holds at most 4 KiB (input buffer) plus a
scratch array of up to 64 KiB per layer: about 1.1 MiB for a 16-layer chain, far below the 4 MiB per
layer of SG-R-MEM-02. `decodeAll` grows its buffer with the bytes produced and never allocates from
`maxOutput` (so `maxOutput = Long.MAX_VALUE` is fine); a body that cannot fit one Java array fails
with `OUTPUT_LIMIT`. The core tests run with `-Xmx96m` and stream a 4 GiB bomb under a 100 MiB budget.

## Security model

- Every limit is checked before bytes are handed out; no allocation is sized from a value in the
  data (declared sizes, headers, stream lengths).
- `Content-Encoding` is parsed strictly (ASCII only, 1024 bytes, 16 elements, token grammar), a chain
  cannot multiply a bomb because each layer has its own budget.
- A codec never gets the caller's array: it writes into a private scratch buffer and the guard copies
  validated bytes out, so a codec that ignores its length cannot damage your memory.
- The ratio is judged once per codec step (at most 64 KiB), counted against bytes the codec actually
  consumed, so read-ahead gives no credit.

## Build

```
export JAVA_HOME="C:\\Projekte\\jdk-17.0.12+7"; export PATH="/c/Projekte/jdk-17.0.12+7/bin:/d/Claude/tools/maven/apache-maven-3.9.9/bin:$PATH"
mvn -q -pl core test
```

The design contract between the parts is in `docs/design.md`, the normative rules in `spec/SPEC.md`.
Licensed under the MIT license.
