# spillgate-java

Decode untrusted compressed data under mandatory limits. A decompression bomb ends in a typed
`SpillgateException` instead of exhausting memory. Java port of
[spillgate](https://github.com/FelixMiddelhoff/spillgate): it implements the shared specification
(`spec/SPEC.md`) and passes the shared conformance corpus. Java 17 or newer.

**New here? Read the [how-to guide](docs/how-to.md)**: choosing limits, wiring spillgate into
`java.net.http.HttpClient`, Apache HttpClient, OkHttp, Servlet, Spring and Netty, mapping results to
HTTP statuses, and finding the places in your service that decompress today (`GZIPInputStream`,
`InflaterInputStream` and friends have no bound).

```java
import io.github.felixmiddelhoff.spillgate.*;
import java.io.IOException;
import java.io.InputStream;

// Share one Decoder per process: it is immutable and thread safe.
// maxOutput is required; there is no unlimited mode.
static final Decoder DECODER = buildDecoder();

static Decoder buildDecoder() {
    try {
        return Decoder.of(Limits.maxOutput(8L << 20).withMaxInput(2L << 20));
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
    } catch (SpillgateException e) {          // before IOException: it is one, but not an I/O failure
        int status = e.kind().httpStatus();   // 413 for OUTPUT_LIMIT, 415 for UNSUPPORTED_ENCODING, ...
        throw e;
    }
}
```

`contentEncoding` is a varargs of header field values as received (`"gzip, br", "zstd"`). The
API is blocking (`InputStream`); in reactive or NIO code run it on a worker thread.

## Modules and artifacts

Group `io.github.felixmiddelhoff`, version `0.1.0` (not released yet; see [CHANGELOG.md](CHANGELOG.md)):

| Artifact | Java module | Adds | Dependency |
| --- | --- | --- | --- |
| `spillgate` | `io.github.felixmiddelhoff.spillgate` | `identity`, `gzip`, `deflate` (zlib and raw) | none (JDK `Inflater` behind own framing) |
| `spillgate-zstd` | `io.github.felixmiddelhoff.spillgate.zstd` | zstd, `ZstdCodec` (own decoder, no dictionaries) | `spillgate` |
| `spillgate-brotli` | `io.github.felixmiddelhoff.spillgate.brotli` | brotli, `BrotliCodec` (vendored copy of Google's pure Java decoder, see [brotli/VENDORED.md](brotli/VENDORED.md)) | `spillgate` |

```xml
<dependency>
  <groupId>io.github.felixmiddelhoff</groupId>
  <artifactId>spillgate</artifactId>
  <version>0.1.0</version>
</dependency>
```

The repository also holds `spillgate-test-support`, `spillgate-conformance` and `spillgate-howto`
(tests only, never published). Extension codecs are registered explicitly on a decoder; there is no
global registry:

```java
Decoder decoder = Decoder.builder(Limits.maxOutput(8L << 20).withMaxInput(2L << 20))
        .codec("br", BrotliCodec.create(BrotliOptions.defaults().withMaxWindowBits(22)))
        .codec("zstd", ZstdCodec.create(ZstdOptions.defaults().withMaxWindowBytes(4L << 20)))
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
- **Codec failures are contained.** A `RuntimeException`, `StackOverflowError`, `LinkageError` or a
  contract violation from a codec is `INTERNAL`. An `OutOfMemoryError` (or another
  `VirtualMachineError`) releases the layer and propagates: the JVM is no longer in a state the
  library can vouch for.
- **`close()`** is idempotent and releases every codec (native memory of the `Inflater` included); it
  does **not** close your source. Reading after `close()` throws a plain `IOException("stream
  closed")`. Nothing relies on `finalize` or a `Cleaner`: close your streams. `available()` returns 0.
- **Cancellation.** `new CancelToken()` passed to `reader(source, token, ...)`; `token.cancel()` from
  any thread. A canceled stream throws `SpillgateException(CANCELED)`, sticky, never
  `InterruptedIOException`. `Thread.interrupt()` is not cancellation.
- **Threads.** A `Decoder` is thread safe; share one. A stream is not safe for concurrent use.

## Memory

Streaming holds a few hundred KiB at most (4 KiB input buffer plus up to 64 KiB scratch per layer);
`decodeAll` grows with the bytes produced, never from `maxOutput`, and peaks at up to about three
times the output while its buffer grows. Brotli and zstd add their window, bounded by the cap you set.
The numbers and the tests that measure them are in [docs/memory.md](docs/memory.md).

## Security model

- Every limit is checked before bytes are handed out; no allocation is sized from a value in the
  data (declared sizes, headers, stream lengths).
- `Content-Encoding` is parsed strictly (ASCII only, 1024 bytes, 16 elements, token grammar); a chain
  cannot multiply a bomb because each layer has its own budget.
- A codec never gets the caller's array: it writes into a private scratch buffer and the guard copies
  validated bytes out, so a codec that ignores its length cannot damage your memory.
- The ratio is judged once per codec step (at most 64 KiB), counted against bytes the codec actually
  consumed, so read-ahead gives no credit.
- Not covered: the size of the compressed request you accept, timeouts, CPU after decoding, archive
  formats. See the how-to guide, section 3. Vulnerabilities: [SECURITY.md](SECURITY.md).

## Status

Version 0.1.0 is in preparation and not published yet; the API may still change before 1.0. The
library passes all 353 vectors of the shared conformance corpus with brotli and zstd registered and
no vector skipped (`ConformanceTest`), every rule of the specification is named by a test
(`SpecTraceTest`), and the core, zstd and brotli tests run under small heaps (see
[docs/memory.md](docs/memory.md)). Design contract between the parts: [docs/design.md](docs/design.md);
normative rules: [spec/SPEC.md](spec/SPEC.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md);
releasing: [RELEASING.md](RELEASING.md).

## Build

```
mvn -q test                    # Java 17+, Maven 3.9
```

## License

[MIT](LICENSE). The vendored brotli decoder is MIT licensed as well ([brotli/LICENSE-brotli](brotli/LICENSE-brotli)).
