# How to use spillgate in your Java service

This guide is for the developers who build the service: where to put spillgate, how to choose the
limits, how to wire it into HTTP clients, web frameworks and other data paths, and what to do with
the results. The rules behind the library are in [spec/SPEC.md](../spec/SPEC.md); the API is
documented in the Javadoc.

Java blocks marked with a first comment line that names a library (`// needs: ...`) use a library
this repository does not depend on (Apache HttpClient 5, OkHttp, Servlet, Spring, Netty). They are
not compiled here; they follow the public APIs of those libraries, so check them against the
versions you use. Every other Java block is taken from
[`howto/src/test/java/.../HowToSnippetsTest.java`](../howto/src/test/java/io/github/felixmiddelhoff/spillgate/howto/HowToSnippetsTest.java),
which compiles and runs them (`mvn -pl howto -am test`, under a 64 MiB heap), against a real local HTTP
server for the HTTP examples. Imports are omitted in the blocks below; they are in that file.

**spillgate is a blocking `InputStream` API.** It never starts threads. In reactive or NIO code
you run it on a worker thread (section 3.3).

## 1. What spillgate protects you from

Any code that decompresses bytes someone else controls can be made to allocate gigabytes from a few
kilobytes (a decompression bomb): by one layer, by a chain of encodings
(`Content-Encoding: gzip, gzip, gzip, ...`), by lying size fields, or by exotic codecs. spillgate
sits in front of the decompressor and turns each of those into a typed `SpillgateException` with
bounded memory. It does not replace limits on the compressed size, timeouts or authentication (see
"What is not covered" at the end of section 3).

The rule of thumb: **every place where your service turns compressed bytes into plain bytes must go
through spillgate**. Section 6 shows how to find those places.

The Java platform and its libraries have these traps:

- **`GZIPInputStream` and `InflaterInputStream` have no bound.** `new GZIPInputStream(in).readAllBytes()`,
  `transferTo(out)`, `IOUtils.toByteArray(...)`, `Files.copy(gzipStream, path)` and a
  `while (read != -1)` loop that collects into a `ByteArrayOutputStream` all run until the heap is
  full. `readAllBytes()` buffers in chunks and then copies once, so its peak is about twice the
  output, and it ends with an `OutOfMemoryError` ("Required array size too large") at the 2^31-9 byte
  array limit. `readNBytes(len)` stops at `len` and returns normally: like `take(n)` in other
  languages it **truncates** instead of failing, so a bomb and a body of exactly `len` bytes look the
  same.
- **`available()` lies.** `InflaterInputStream.available()` (and so `GZIPInputStream`) returns 1
  until the end of the stream and 0 after it. It says nothing about how many bytes are left. Code
  that sizes a buffer with `new byte[in.available()]` or uses `available() == 0` as an end test
  reads wrongly or loops. spillgate's stream returns 0 and promises nothing.
- **`BufferedInputStream` hides sizes, it does not bound them.** Wrapping a decoder in a
  `BufferedInputStream`, `InputStreamReader` or a parser (Jackson, JAXB, a CSV reader) adds
  buffering but no limit; the parser consumes the whole stream.
- **Allocation from a declared size.** The gzip trailer holds the size mod 2^32, a zstd frame header
  can declare up to 2^64 bytes, `Content-Length` is a claim, a binary protocol has a length prefix.
  `new byte[declared]`, `ByteBuffer.allocate(declared)`, `ByteArrayOutputStream(declared)` and
  `Inflater.inflate(new byte[expectedSize])` let the sender choose your allocation. spillgate never
  sizes anything from such a value.
- **`java.util.zip.Inflater` and `DataFormatException`.** `Inflater.inflate` throws the checked
  `DataFormatException`, which is not an `IOException`; `GZIPInputStream` turns it into a
  `ZipException`, an `IOException`. Code that catches `Exception` or `IOException` around a decode and
  carries on "with what it got" treats a corrupt stream as a short body. A hand-written
  `while (!inflater.finished()) inflater.inflate(buf)` loop spins forever on a truncated stream when
  it ignores `needsInput()` and `needsDictionary()`.
- **Native memory outside the heap.** An `Inflater` holds a native zlib state (tens of KiB each).
  The heap limit (`-Xmx`) does not see it. Streams you do not `close()` (or `Inflater`s you do not
  `end()`) keep that memory until the garbage collector finds them; a service that is short on
  native memory but not on heap gets killed by the container instead of failing with an
  `OutOfMemoryError`. spillgate releases it in `close()`, at the clean end and on every error
  (section 4).
- **HTTP clients decode for you.** Apache HttpClient (`ContentCompressionExec`, on by default in
  4.x and 5.x classic clients) and OkHttp (`BridgeInterceptor`: when you do not set
  `Accept-Encoding` it adds `gzip` and decodes the response) expand without a bound before your
  code sees a byte. `java.net.http.HttpClient` is the exception: it neither adds `Accept-Encoding`
  nor decodes, so a gzip response arrives as gzip, and the usual "fix" is
  `new GZIPInputStream(response.body())`, which is unbounded. Spring's `RestTemplate` and
  `WebClient` inherit the behavior of the client under them (Reactor Netty's `compress(true)`
  decodes).
- **Servers decode for you, or filters do.** Netty's `HttpContentDecompressor` in the pipeline,
  `DelegatingDecompressorFrameListener` for HTTP/2, Jetty's `GzipHandler` (it can inflate request
  bodies when it is configured to), and any servlet filter that wraps `request.getInputStream()` in a
  `GZIPInputStream` (many projects have a class called `GzipFilter` or `GzipRequestFilter` doing
  exactly that) hand your code plain bytes. Tomcat's `compression` settings and Spring Boot's
  `server.compression.*` only compress **responses**; they do not decode request bodies. If such a
  layer sits before spillgate, spillgate only bounds data that has already been expanded.
- **Servlet request bodies are unlimited.** `HttpServletRequest.getInputStream()` and Spring's
  `@RequestBody byte[]` read as much as the client sends; `maxPostSize` and Spring's
  `spring.servlet.multipart.*` limits apply to form and multipart parsing only. Bound the compressed
  size too (section 2, `maxInput`).
- **Errors that look like I/O.** `SpillgateException` extends `IOException`. A generic
  `catch (IOException e) { retry(); }` or `catch (IOException e) { return 500; }` placed before or
  instead of a `SpillgateException` handler retries a bomb and reports an attack as a server fault
  (section 4).

## 2. Choose the limits

```java
Limits limits = Limits.maxOutput(8L << 20)  // required: bytes each layer may produce
        .withMaxLayers(3)                   // default 5, range 1..16
        .withMaxRatio(1000)                 // default 1000
        .withRatioGrace(64 * 1024)          // default 64 KiB
        .withMaxInput(2L << 20);            // optional: compressed bytes taken from the source
```

| Setter | What it bounds | Default |
| --- | --- | --- |
| `Limits.maxOutput(n)` | bytes **each layer** may produce (required, `1 .. 2^63-1`) | none |
| `withMaxLayers(n)` | non-identity encodings in one chain (1..16) | 5 |
| `withMaxRatio(n)` | output bytes per input byte the decoder consumed (1..2^31-1) | 1000 |
| `withRatioGrace(n)` | output size below which the ratio is not checked | 64 KiB |
| `withMaxInput(n)` | compressed bytes taken from the source (never more than `n + 1` are requested; the byte after `n` may be looked at, only taking it is `INPUT_LIMIT`) | none |
| `withoutRatioLimit()` | named opt-out of the ratio check; cannot be combined with `withMaxRatio` | off |

`Limits` is immutable. It is validated completely when the decoder is built: a missing, zero or
out-of-range value is `Kind.INVALID_LIMITS` from `Decoder.of` / `DecoderBuilder.build()`, never during
traffic. There is no unlimited mode, no default for `maxOutput`, and no number that switches a limit
off.

### maxOutput: the memory you are willing to spend

Think of it as "the largest legitimate body this endpoint should ever see, after decompression". Do
not use one global number:

| Data path | Starting point |
| --- | --- |
| JSON API request bodies | 1 to 10 MiB |
| HTML or JSON fetched from third parties | 8 to 32 MiB |
| File upload processed in memory (`decodeAll`) | the largest file you accept, and no more |
| File upload streamed to disk | your storage quota per object |
| Message queue payloads | the broker's maximum message size times a small factor |

Look at the 99.9th percentile of real decoded sizes in production and add headroom. A limit 100
times larger than anything legitimate only helps the attacker. `decodeAll` holds the whole body in
memory (a Java array: at most 2^31-9 bytes, whatever `maxOutput` says), so `maxOutput` is also its
memory bound per call: allow about three times `maxOutput` for the moment the buffer grows (section
"Memory" in [memory.md](memory.md)), and multiply by your concurrency (request threads, virtual
threads, executor size).

### maxRatio and ratioGrace

The ratio is checked per layer and end to end. The default of 1000 stops the classic bombs (zeros
compress to about 1030:1 in DEFLATE at best) while ordinary data stays far below it. Raise it only
if a measured, legitimate data type needs it. Brotli and zstd can reach much higher ratios on sparse
data, so an endpoint that accepts, for example, large sparse arrays may need a higher `maxRatio`.
Prefer raising it for one endpoint over `withoutRatioLimit()`, which leaves only `maxOutput` between
you and a bomb. Leave `ratioGrace` alone; it keeps tiny messages from being refused.

### maxLayers

Real traffic uses one layer, rarely two. The default of 5 is generous; use 2 or 3 for public
endpoints. Chains of more than 16 elements are refused whatever you set.

### maxInput

If the compressed size is not already bounded by your web server, framework or broker, add
`withMaxInput`. spillgate bounds the output; it is not a substitute for limiting how many bytes you
accept. Do both. `maxInput` counts the bytes taken from the source of layer 1 and also covers bodies
without any encoding.

What counts is taking, not looking: a decoder may look at the one byte after `maxInput` without
taking it, so a complete stream of exactly `maxInput` bytes followed by trailing data is `CORRUPT`
(trailing data), not `INPUT_LIMIT`. A stream that needs that byte to go on (it is cut at the limit and
the source continues) is `INPUT_LIMIT`; if the source itself ends there it is a truncated stream
(`CORRUPT`). Identity bodies take every byte they pass: one byte beyond `maxInput` is `INPUT_LIMIT`
after exactly `maxInput` bytes were delivered. Only layer 1 has the limit, and at most `maxInput + 1`
bytes are ever requested from the source.

## 3. Wire it in

Create one `Decoder` at start-up and share it. It is immutable and thread safe and holds no buffers;
the streams it returns are independent of each other and not meant to be shared between threads.

```java
/** One decoder per process: immutable and thread safe. A bad configuration fails at start-up. */
static final Decoder DECODER = createDecoder();

static Decoder createDecoder() {
    try {
        return Decoder.builder(Limits.maxOutput(8L << 20).withMaxLayers(3).withMaxInput(2L << 20)).build();
    } catch (SpillgateException e) {
        throw new ExceptionInInitializerError(e);
    }
}
```

Building the decoder validates the limits and reads no data. Let a misconfiguration crash at
start-up, not on the first request. In Spring, expose it as a `@Bean`; with CDI as an
`@ApplicationScoped` producer.

`source` is any `InputStream` (a file, a socket stream, `ByteArrayInputStream`, a servlet input
stream). spillgate never closes it: `close()` of the spillgate stream releases the decoder, you close
your source (try-with-resources on both). The encodings are the `Content-Encoding` values as
received, as a varargs: one string, or one per header line when the header was sent several times
(`"gzip, br"` and `"zstd"` are two elements; spillgate joins them in order). Pass no encoding for a
body that should still be bounded and has none.

The built-in codecs are `identity`, `gzip` (also `x-gzip`) and `deflate` (zlib; raw deflate is
accepted unless you call `strictZlib(true)` on the builder). Section 3.8 adds brotli and zstd.

### 3.1 `java.net.http.HttpClient` (JDK 11+)

It does not decode and does not send `Accept-Encoding` by itself, so you only have to set the header
and decode the stream yourself, with spillgate instead of `GZIPInputStream`:

```java
/** java.net.http.HttpClient never decodes Content-Encoding and sends no Accept-Encoding by itself. */
static byte[] fetch(HttpClient client, URI uri) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri)
            .header("Accept-Encoding", "gzip, deflate") // only what the decoder supports
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream raw = response.body()) { // closing it aborts the transfer on an error
        String[] encodings = response.headers().allValues("Content-Encoding").toArray(new String[0]);
        return DECODER.decodeAll(raw, encodings); // SpillgateException on any refusal
    }
}
```

Never use `BodyHandlers.ofString()` or `ofByteArray()` on a response you have not bounded:
`HttpClient` has no body size limit, and they read to the end. Use `ofInputStream()` and
`decodeAll`/`reader` as above, and put a timeout on the request.

### 3.2 Apache HttpClient 5 and OkHttp

Both decode gzip (and Apache also deflate, and brotli when the optional library is on the classpath)
by default. Switch the decoding off, set `Accept-Encoding` yourself, and decode the raw stream.

Apache HttpClient 5 (classic API). `disableContentCompression()` removes both the request
interceptor that adds `Accept-Encoding` and the `ContentCompressionExec` that decodes:

```java
// needs: org.apache.httpcomponents.client5:httpclient5 (5.x)
CloseableHttpClient client = HttpClientBuilder.create()
        .disableContentCompression()
        .build();

HttpGet get = new HttpGet(uri);
get.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");
byte[] body = client.execute(get, response -> {
    String[] encodings = Arrays.stream(response.getHeaders(HttpHeaders.CONTENT_ENCODING))
            .map(Header::getValue)
            .toArray(String[]::new);
    try (InputStream raw = response.getEntity().getContent()) {   // still compressed
        return DECODER.decodeAll(raw, encodings);
    }
});
```

If the client is created by a library or a framework (Spring's `HttpComponentsClientHttpRequestFactory`,
an SDK), configure that builder; a client you did not build may decode. The 4.x builder has the same
`disableContentCompression()`.

OkHttp (3.x/4.x/5.x). The `BridgeInterceptor` decodes a gzip response **only if it added
`Accept-Encoding: gzip` itself**. A request that carries its own `Accept-Encoding` header gets the raw
body:

```java
// needs: com.squareup.okhttp3:okhttp (4.x or 5.x)
OkHttpClient client = new OkHttpClient();
Request request = new Request.Builder()
        .url(url)
        .header("Accept-Encoding", "gzip, deflate")   // set it: then OkHttp does not decode
        .build();
try (Response response = client.newCall(request).execute();
        ResponseBody responseBody = Objects.requireNonNull(response.body())) {
    String[] encodings = response.headers("Content-Encoding").toArray(new String[0]);
    byte[] body = DECODER.decodeAll(responseBody.byteStream(), encodings);
}
```

An OkHttp interceptor of your own or a library (a logging or "gzip request" interceptor) that wraps
the body in `GzipSource` is a decoder too; check `client.interceptors()` and
`client.networkInterceptors()`. Never call `responseBody.string()` or `bytes()` on a body that is
not bounded.

### 3.3 Spring `RestTemplate`, `RestClient` and `WebClient`

`RestTemplate` and `RestClient` use the client under them: with
`JdkClientHttpRequestFactory` (java.net.http) nothing is decoded; with
`HttpComponentsClientHttpRequestFactory` use a client built as in 3.2; with OkHttp set the header.
Read the body as `byte[]` after bounding the compressed size, or as an `InputStream` through
`execute(..., ResponseExtractor)`.

`WebClient` is reactive; spillgate is blocking. Read the **compressed** body into memory with a size
limit (`maxInMemorySize`; the compressed size is small and bounded), then decode on a bounded worker pool so the event
loop is not blocked. Reactor Netty (the default connector) decompresses when `compress(true)` is set
on its `HttpClient`; leave it off and send `Accept-Encoding` yourself. Check with a test against your
version that the bytes still arrive encoded (serve a gzip body from a local server and assert on the
first bytes, `1f 8b`, before decoding):

```java
// needs: org.springframework:spring-webflux (6.x), spring-boot's reactor-netty connector
WebClient client = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(4 << 20))      // bound on the COMPRESSED body
        .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate")
        .build();

Mono<byte[]> body = client.get().uri(uri)
        .exchangeToMono(response -> {
            String[] encodings = response.headers().header(HttpHeaders.CONTENT_ENCODING).toArray(new String[0]);
            return response.bodyToMono(byte[].class)                  // raw: nothing has decoded it
                    .publishOn(Schedulers.boundedElastic())          // decoding blocks
                    .map(raw -> decode(raw, encodings));
        });

static byte[] decode(byte[] raw, String[] encodings) {
    try {
        return DECODER.decodeAll(new ByteArrayInputStream(raw), encodings);
    } catch (IOException e) {
        throw Exceptions.propagate(e);   // a SpillgateException stays the cause; map it where you handle errors
    }
}
```

With virtual threads (JDK 21+) the blocking call is not a problem on a virtual thread, but a
`decodeAll` of `maxOutput` bytes is still `maxOutput` bytes of heap per concurrent request.

### 3.4 Servlet and Spring MVC request bodies

Servlet containers and Spring MVC do not decode `Content-Encoding` on requests; whatever gives you
the stream gives you the compressed bytes. The order is: bound the compressed body, then decode
(spillgate with `withMaxInput` does both), and make sure no filter decodes before you.

```java
// needs: jakarta.servlet:jakarta.servlet-api (6.x; use javax.servlet on Servlet 4 / Spring 5)
@WebServlet("/ingest")
public class IngestServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] encodings = Collections.list(request.getHeaders("Content-Encoding")).toArray(new String[0]);
        try (InputStream body = request.getInputStream()) {
            byte[] data = DECODER.decodeAll(body, encodings);
            process(data);
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (SpillgateException e) {
            response.sendError(refusal(e));            // fixed status, no details for the caller
        }
    }
}
```

Spring MVC, same idea. Take the stream from the request and not `@RequestBody byte[]` (which reads
without a bound):

```java
// needs: org.springframework:spring-webmvc (6.x)
@PostMapping("/ingest")
ResponseEntity<String> ingest(HttpServletRequest request) throws IOException {
    String[] encodings = Collections.list(request.getHeaders(HttpHeaders.CONTENT_ENCODING)).toArray(new String[0]);
    try {
        byte[] data = DECODER.decodeAll(request.getInputStream(), encodings);
        return ResponseEntity.ok("received " + data.length + " bytes");
    } catch (SpillgateException e) {
        return ResponseEntity.status(refusal(e)).body("request body refused");
    }
}
```

Also: set a limit on the compressed request size in the container or gateway (a gateway limit, or
`withMaxInput` as above), and remove any request-decompression filter. In Spring Boot, `server.compression.enabled=true` is about responses and harmless here.

The handler of the same shape, tested against a real server (JDK `com.sun.net.httpserver`; every
status of section 4 is asserted in section 5):

```java
/** A request handler in the style of every server: decode the body, answer 200 or the mapped status. */
static void ingest(HttpExchange exchange) throws IOException {
    List<String> encodings = exchange.getRequestHeaders().getOrDefault("Content-Encoding", List.of());
    int status;
    String reply;
    try (InputStream body = exchange.getRequestBody()) {
        byte[] data = DECODER.decodeAll(body, encodings.toArray(new String[0]));
        status = 200;
        reply = "received " + data.length + " bytes";
    } catch (SpillgateException e) { // before IOException: it is an IOException, but not an I/O failure
        status = refusal(e);
        reply = "request body refused"; // fixed text: do not echo details to the caller
    }
    byte[] out = reply.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, out.length);
    exchange.getResponseBody().write(out);
    exchange.close();
}
```

### 3.5 Netty

Do not put `HttpContentDecompressor` (or, for HTTP/2, `DelegatingDecompressorFrameListener`) in the
pipeline. Bound the compressed body with `HttpObjectAggregator`, and decode the aggregated content
with spillgate, off the event loop if the bodies can be large:

```java
// needs: io.netty:netty-codec-http (4.1.x)
pipeline.addLast(new HttpServerCodec());
pipeline.addLast(new HttpObjectAggregator(2 << 20));          // bounds the COMPRESSED body (413 if more)
// no HttpContentDecompressor here
pipeline.addLast(executorGroup, new SimpleChannelInboundHandler<FullHttpRequest>() {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String[] encodings = request.headers().getAll(HttpHeaderNames.CONTENT_ENCODING).toArray(new String[0]);
        HttpResponseStatus status;
        try (InputStream body = new ByteBufInputStream(request.content())) {
            byte[] data = DECODER.decodeAll(body, encodings);
            status = HttpResponseStatus.OK;
            process(data);
        } catch (SpillgateException e) {
            status = HttpResponseStatus.valueOf(refusal(e));
        } catch (IOException e) {
            status = HttpResponseStatus.BAD_REQUEST;
        }
        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status)).addListener(ChannelFutureListener.CLOSE);
    }
});
```

`executorGroup` is an `EventExecutorGroup` (for example `new DefaultEventExecutorGroup(8)`) so the
blocking decode does not run on an I/O thread. For a Netty **client**, the same: no
`HttpContentDecompressor`, and the `Content-Encoding` header of the `HttpResponse` is read before the
content.

### 3.6 Any other source: files, S3-like streams, queues

Files, object-storage SDK streams (`S3Object.getObjectContent()`, `ResponseInputStream` of the AWS SDK
v2, GCS and Azure readers) and message payloads are all `InputStream`s or `byte[]`s. For content
stored with a known encoding pass it yourself (`"gzip"`). Streaming to disk, where the target must
appear only if the whole body decoded:

```java
/** Streaming to disk: the target appears only if the whole body decoded. */
static void decodeToFile(InputStream source, String encoding, Path target) throws IOException {
    Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), "upload-", ".tmp");
    try (SpillgateInputStream in = DECODER.reader(source, encoding)) {
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
        Files.deleteIfExists(tmp); // after a refusal the partial file is removed
    }
}
```

A queue consumer: `DECODER.decodeAll(new ByteArrayInputStream(message.body()), message.contentEncoding())`,
with `maxOutput` from the broker's maximum message size, and a refusal goes to the dead-letter queue,
not back to the queue (it would fail the same way every time).

### 3.7 Whole body versus streaming

- `decodeAll` (the instance method, or the static one that takes the `Limits`): simplest. You get the
  complete body or an exception and **no data**. Peak memory is the decoded size, and a body that
  does not fit one Java array fails with `OUTPUT_LIMIT`.
- `reader(...)`: streaming, a normal `InputStream` (`read`, `readAllBytes`, `transferTo`, `skip` all
  work and are bounded). **Bytes can be delivered before a later exception.** Whatever you did with
  them (wrote to a file, fed a parser, stored in a database, sent downstream) must be undone when an
  exception comes. Write to a temporary file and move it on success; never commit a partial body.

A single `read(byte[], off, len)` returns at most 64 KiB whatever `len` is, so a huge buffer does not
allocate a huge buffer. A stream is not safe for use from several threads; different streams are
independent. Reading after `close()` throws a plain `IOException("stream closed")`, not a
`SpillgateException`.

### 3.8 Cancellation

`reader(source, token, encodings)` stops the stream with `Kind.CANCELED` (sticky) once
`token.cancel()` was called, from any thread. It is looked at on every `read` and whenever a layer
fetches more input (at least once per 4 KiB of compressed input). It cannot interrupt one codec step
(at most 64 KiB of output), and it does **not** interrupt a blocking `read` of your source: a stalled
socket needs its own timeout. **`Thread.interrupt()` is not cancellation**, and a canceled stream
does not throw `InterruptedIOException`, because some callers retry that and a canceled stream must
not look retryable.

```java
/** Reads to the end or until another thread calls token.cancel(). */
static long drain(InputStream source, String encoding, CancelToken token) throws IOException {
    long total = 0;
    try (SpillgateInputStream in = DECODER.reader(source, token, encoding)) {
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
        }
    }
    return total;
}
```

### 3.9 Brotli and zstd

Add the artifacts (`spillgate-brotli`, `spillgate-zstd`) and register the codecs. Offer in
`Accept-Encoding` only what you registered:

```java
static Decoder withBrotliAndZstd() throws SpillgateException {
    return Decoder.builder(Limits.maxOutput(8L << 20).withMaxInput(2L << 20))
            .codec("br", BrotliCodec.create(BrotliOptions.defaults().withMaxWindowBits(22)))
            .codec("zstd", ZstdCodec.create(ZstdOptions.defaults().withMaxWindowBytes(4L << 20)))
            .build();
}

static final String ACCEPT_ENCODING = "gzip, deflate, br, zstd"; // exactly what is registered
```

A stream that asks for a larger window than the cap fails with `Kind.MEMORY_LIMIT` before a byte is
decoded (the window is read from the header; the brotli large-window extension is always refused).
The window is memory per in-flight request (see [memory.md](memory.md)): lower the caps on busy
servers. `spillgate-brotli` contains a vendored copy of Google's pure Java decoder behind own
framing, `spillgate-zstd` is an own decoder with no dictionary support (a dictionary frame is
`corrupt`); neither has a runtime dependency.

Zstd has one more cost the output limits cannot see: frames that produce no output (empty frames,
skippable frames) cost CPU per input byte but no output. Always set `withMaxInput` (or a body size
limit) on endpoints that accept zstd.

### 3.10 Your own codec

Implement `Codec` (a factory) and `CodecReader` (a decoding step) and register it on one decoder:

```java
/** Example codec "rot": XOR 0x20 on every byte (swaps ASCII letter case). */
static final class Rot implements Codec {
    @Override
    public CodecReader newReader(CodecInput input) {
        return new CodecReader() {
            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int available = input.fill(); // 0 is the end of the input
                if (available == 0) {
                    return -1; // only valid here: the format has no trailer to wait for
                }
                int n = Math.min(available, len);
                byte[] source = input.buffer();
                int position = input.position();
                for (int i = 0; i < n; i++) {
                    buf[off + i] = (byte) (source[position + i] ^ 0x20);
                }
                input.consume(n); // consume only what was used: the ratio meter counts it
                return n;
            }
        };
    }
}

static Decoder withRot() throws SpillgateException {
    return Decoder.builder(Limits.maxOutput(1 << 20)).codec("rot", new Rot()).build();
}
```

The codec runs behind the same limiter, ratio meter and input meter as the built-in ones, so even a
careless codec cannot exceed `maxOutput`; a `RuntimeException` or `StackOverflowError` in it becomes
`Kind.INTERNAL`. Names must be lower-case tokens and must not reuse `identity`, `gzip`, `deflate` or
`x-gzip` (`build()` throws `INVALID_OPTION`). A codec must not allocate from values read from the
stream except its window within a cap; the contract is in the Javadoc of `CodecReader` and
`CodecInput`. Read compressed input only through `CodecInput`, and `consume` only what you used. With a `maxInput`, the window may end in the byte after the limit (the probe), shown alone: consuming it is `INPUT_LIMIT` at the end of the step; to find trailing data after a complete stream, look at the byte (`fill()`) without consuming it, and if you buffered it ahead, give it back with `unreadProbe()`.

### What is not covered

spillgate bounds the **decompressed** bytes, the ratio, the layers, memory and (optionally) the
compressed bytes it reads. It does not bound the size of the request you accept before it (use your
server's body limit), time (a client that sends one byte per minute; use timeouts on the socket and
on the whole request, and `CancelToken`), CPU of work after decoding (parsers, schema validation,
`ObjectMapper`), or archive formats (`zip`, `tar`, `jar`: bound entry counts and sizes separately;
`ZipInputStream` has the same bomb problem). It does not check authentication or content types.

## 4. Handle the results

Every refusal is a `SpillgateException` (an `IOException`) with a `kind()` (`Kind`), the `layer()`
that failed (1 reads from the source) and the `limit()` that was exceeded. `kind().httpStatus()` is
the mapping below; `kind().specName()` is the stable name for logs and metrics.

```java
private static final Logger LOG = Logger.getLogger("ingest");

/** Logs the refusal and returns the HTTP status for a server (the same mapping in every spillgate port). */
static int refusal(SpillgateException e) {
    switch (e.kind()) {
        case INTERNAL, MEMORY_LIMIT ->
                LOG.severe("decoder problem: " + e.kind().specName() + " layer=" + e.layer());
        case SOURCE_ERROR, CANCELED ->
                LOG.fine("decode interrupted: " + e.kind().specName()); // not an attack signal
        default -> LOG.warning("refused: " + e.kind().specName() + " layer=" + e.layer() + " limit=" + e.limit());
    }
    // metrics.counter("refusals", "kind", e.kind().specName()).increment(); // your metrics
    return e.kind().httpStatus();
}
```

**Catch `SpillgateException` before `IOException`.** It is an `IOException` so that it passes through
`InputStream` code, but it is not an I/O failure:

```java
try (SpillgateInputStream in = DECODER.reader(source, encodings)) {
    process(in);
} catch (SpillgateException e) {          // first: a refusal, mapped by kind
    return refusal(e);
} catch (IOException e) {                 // then: failures that are not spillgate's
    return 500;
}
```

Do not swallow it as a generic I/O failure: no `catch (IOException e) { retry }` around a decode, no
`catch (Exception e) { log.warn(...) }` that continues with the bytes already read, no wrapping into
`UncheckedIOException` before you looked at the kind. When an `IOException` is only a carrier
(a lambda that cannot throw it), keep the exception as the cause and unwrap it at the edge:
`e.getCause() instanceof SpillgateException`. Prefer `try-with-resources` on the stream: it
releases the decoder (native memory included) on every path. A failing stream also releases its
codecs by itself, but do not rely on the garbage collector: nothing here uses `finalize` or a
`Cleaner`.

A source that fails (`IOException` from your `InputStream`) arrives as `SOURCE_ERROR` with the
original exception as `getCause()`; a `SpillgateException` from a lower layer or another decoder's
stream passes through unchanged. An `OutOfMemoryError` inside a codec is **not** converted: it
releases the layer's state and propagates, because the JVM is no longer in a state the library can
vouch for. Do not catch `Error`.

### What each outcome means and what to do

| Kind (`specName`) | Meaning | Server answers (`httpStatus`) | Client side | Log / metric |
| --- | --- | --- | --- | --- |
| `OUTPUT_LIMIT` (`output_limit`) | the data decodes to more than `maxOutput` | 413 | treat the response as failed, do not retry | warn, count; a spike is an attack or a limit that is too low |
| `RATIO_LIMIT` | expansion over `maxRatio` | 413 | failed, do not retry | warn, count; check if a legitimate data type needs a higher ratio |
| `LAYER_LIMIT` | more layers or elements than allowed | 413 | failed, do not retry | warn, count |
| `INPUT_LIMIT` | more compressed bytes than `maxInput` | 413 | failed, do not retry | warn, count |
| `UNSUPPORTED_ENCODING` | a valid token the decoder cannot handle (`compress`, `dcz`, `aes128gcm`, ...) | 415 | failed; nothing was decoded | info; shows clients using something you do not offer |
| `MALFORMED_ENCODING` | the header breaks the strict grammar | 400 | failed | info, bounded text only |
| `CORRUPT` | the stream is invalid, truncated or has trailing data | 400 | may retry once if the connection was cut; a repeat is a bad server | info |
| `MEMORY_LIMIT` | a brotli or zstd stream needs a larger window than the cap | 413 | failed, do not retry | warn; check the cap if legitimate traffic hits it |
| `SOURCE_ERROR` | your source (network, disk) failed; the original is `getCause()` | 400 (or 5xx by your policy) | retry by your usual policy | the cause is in `getCause()` |
| `CANCELED` | your `CancelToken` was canceled | 503, usually no response is needed | no retry by itself | debug |
| `INTERNAL` | a codec or source broke its contract (an exception in a codec, no progress) | 500 | failed | **error and alert**: please report it (see [SECURITY.md](../SECURITY.md)) |
| `INVALID_LIMITS`, `INVALID_OPTION` | bad configuration | 500 | - | thrown by `build()`, never during traffic |

The status column is exactly what `Kind.httpStatus()` returns, the same mapping as in the other
spillgate ports: 413 for output, ratio, layer and input limits, 415 for unsupported encodings, 400
for malformed header, corrupt data and a failed read, 503 for cancellation, 500
otherwise.

General rules:

1. **Any exception means the whole body is unusable**, including the bytes you already received.
2. **Never retry a limit error.** The same input gives the same result and costs the same work.
3. **Branch on `kind()`, do not parse messages.** Keep a `default` branch: new kinds can appear in a
   minor version.
4. **Do not echo error details to the caller.** Return a fixed message and the status code. The
   library's own messages contain at most 32 bytes of attacker data, quoted and escaped
   (`SpillgateException.quote`), but your logs and responses are your decision; log the kind, layer,
   limit and a request ID, not the body.
5. **Treat repeated limit errors from one client as abuse**: rate-limit or block like other malformed
   input. One `RATIO_LIMIT` can be an accident; thousands from one address are not.
6. **Alert on `INTERNAL` and `MEMORY_LIMIT`** rather than on the limit errors you expect.
7. **Do not turn a refusal into an unhandled exception in a request path.** A refusal is the normal
   outcome for hostile input; an uncaught exception that reaches a framework's default handler
   becomes a 500 and an alert about nothing, or worse, a stack trace in the response.

### Tuning after launch

Run for a few days with the limits at their intended values and count refusals per endpoint. If
legitimate traffic is refused, raise the specific limit for that endpoint by the smallest amount that
fixes it and write down why. Do not switch limits off to make an alert go away.

## 5. Test your integration

1. **Build a bomb and see it refused.** 50 MB of zeros compress to about 50 KB. `GZIPOutputStream`
   is fine in tests; it must not be in the request path:

   ```java
   static byte[] gzipBomb(int size) throws IOException {
       ByteArrayOutputStream bytes = new ByteArrayOutputStream();
       try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { // fine in tests, never in the request path
           byte[] zeros = new byte[64 * 1024];
           for (int written = 0; written < size; written += zeros.length) {
               gzip.write(zeros, 0, Math.min(zeros.length, size - written));
           }
       }
       return bytes.toByteArray();
   }
   ```

   Then send it to your endpoint in an integration test and assert on 413:

   ```java
   static int post(HttpClient client, URI uri, byte[] body, String contentEncoding) throws Exception {
       HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(body));
       if (contentEncoding != null) {
           request.header("Content-Encoding", contentEncoding);
       }
       return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
   }
   ```

   `HowToSnippetsTest` does this against the handler of section 3.4: a gzip bomb, a chain of seven
   `gzip` elements (413 with `maxLayers` 3), `Content-Encoding: compress` (415), a truncated body
   (400), a malformed header (400), and 3 MiB without any encoding against a `maxInput` of 2 MiB (413).
   A server that refuses a large body while the client is still sending it may close the
   connection, so the client can see a reset instead of the 413; the test accepts both.
   For larger or exotic cases use the generated inputs of the conformance corpus
   ([conformance/](../conformance), read by `ConformanceTest`).
2. **Test every ingress.** Each endpoint and background consumer gets a bomb test, a test with
   `Content-Encoding: gzip, gzip, gzip, gzip, gzip, gzip, gzip`, one with an unknown encoding
   (`Content-Encoding: compress`, expect 415) and one with a truncated body (expect 400). For
   clients, serve the same bodies from a local server (`MockWebServer`, WireMock, the JDK's
   `HttpServer`) and assert that the client function throws a `SpillgateException`.
3. **Check memory.** Run the bomb test under a small heap and let a wrong wiring crash it. The test
   module of this repository runs with `-Xmx64m` (surefire `argLine`) and decodes a bomb of 200 MB;
   an unbounded `GZIPInputStream.readAllBytes()` would end in an `OutOfMemoryError` there. For a whole
   service use a container memory limit in the integration test and check that the process survives
   the bomb; also watch native memory (`-XX:NativeMemoryTracking=summary`, `jcmd <pid> VM.native_memory`)
   if you also use `Inflater` directly elsewhere.
4. **Test the partial-data rule.** Make the downstream step fail midway (or truncate the input) and
   check that nothing from the failed body is committed (the `toFile` snippet is tested for exactly
   that: no target and no temporary file after a refusal).

## 6. Find and fix the places that decompress today

Search your code:

```text
grep -rnE "GZIPInputStream|InflaterInputStream|InflaterOutputStream|new Inflater|Inflater\(|ZipInputStream|ZipFile|JarInputStream|readAllBytes|readNBytes|IOUtils\.toByteArray|ByteStreams\.toByteArray|toByteArray\(|ContentCompressionExec|disableContentCompression|BridgeInterceptor|GzipSource|HttpContentDecompressor|DelegatingDecompressorFrameListener|JdkZlibDecoder|GzipFilter|GzipHandler|compress\(true\)|Accept-Encoding|Content-Encoding|BrotliInputStream|ZstdInputStream|Snappy|LZ4" src/
```

And your dependency tree (`mvn dependency:tree`, `gradle dependencies`): look for `commons-compress`
(`GzipCompressorInputStream`, `BrotliCompressorInputStream`, `ZstdCompressorInputStream`),
`com.github.luben:zstd-jni`, `org.brotli:dec`, `org.xerial.snappy`, `org.lz4`, `io.netty:netty-codec`,
`httpclient5`, `okhttp`, `jetty-server`. Then look at what decodes without you asking:

- HTTP clients: Apache HttpClient 4/5, OkHttp, Spring `RestTemplate`/`RestClient`/`WebClient` over
  those, Reactor Netty (`compress(true)`), Retrofit, Feign, JAX-RS clients (Jersey and RESTEasy ship
  reader interceptors that decode gzip), and the SDKs that wrap these clients (cloud SDKs,
  Elasticsearch and other API clients: look at the HTTP client they use and at its decompression
  setting). Pass them a configuration as in section 3, or read the raw body yourself.
- Servers and frameworks: Netty `HttpContentDecompressor`, Jetty `GzipHandler` (check
  `inflateBufferSize`), Undertow's `RequestEncodingHandler`/`EncodingHandler` (can decode requests),
  Quarkus/Vert.x (`HttpServerOptions.setDecompressionSupported(true)` decodes request bodies: leave it
  off), Micronaut and Helidon decompression settings, servlet filters named `Gzip*Filter`, API gateways
  and reverse proxies that decompress for you, gRPC (`grpc-java` accepts compressed messages by
  default: configure decompressors deliberately), message-queue clients with automatic
  decompression.
- Object storage and file helpers: Hadoop and Spark codecs (`CompressionCodec`), Parquet readers,
  `Files.newInputStream` + `GZIPInputStream` for `.gz` configuration or log files that users can
  supply, Jackson `JsonParser` over a decoded stream, `commons-compress` archives (archives are out
  of scope for spillgate; bound them separately).

For each, replace the decoder with spillgate or make sure it never sees unbounded data.

## 7. Pitfalls

- **Decoding in two places.** If a proxy, filter, middleware or client library decodes before
  spillgate, spillgate only bounds the plain data and the bomb has already been expanded. Make sure
  spillgate is the first decoder in the path.
- **Wrapping the spillgate stream in a decoder again.** `new GZIPInputStream(spillgateStream)` on a
  body you decoded already fails, and on a body that was not encoded decodes it without a bound.
- **`BufferedInputStream`/`InputStreamReader` as a limit.** They are not. The limit is the `Limits`.
- **`readNBytes(n)` and `InputStream.readAllBytes()` on a decoder you built yourself.** The first
  truncates silently, the second grows until the heap is gone. Both are safe **on a
  `SpillgateInputStream`**, because the limiter sits below them.
- **Reading the body twice.** After an exception the stream is spent (errors are sticky). Do not fall
  back to "just read it plainly".
- **A huge `maxOutput` "to be safe".** It makes the guard useless. Size it from data.
- **`withoutRatioLimit()` as a first reaction.** See the tuning section.
- **Unbounded concurrency.** Each in-flight `decodeAll` may hold up to about `3 x maxOutput` bytes
  for a moment; each streaming reader holds a few hundred KiB (more with brotli or zstd: the
  window). Size the thread pool and the semaphore in front of it accordingly; with virtual threads
  there is no pool to size, so add a semaphore.
- **Blocking an event loop.** The stream is blocking. Netty I/O threads, Vert.x event loops and
  Reactor `parallel()` schedulers must not run it for more than a few KiB: use a worker executor or
  `Schedulers.boundedElastic()`.
- **Not closing the stream.** `close()` is where the decoder's native memory is released if the read
  loop was left early. Use try-with-resources. Closing the spillgate stream does not close your
  source.
- **Catching `IOException` first, or `Exception` around the decoder.** It hides refusals as I/O
  trouble and `INTERNAL` errors as noise.
- **Treating `identity` as safe.** An unencoded body is bounded as well; that is deliberate.
- **Relying on `Thread.interrupt()` to stop a decode.** Use a `CancelToken`.
- **Racing `close()` with `read()`.** A stream is for one thread. The one supported cross-thread
  use is a watchdog that calls `close()` while another thread reads: that reader gets bytes, a clean
  end or a plain `IOException("stream closed")`, never a `NullPointerException` or an `INTERNAL`
  error. Do not rely on anything else about concurrent use; to stop a read use a `CancelToken`.
- **A source that throws an `Error`.** An `AssertionError` or `StackOverflowError` from your source
  becomes `INTERNAL`; `VirtualMachineError`s such as `OutOfMemoryError` propagate (the JVM is not in a
  state the library can vouch for) after the layer's state is released.
- **Brotli's window is allocated up to the declared meta-block length, not lazily.** A stream that
  declares a large first meta-block makes the decoder allocate its ring (up to the window cap, about
  17 MiB at 2^24) and then stall on a slow source. Budget `layers x window` per concurrent stream and
  lower `withMaxWindowBits`.
- **Brotli output arrives in ring-sized pieces.** The decoder hands out bytes only when its ring is
  full or the stream ends, so `maxOutput` and the ratio check are coarse for brotli: up to one ring
  (the window) of CPU is spent past `maxOutput` before the limit can fire. What is delivered stays
  within `maxRatio x consumed`. Cancellation is checked per codec call, so it also acts at that grain.
- **JPMS.** The artifacts are named modules: `io.github.felixmiddelhoff.spillgate`, `...spillgate.zstd`,
  `...spillgate.brotli`. Add `requires io.github.felixmiddelhoff.spillgate;` (and `...zstd` or
  `...brotli` if you use them) to your `module-info.java`. Only the public package is exported
  (`io.github.felixmiddelhoff.spillgate`, `.zstd`, `.brotli`); the zstd and brotli modules `require
  transitive` the core, so one `requires` is enough. On the class path nothing is needed.

## 8. Checklist before you ship

- [ ] One shared `Decoder`, created at start-up (a bad config fails there), limits chosen per data path from real sizes.
- [ ] Every ingress (HTTP client, web endpoint, queue consumer, file or object reader) goes through spillgate; no other decoder runs before it.
- [ ] HTTP libraries are told not to decode (`disableContentCompression()`, your own `Accept-Encoding` with OkHttp, no `compress(true)`, no `HttpContentDecompressor`, no request-decompression filter), and `Accept-Encoding` is set explicitly (no `br`/`zstd` unless you registered them).
- [ ] No `GZIPInputStream`, `InflaterInputStream`, `Inflater` or `readAllBytes()` of a decoder on data from outside.
- [ ] The compressed size is bounded separately (server body limit, `withMaxInput`).
- [ ] Blocking decode never runs on an event loop or I/O thread; the worker pool and concurrency are capped.
- [ ] Streams are closed (try-with-resources); streaming consumers discard or roll back partial data on any exception.
- [ ] `SpillgateException` is caught before `IOException` and never retried or swallowed; limit errors map to 413, unsupported encodings to 415, malformed or corrupt data to 400 (`Kind.httpStatus`).
- [ ] Metrics per kind; alerts on `INTERNAL` and `MEMORY_LIMIT`; refusals visible per endpoint.
- [ ] No retries of limit errors; no echoing of attacker-supplied text.
- [ ] Bomb, deep-chain, unknown-encoding and truncation tests exist for every ingress and run in CI, under a small heap.
- [ ] You know who gets the report if `INTERNAL` appears (see [SECURITY.md](../SECURITY.md)).
