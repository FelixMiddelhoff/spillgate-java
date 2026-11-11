package io.github.felixmiddelhoff.spillgate.howto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.felixmiddelhoff.spillgate.CancelToken;
import io.github.felixmiddelhoff.spillgate.Codec;
import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Decoder;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.Limits;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import io.github.felixmiddelhoff.spillgate.SpillgateInputStream;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliCodec;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliOptions;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdCodec;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every Java snippet of docs/how-to.md lives here, between {@code // --- snippet:NAME} and
 * {@code // --- end}, and the guide is generated from these regions. Snippets that need a library this
 * repository does not depend on (Apache HttpClient, OkHttp, Servlet, Spring, Netty) are in the guide only.
 */
class HowToSnippetsTest {

    // --- snippet:decoder
    /** One decoder per process: immutable and thread safe. A bad configuration fails at start-up. */
    static final Decoder DECODER = createDecoder();

    static Decoder createDecoder() {
        try {
            return Decoder.builder(Limits.maxOutput(8L << 20).withMaxLayers(3).withMaxInput(2L << 20)).build();
        } catch (SpillgateException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    // --- end

    // --- snippet:javaHttpClient
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
    // --- end

    // --- snippet:refusal
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
    // --- end

    // --- snippet:ingest
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
    // --- end

    // --- snippet:toFile
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
    // --- end

    // --- snippet:cancel
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
    // --- end

    // --- snippet:rot
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
    // --- end

    // --- snippet:optionalCodecs
    static Decoder withBrotliAndZstd() throws SpillgateException {
        return Decoder.builder(Limits.maxOutput(8L << 20).withMaxInput(2L << 20))
                .codec("br", BrotliCodec.create(BrotliOptions.defaults().withMaxWindowBits(22)))
                .codec("zstd", ZstdCodec.create(ZstdOptions.defaults().withMaxWindowBytes(4L << 20)))
                .build();
    }

    static final String ACCEPT_ENCODING = "gzip, deflate, br, zstd"; // exactly what is registered
    // --- end

    // --- snippet:bomb
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
    // --- end

    // --- snippet:post
    static int post(HttpClient client, URI uri, byte[] body, String contentEncoding) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentEncoding != null) {
            request.header("Content-Encoding", contentEncoding);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    // --- end

    private static HttpServer server;
    private static URI base;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ingest", HowToSnippetsTest::ingest);
        server.createContext("/gzipped", exchange -> {
            byte[] gz = gzip("hello".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gz.length);
            exchange.getResponseBody().write(gz);
            exchange.close();
        });
        server.createContext("/bomb", exchange -> {
            byte[] gz = gzip(new byte[20_000_000]);
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gz.length);
            exchange.getResponseBody().write(gz);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(data);
        }
        return bytes.toByteArray();
    }

    @Test
    void javaHttpClientReadsAGzippedResponseAndRefusesABomb() throws Exception {
        assertEquals("hello", new String(fetch(CLIENT, base.resolve("/gzipped")), StandardCharsets.UTF_8));
        SpillgateException e = assertThrows(SpillgateException.class, () -> fetch(CLIENT, base.resolve("/bomb")));
        assertTrue(e.kind() == Kind.OUTPUT_LIMIT || e.kind() == Kind.RATIO_LIMIT, e.kind().toString());
    }

    @Test
    void everyIngressAnswersTheMappedStatus() throws Exception {
        URI uri = base.resolve("/ingest");
        assertEquals(200, post(CLIENT, uri, gzip("{}".getBytes(StandardCharsets.UTF_8)), "gzip"));
        assertEquals(200, post(CLIENT, uri, "plain".getBytes(StandardCharsets.UTF_8), null));
        assertEquals(413, post(CLIENT, uri, gzipBomb(50_000_000), "gzip")); // bomb
        byte[] small = gzip("x".getBytes(StandardCharsets.UTF_8));
        assertEquals(413, post(CLIENT, uri, small, "gzip, gzip, gzip, gzip, gzip, gzip, gzip")); // deep chain
        assertEquals(415, post(CLIENT, uri, small, "compress")); // unknown encoding
        assertEquals(400, post(CLIENT, uri, java.util.Arrays.copyOf(small, small.length - 4), "gzip")); // truncated
        assertEquals(400, post(CLIENT, uri, small, "gzip;;")); // malformed header
        assertEquals(413, post(CLIENT, uri, new byte[3 << 20], null)); // more than maxInput, even unencoded
    }

    @Test
    void streamingToFileLeavesNothingBehindOnARefusal(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.bin");
        decodeToFile(new ByteArrayInputStream(gzip("data".getBytes(StandardCharsets.UTF_8))), "gzip", target);
        assertEquals("data", Files.readString(target));
        Path other = dir.resolve("bomb.bin");
        assertThrows(SpillgateException.class,
                () -> decodeToFile(new ByteArrayInputStream(gzipBomb(50_000_000)), "gzip", other));
        assertTrue(Files.notExists(other));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count()); // only out.bin: no temporary file
        }
    }

    @Test
    void aCanceledTokenStopsTheStreamWithCanceled() throws Exception {
        CancelToken token = new CancelToken();
        token.cancel();
        SpillgateException e = assertThrows(SpillgateException.class,
                () -> drain(new ByteArrayInputStream(gzip(new byte[1000])), "gzip", token));
        assertEquals(Kind.CANCELED, e.kind());
        assertEquals(503, e.kind().httpStatus());
        assertEquals(1000, drain(new ByteArrayInputStream(gzip(new byte[1000])), "gzip", new CancelToken()));
    }

    @Test
    void aCustomCodecRunsBehindTheLimiter() throws Exception {
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8),
                withRot().decodeAll(new ByteArrayInputStream("hELLO".getBytes(StandardCharsets.UTF_8)), "rot"));
        SpillgateException e = assertThrows(SpillgateException.class,
                () -> Decoder.builder(Limits.maxOutput(1 << 20)).codec("Rot", new Rot()).build());
        assertEquals(Kind.INVALID_OPTION, e.kind());
    }

    @Test
    void brotliAndZstdAreRegisteredWithWindowCaps() throws Exception {
        Decoder decoder = withBrotliAndZstd();
        byte[] zstdHi = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x20, 0x02, 0x11, 0x00, 0x00, 'h', 'i'};
        assertEquals("hi", new String(decoder.decodeAll(new ByteArrayInputStream(zstdHi), "zstd"), StandardCharsets.UTF_8));
        byte[] brotliA = {0x03, 0x00, (byte) 0x80, 'a', 0x03}; // window 2^16
        assertEquals("a", new String(decoder.decodeAll(new ByteArrayInputStream(brotliA), "br"), StandardCharsets.UTF_8));
        byte[] brotliW24 = {0x0f, 0x00, (byte) 0x80, 'a', 0x03}; // window 2^24, over the cap of 22 bits
        assertEquals(Kind.MEMORY_LIMIT, assertThrows(SpillgateException.class,
                () -> decoder.decodeAll(new ByteArrayInputStream(brotliW24), "br")).kind());
        // a zstd frame that declares a 32 MiB window is refused before anything is allocated
        byte[] bigWindow = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x00, 0x78, 0x01, 0x00, 0x00};
        SpillgateException e = assertThrows(SpillgateException.class,
                () -> decoder.decodeAll(new ByteArrayInputStream(bigWindow), "zstd"));
        assertEquals(Kind.MEMORY_LIMIT, e.kind());
        assertEquals(503, e.kind().httpStatus());
    }

    @Test
    void aBombOf200MegabytesRunsInASmallHeap() throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() < 128L << 20, "the small heap is not in effect");
        byte[] bomb = gzipBomb(200_000_000);
        SpillgateException e = assertThrows(SpillgateException.class,
                () -> Decoder.decodeAll(new ByteArrayInputStream(bomb), Limits.maxOutput(1 << 20), "gzip"));
        // zeros are also far beyond the ratio limit; whichever check fires first, nothing is delivered
        assertTrue(e.kind() == Kind.OUTPUT_LIMIT || e.kind() == Kind.RATIO_LIMIT, e.kind().toString());
    }

    @Test
    void identityIsBoundedToo() {
        SpillgateException e = assertThrows(SpillgateException.class, () -> Decoder.decodeAll(
                new ByteArrayInputStream(new byte[2000]), Limits.maxOutput(1000)));
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
    }
}
