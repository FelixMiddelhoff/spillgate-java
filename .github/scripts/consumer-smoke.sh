#!/usr/bin/env bash
# Builds a clean consumer project against the artifacts in a local Maven repository and runs it.
# Usage: consumer-smoke.sh <local-repo> <version>
# The local repository must already hold spillgate, spillgate-zstd, spillgate-brotli and the parent pom
# (mvn -Dmaven.repo.local=<local-repo> install). Nothing from the source tree is on the class path.
set -euo pipefail

repo="$1"
version="$2"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/src"
cat > "$work/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>consumer</groupId>
  <artifactId>smoke</artifactId>
  <version>1</version>
  <dependencies>
    <dependency><groupId>io.github.felixmiddelhoff</groupId><artifactId>spillgate</artifactId><version>${version}</version></dependency>
    <dependency><groupId>io.github.felixmiddelhoff</groupId><artifactId>spillgate-zstd</artifactId><version>${version}</version></dependency>
    <dependency><groupId>io.github.felixmiddelhoff</groupId><artifactId>spillgate-brotli</artifactId><version>${version}</version></dependency>
  </dependencies>
</project>
POM

cat > "$work/src/Main.java" <<'JAVA'
import io.github.felixmiddelhoff.spillgate.Decoder;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.Limits;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliCodec;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliOptions;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdCodec;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class Main {
    static ByteArrayInputStream gzip(int size) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(new byte[size]);
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    public static void main(String[] args) throws Exception {
        Decoder decoder = Decoder.builder(Limits.maxOutput(1 << 20))
                .codec("br", BrotliCodec.create(BrotliOptions.defaults()))
                .codec("zstd", ZstdCodec.create(ZstdOptions.defaults()))
                .build();
        byte[] zstdHi = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x20, 0x02, 0x11, 0x00, 0x00, 'h', 'i'};
        String hi = new String(decoder.decodeAll(new ByteArrayInputStream(zstdHi), "zstd"), StandardCharsets.UTF_8);
        if (!hi.equals("hi")) {
            throw new AssertionError("zstd: " + hi);
        }
        byte[] brotliA = {0x03, 0x00, (byte) 0x80, 'a', 0x03};
        String a = new String(decoder.decodeAll(new ByteArrayInputStream(brotliA), "br"), StandardCharsets.UTF_8);
        if (!a.equals("a")) {
            throw new AssertionError("brotli: " + a);
        }
        if (decoder.decodeAll(gzip(1000), "gzip").length != 1000) {
            throw new AssertionError("gzip");
        }
        try {
            decoder.decodeAll(gzip(50_000_000), "gzip");
            throw new AssertionError("the bomb was not refused");
        } catch (SpillgateException e) {
            if (e.kind().httpStatus() != 413 || e.kind() == Kind.CORRUPT) {
                throw e;
            }
        }
        System.out.println("consumer smoke ok");
    }
}
JAVA

cd "$work"
mvn -B -ntp -q -Dmaven.repo.local="$repo" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=cp.txt
mkdir classes
javac --release 17 -cp "$(cat cp.txt)" -d classes src/Main.java
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac
java -cp "classes${sep}$(cat cp.txt)" Main
# once more on the module path: the artifacts are named modules
java --module-path "$(cat cp.txt)" --add-modules io.github.felixmiddelhoff.spillgate,io.github.felixmiddelhoff.spillgate.zstd,io.github.felixmiddelhoff.spillgate.brotli --class-path classes Main
