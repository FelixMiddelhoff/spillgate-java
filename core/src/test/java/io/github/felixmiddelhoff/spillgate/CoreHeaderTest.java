package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The header grammar tables (SPEC section 4), a port of the Rust {@code src/header.rs} unit tests. */
class CoreHeaderTest {
    private static final List<String> ALL = Arrays.asList("gzip", "deflate", "br", "zstd");

    private static List<String> full(int maxLayers, String... values) throws SpillgateException {
        return Header.resolve(Header.parse(values), ALL::contains, maxLayers);
    }

    private static List<String> parse(String... values) throws SpillgateException {
        return full(5, values);
    }

    private static Kind kind(String... values) {
        return assertThrows(SpillgateException.class, () -> parse(values)).kind();
    }

    private static List<String> names(String... values) throws SpillgateException {
        return parse(values);
    }

    private static String rep(String s, int n) {
        return String.join("", Collections.nCopies(n, s));
    }

    private static String[] repArray(String s, int n) {
        String[] out = new String[n];
        Arrays.fill(out, s);
        return out;
    }

    // Covers: SG-R-HDR-12
    @Test
    void sg_r_hdr_12_decoding_order_is_the_reverse_of_the_list() throws Exception {
        assertEquals(Arrays.asList("deflate", "gzip"), names("gzip, deflate"));
        assertEquals(Arrays.asList("br", "gzip"), names("gzip", "br"));
    }

    // Covers: SG-R-HDR-01 SG-R-HDR-03 SG-R-HDR-05 SG-R-HDR-14
    @Test
    void sg_r_hdr_14_equivalent_presentations_give_the_same_result() throws Exception {
        String[][] presentations = {
            {"gzip,br"},
            {"GZIP, BR"},
            {"gzip", "br"},
            {" gzip ", "\tbr\t"},
            {",,gzip,,br,,"},
            {"", "gzip", " ", "br", ""},
            {"gzip,\t,br"},
            {"GzIp", "bR"},
            {null, "gzip", null, "br"},
        };
        for (String[] p : presentations) {
            assertEquals(Arrays.asList("br", "gzip"), names(p), Arrays.toString(p));
        }
    }

    // Covers: SG-R-HDR-01 SG-R-HDR-03 SG-R-HDR-10
    @Test
    void sg_r_hdr_03_an_empty_header_is_the_empty_chain() throws Exception {
        String[][] empties = {
            {""}, {" "}, {","}, {", ,\t,"}, {}, {"", " "}, {"identity, identity"}, {"\t"}, {null}, {" , ,, "},
        };
        for (String[] e : empties) {
            assertTrue(names(e).isEmpty(), Arrays.toString(e));
        }
        assertTrue(Header.parse(null).isEmpty());
    }

    // Covers: SG-R-HDR-07
    @Test
    void sg_r_hdr_07_x_gzip_is_the_only_alias() throws Exception {
        assertEquals(Arrays.asList("gzip"), names("x-gzip"));
        assertEquals(Arrays.asList("gzip"), names("X-GZIP"));
        assertEquals(Arrays.asList("gzip", "gzip"), names("x-gzip, gzip"));
        for (String name : new String[] {"x-compress", "x-deflate", "x-br", "x-zstd"}) {
            assertEquals(Kind.UNSUPPORTED_ENCODING, kind(name), name);
        }
    }

    // Covers: SG-R-HDR-08 SG-R-HDR-09 SG-I-06
    @Test
    void sg_r_hdr_08_unknown_but_well_formed_tokens_are_unsupported() {
        for (String token : new String[] {"compress", "dcb", "dcz", "aes128gcm", "snappy", "deflate64", "bzip2"}) {
            assertEquals(Kind.UNSUPPORTED_ENCODING, kind("gzip, " + token), token);
            assertEquals(Kind.UNSUPPORTED_ENCODING, kind(token + ", gzip"), token);
        }
    }

    // Covers: SG-R-HDR-04 SG-R-HDR-05
    @Test
    void sg_r_hdr_04_malformed_elements_are_refused() {
        String[] evil = {
            "gzip;q=1.0", "gzip;", "\"gzip\"", "gz ip", "gzip br", "gzip\n", "gzip\r\n", "\0gzip", "gzip\0",
            "gézip", "gzip​", "-gzip", ".gzip", "_gzip", "gzip/br", "gzip:", rep("g", 33), "gzip\u000b",
            "gzip\u000c", "gzip\u0085", "gzip, br;x", "ﬁ", "ｇzip", "gzip ", " gzip",
            "gzip\u001b[31m", "gzip\u007f", "😀", "\ud800", "gz\udc00ip", "gzip'", "gzip=", "gzip(", "gzip,x y",
        };
        for (String token : evil) {
            assertEquals(Kind.MALFORMED_ENCODING, kind(token), "{" + token + "}");
            assertEquals(Kind.MALFORMED_ENCODING, kind("gzip", token), "second value {" + token + "}");
        }
    }

    // Covers: SG-R-HDR-04
    @Test
    void sg_r_hdr_04_token_length_and_alphabet_edges() throws Exception {
        String max = rep("a", 32);
        assertEquals(Arrays.asList(max), Header.resolve(Header.parse(new String[] {max}), n -> true, 5));
        assertEquals(Kind.MALFORMED_ENCODING,
                assertThrows(SpillgateException.class, () -> Header.parse(new String[] {rep("a", 33)})).kind());
        for (String token : new String[] {"a", "z", "A", "Z", "0", "9", "a.b_c-d", "9z", "A0._-"}) {
            List<String> got = Header.resolve(Header.parse(new String[] {token}), n -> true, 5);
            assertEquals(Arrays.asList(token.toLowerCase(Locale.ROOT)), got);
        }
        for (char first : "`{@[/:.-_".toCharArray()) {
            assertEquals(Kind.MALFORMED_ENCODING,
                    assertThrows(SpillgateException.class, () -> Header.parse(new String[] {first + "x"})).kind(),
                    "first " + first);
        }
        // every ASCII byte decides the grammar the same way as the regex says
        for (int c = 0; c < 128; c++) {
            String s = "a" + (char) c + "b";
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '_' || c == '-';
            boolean skipped = c == ',' || c == ' ' || c == '\t';
            if (c == ',') {
                assertEquals(2, Header.parse(new String[] {s}).size());
            } else if (c == ' ' || c == '\t') {
                assertThrows(SpillgateException.class, () -> Header.parse(new String[] {s}));
            } else if (ok) {
                assertEquals(1, Header.parse(new String[] {s}).size(), "char " + c);
            } else {
                assertThrows(SpillgateException.class, () -> Header.parse(new String[] {s}), "char " + c);
            }
            assertFalse(skipped && ok);
        }
        assertFalse(Header.isToken(""));
        assertFalse(Header.isToken((String) null));
        assertTrue(Header.isToken("a"));
        assertFalse(Header.isToken("é"));
        assertFalse(Header.isToken(rep("a", 33)));
    }

    // Covers: SG-R-HDR-03
    @Test
    void sg_r_hdr_03_only_space_and_tab_are_trimmed() throws Exception {
        assertEquals(Arrays.asList("gzip"), names(" \t gzip \t "));
        for (String ws : new String[] {"\r", "\n", "\u000b", "\u000c", " ", " ", "\u0085", "　"}) {
            assertEquals(Kind.MALFORMED_ENCODING, kind(ws + "gzip"), "{" + ws + "}");
            assertEquals(Kind.MALFORMED_ENCODING, kind("gzip" + ws), "{" + ws + "}");
        }
    }

    // Covers: SG-R-HDR-02 SG-I-07
    @Test
    void sg_r_hdr_02_the_header_length_cap_counts_bytes() throws Exception {
        String ok = "gzip" + rep(",", 1020);
        assertEquals(1024, ok.length());
        assertEquals(Arrays.asList("gzip"), names(ok));
        assertEquals(Kind.MALFORMED_ENCODING, kind(ok + ","));
        // the joining commas count: 512 + 512 + 4 + 2 commas = 1030
        assertEquals(Kind.MALFORMED_ENCODING, kind(rep(",", 512), rep(",", 512), "gzip"));
        // exactly 1024 with two values: 1019 + 1 comma + 4 = 1024
        String a = rep(",", 1019);
        assertEquals(Arrays.asList("gzip"), names(a, "gzip"));
        assertEquals(Kind.MALFORMED_ENCODING, kind(a, "gzipp"));
        // 600 two-byte characters are 1200 bytes
        assertEquals(Kind.MALFORMED_ENCODING, kind(rep("é", 600)));
        // 3-byte and 4-byte characters
        assertEquals(Kind.MALFORMED_ENCODING, kind(rep("€", 400)));
        assertEquals(Kind.MALFORMED_ENCODING, kind(rep("😀", 300)));
        // 256 four-byte characters are exactly 1024 bytes: within the cap, so a grammar fault
        assertEquals(Kind.MALFORMED_ENCODING, kind(rep("😀", 256)));
        SpillgateException e = assertThrows(SpillgateException.class, () -> parse(rep("x", 1025)));
        assertEquals(1024, e.limit());
        assertEquals(Kind.MALFORMED_ENCODING, e.kind());
        // many empty values: the separators count
        assertEquals(Kind.MALFORMED_ENCODING, kind(repArray("", 1026)));
        assertTrue(names(repArray("", 1025)).isEmpty());
        // a huge value is refused without being scanned
        assertEquals(Kind.MALFORMED_ENCODING, kind(rep("x", 5_000_000)));
        // many values
        assertEquals(Kind.MALFORMED_ENCODING, kind(repArray("a", 600)));
    }

    // Covers: SG-R-HDR-02 SG-R-HDR-04 SG-R-HDR-06 SG-R-HDR-08 SG-R-HDR-11
    @Test
    void evaluation_order_decides_the_outcome_class() {
        // length beats grammar
        SpillgateException e = assertThrows(SpillgateException.class, () -> parse(rep("x;", 600)));
        assertEquals(Kind.MALFORMED_ENCODING, e.kind());
        assertTrue(e.getMessage().contains("long"), e.getMessage());
        // grammar beats count
        String many = rep("gzip, ", 20) + "bad;";
        assertEquals(Kind.MALFORMED_ENCODING, kind(many));
        // grammar, left to right: first fault wins
        e = assertThrows(SpillgateException.class, () -> parse("bad;one, bad;two"));
        assertTrue(e.getMessage().contains("one"));
        assertFalse(e.getMessage().contains("two"));
        // count beats support
        assertEquals(Kind.LAYER_LIMIT, kind(String.join(", ", Collections.nCopies(17, "compress"))));
        // support beats layers
        assertEquals(Kind.UNSUPPORTED_ENCODING, kind(rep("gzip, ", 6) + "compress"));
        // grammar beats support
        assertEquals(Kind.MALFORMED_ENCODING, kind("compress, bad;"));
        // grammar beats layer count in a later value
        assertEquals(Kind.MALFORMED_ENCODING, kind(String.join(", ", Collections.nCopies(17, "gzip")), "bad;"));
    }

    // Covers: SG-R-HDR-06 SG-R-HDR-10 SG-I-07
    @Test
    void sg_r_hdr_06_sixteen_elements_including_identity() throws Exception {
        assertTrue(full(1, String.join(", ", Collections.nCopies(16, "identity"))).isEmpty());
        assertEquals(Kind.LAYER_LIMIT,
                assertThrows(SpillgateException.class,
                        () -> full(16, String.join(", ", Collections.nCopies(17, "identity")))).kind());
        String sixteenGzip = String.join(", ", Collections.nCopies(16, "gzip"));
        assertEquals(16, full(16, sixteenGzip).size());
        assertEquals(Kind.LAYER_LIMIT,
                assertThrows(SpillgateException.class, () -> full(16, sixteenGzip + ", identity")).kind());
        SpillgateException e = assertThrows(SpillgateException.class,
                () -> full(16, String.join(", ", Collections.nCopies(17, "identity"))));
        assertEquals(16, e.limit());
        // empty elements are not counted
        assertEquals(16, Header.parse(new String[] {"," + String.join(",,", Collections.nCopies(16, "gzip"))}).size());
        // several field values count together
        assertEquals(Kind.LAYER_LIMIT, kind(repArray("gzip", 17)));
        assertEquals(16, Header.parse(repArray("gzip", 16)).size());
    }

    // Covers: SG-R-HDR-10 SG-R-HDR-11
    @Test
    void sg_r_hdr_11_the_layer_count_ignores_identity() throws Exception {
        assertEquals(Arrays.asList("gzip", "gzip"), full(2, "identity, gzip, identity, gzip"));
        SpillgateException e = assertThrows(SpillgateException.class, () -> full(2, "gzip, gzip, gzip"));
        assertEquals(Kind.LAYER_LIMIT, e.kind());
        assertEquals(2, e.limit());
        assertThrows(SpillgateException.class, () -> full(1, "gzip, gzip"));
        full(1, "gzip");
        full(1, "identity");
    }

    // Covers: SG-R-HDR-13
    @Test
    void sg_r_hdr_13_error_messages_quote_and_bound_attacker_data() {
        String[] evils = {
            "evil\nSEVERITY=ALERT;", rep("x", 900), "\u001b]0;title\u0007", "a\r\nb\0\u001b[31m",
            "x" + rep("\n", 10) + rep("x", 40), " forged log line ",
        };
        for (String evil : evils) {
            SpillgateException e = assertThrows(SpillgateException.class, () -> parse(evil));
            assertEquals(Kind.MALFORMED_ENCODING, e.kind());
            String shown = e.toString();
            for (char c : shown.toCharArray()) {
                assertFalse(Character.isISOControl(c) || c == ' ' || c == ' ',
                        "control char in " + shown);
            }
            assertTrue(count(shown, 'y') <= 32);
            assertTrue(shown.length() < 300, shown.length() + "");
        }
        // unsupported names are quoted too
        SpillgateException e = assertThrows(SpillgateException.class, () -> parse("gzip, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(Kind.UNSUPPORTED_ENCODING, e.kind());
        assertTrue(e.getMessage().contains("\""));
        // a 100 KiB hostile value
        e = assertThrows(SpillgateException.class, () -> parse(rep("y", 100_000)));
        assertTrue(e.getMessage().length() < 300);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) {
            if (x == c) {
                n++;
            }
        }
        return n;
    }

    // Covers: SG-R-HDR-05
    @Test
    void sg_r_hdr_05_comparison_is_ascii_case_insensitive_without_folding() throws Exception {
        assertEquals(Arrays.asList("deflate", "gzip"), names("GZip, DEFLATE"));
        // the Kelvin sign and the long s are not ASCII and are malformed
        assertEquals(Kind.MALFORMED_ENCODING, kind("K"));
        assertEquals(Kind.MALFORMED_ENCODING, kind("ſ"));
        // the Turkish dotless/dotted i trap: with a Turkish default locale GZIP would not lower-case right
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(Arrays.asList("gzip"), names("GZIP"));
            assertEquals(Arrays.asList("deflate"), names("DEFLATE"));
            List<String> any = Header.resolve(Header.parse(new String[] {"IDENTITY, ZSTD"}), n -> true, 5);
            assertEquals(Arrays.asList("zstd"), any);
            assertEquals(Kind.UNSUPPORTED_ENCODING, kind("INDEX"));
        } finally {
            Locale.setDefault(before);
        }
        // all 26 letters fold
        assertEquals(Arrays.asList("abcdefghijklmnopqrstuvwxyz"),
                Header.resolve(Header.parse(new String[] {"ABCDEFGHIJKLMNOPQRSTUVWXYZ"}), n -> true, 5));
    }

    // Covers: SG-R-HDR-02 SG-R-HDR-06 SG-R-HDR-11 SG-I-07
    @Test
    void sg_r_hdr_through_the_decoder_header_limits() throws Exception {
        Decoder d = decoder(Limits.maxOutput(1 << 20).withMaxLayers(16).withoutRatioLimit());
        String sixteen = chain("pass", 16);
        assertArrayEquals(new byte[] {'x'}, run(d, new byte[] {'x'}, sixteen, 4).bytes);
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[] {'x'}), chain("pass", 17)));
        assertEquals(Kind.LAYER_LIMIT, e.kind());
        assertEquals(16, e.limit());
        assertEquals(Kind.LAYER_LIMIT,
                assertThrows(SpillgateException.class, () -> d.reader(in(new byte[] {'x'}), chain("identity", 17))).kind());
        // 17 elements but only unsupported: the count comes first
        assertEquals(Kind.LAYER_LIMIT,
                assertThrows(SpillgateException.class, () -> d.reader(in(new byte[] {'x'}), chain("compress", 17))).kind());
        // too long
        assertEquals(Kind.MALFORMED_ENCODING,
                assertThrows(SpillgateException.class, () -> d.reader(in(new byte[] {'x'}), "pass" + rep(" ", 1021))).kind());
        d.reader(in(new byte[] {'x'}), "pass" + rep(" ", 1020)).close();
        // default MaxLayers is 5, identity does not count
        Decoder d5 = decoder(Limits.maxOutput(1 << 20).withoutRatioLimit());
        d5.reader(in(new byte[] {'x'}), "pass,pass,pass,pass,pass,identity,identity").close();
        e = assertThrows(SpillgateException.class, () -> d5.reader(in(new byte[] {'x'}), "pass,pass,pass,pass,pass,pass"));
        assertEquals(Kind.LAYER_LIMIT, e.kind());
        assertEquals(5, e.limit());
        Decoder d1 = decoder(Limits.maxOutput(1 << 20).withMaxLayers(1).withoutRatioLimit());
        assertEquals(Kind.LAYER_LIMIT,
                assertThrows(SpillgateException.class, () -> d1.reader(in(new byte[] {'x'}), "pass, pass")).kind());
    }

    // Covers: SG-R-HDR-04 SG-R-HDR-08 SG-R-API-03 SG-I-06
    @Test
    void sg_r_hdr_04_malformed_and_unsupported_are_construction_errors_and_the_source_stays_untouched() throws IOException {
        Decoder d = decoder(loose());
        Object[][] cases = {
            {"gzip;q=1", Kind.MALFORMED_ENCODING},
            {"\"gzip\"", Kind.MALFORMED_ENCODING},
            {"pass, ba d", Kind.MALFORMED_ENCODING},
            {"pass\n", Kind.MALFORMED_ENCODING},
            {"compress", Kind.UNSUPPORTED_ENCODING},
            {"pass, compress", Kind.UNSUPPORTED_ENCODING},
            {"compress, pass", Kind.UNSUPPORTED_ENCODING},
        };
        for (Object[] c : cases) {
            FailAfter source = new FailAfter(new byte[0], () -> new IOException("must not be read"));
            SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(source, (String) c[0]));
            assertEquals(c[1], e.kind(), (String) c[0]);
            assertEquals(0, source.calls.get(), c[0] + ": the source must stay untouched");
        }
        Chunked source = new Chunked("data".getBytes(), 1);
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(source, "pass", "compress"));
        assertEquals(Kind.UNSUPPORTED_ENCODING, e.kind());
        assertEquals(0, source.calls.get());
        IOException ioe = assertThrows(IOException.class, () -> d.decodeAll(in("data".getBytes()), "pass, compress"));
        assertEquals(Kind.UNSUPPORTED_ENCODING, ((SpillgateException) ioe).kind());
    }

    // Covers: SG-R-HDR-12 SG-R-HDR-01
    @Test
    void sg_r_hdr_12_decoding_order_through_the_decoder() {
        Decoder d = decoder(loose());
        byte[] input = {10, 20, 30};
        // last token = layer 1: "up, dbl" applies dbl first: (x*2)+1
        assertArrayEquals(new byte[] {21, 41, 61}, run(d, input, h("up, dbl"), 16).bytes);
        // "dbl, up" applies up first: (x+1)*2
        assertArrayEquals(new byte[] {22, 42, 62}, run(d, input, h("dbl, up"), 16).bytes);
        // several field values are the same list
        assertArrayEquals(new byte[] {21, 41, 61}, run(d, input, h("up", "dbl"), 16).bytes);
        byte[] small = b(1, 2, 200);
        assertArrayEquals(b(4, 6, 146), run(d, small, h("dbl, up"), 8).bytes);
        assertArrayEquals(b(3, 5, 145), run(d, small, h("up, dbl"), 8).bytes);
        assertArrayEquals(b(5, 7, 147), run(d, small, h("up, up, up, dbl"), 8).bytes);
    }

    // Covers: SG-R-HDR-14 SG-R-HDR-03 SG-R-HDR-05
    @Test
    void sg_r_hdr_14_equivalent_presentations_decode_the_same() {
        Decoder d = decoder(loose());
        byte[] input = {1, 2, 3};
        Outcome reference = run(d, input, h("up,dbl"), 16);
        assertArrayEquals(new byte[] {3, 5, 7}, reference.bytes);
        String[][] presentations = {
            {"UP, DBL"}, {"up", "dbl"}, {" up ", "\tdbl\t"}, {",,up,,dbl,,"}, {"", "up", " ", "dbl", ""},
            {"identity", "up", "identity", "dbl", "identity"}, {"Up", "dBl"},
        };
        for (String[] p : presentations) {
            assertEquals(reference, run(d, input, p, 16), Arrays.toString(p));
        }
    }

    // Covers: SG-R-HDR-03 SG-R-HDR-10 SG-R-CDC-10
    @Test
    void sg_r_hdr_03_the_empty_header_is_the_identity_chain() {
        Decoder d = decoder(loose());
        String[][] empties = {{}, {""}, {" "}, {","}, {"identity"}, {"identity, identity"}, {", ,\t,"}};
        for (String[] p : empties) {
            Outcome o = run(d, "payload".getBytes(), p, 3);
            assertArrayEquals("payload".getBytes(), o.bytes, Arrays.toString(p));
            assertNull(o.kind, Arrays.toString(p));
        }
    }

    // Covers: SG-R-HDR-13 SG-I-05
    @Test
    void sg_r_hdr_13_errors_through_the_decoder_carry_bounded_quoted_text() {
        Decoder d = decoder(loose());
        String evil = "evil\nSEVERITY=ALERT;" + rep("x", 500) + "\u001b]0;title\u0007";
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[0]), evil));
        String shown = e.toString();
        assertTrue(shown.chars().noneMatch(Character::isISOControl), shown);
        assertTrue(count(shown, 'y') <= 32);
        assertTrue(shown.length() < 300);
        e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[0]), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(Kind.UNSUPPORTED_ENCODING, e.kind());
        assertTrue(e.getMessage().contains("\""));
        assertEquals("\"\\xc3\\xa9\\x0a\"", SpillgateException.quote("é\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("\"q\\\"b\\\\\"", SpillgateException.quote("q\"b\\".getBytes()));
        assertTrue(count(SpillgateException.quote(filled(100, 'z')), 'z') <= 32);
    }

    // Covers: SG-I-05 SG-I-06 SG-R-HDR-13
    @Test
    void arbitrary_header_text_is_a_typed_error_or_a_decoder() throws Exception {
        Decoder d = decoder(out(100));
        Rng rng = new Rng(42);
        char[] alphabet = ("abcdefghijklmnopqrstuvwxyzABCXYZ0123456789,;= \t\r\n\0\"'\\.-_/é​\u001bｇﬁ")
                .toCharArray();
        for (int round = 0; round < 3000; round++) {
            StringBuilder text = new StringBuilder();
            for (long i = rng.below(60); i > 0; i--) {
                text.append(alphabet[(int) rng.below(alphabet.length)]);
            }
            StringBuilder second = new StringBuilder();
            for (long i = rng.below(10); i > 0; i--) {
                second.append(alphabet[(int) rng.below(alphabet.length)]);
            }
            try (SpillgateInputStream s = d.reader(in("data".getBytes()), text.toString(), second.toString())) {
                drain(s, 16);
            } catch (SpillgateException e) {
                assertTrue(e.kind() == Kind.MALFORMED_ENCODING || e.kind() == Kind.UNSUPPORTED_ENCODING
                        || e.kind() == Kind.LAYER_LIMIT, e.kind() + "");
                String shown = e.toString();
                assertTrue(shown.chars().noneMatch(Character::isISOControl), shown);
                assertTrue(shown.length() < 400);
            }
        }
    }
}
