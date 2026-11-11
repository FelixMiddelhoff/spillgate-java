# Security policy

## Reporting a vulnerability

Use GitHub private vulnerability reporting: the repository's **Security** tab,
**Report a vulnerability**. Please do not open a public issue for a bypass.

A bypass is any input that makes the library deliver more than the configured limits, pass an
unsupported or malformed encoding through, throw anything but a `SpillgateException` (an
unexpected `RuntimeException`, `ArrayIndexOutOfBoundsException`, `StackOverflowError`), loop without
bound, leak native memory of the `Inflater`, or use memory that grows with the output instead of
staying bounded.

What to expect:

- acknowledgment within 3 business days
- coordinated disclosure, up to 90 days from the report
- credit in the advisory if you want it
- no bug bounty

Every fixed bypass becomes a permanent conformance vector in the reference repository
([spillgate](https://github.com/FelixMiddelhoff/spillgate)), so all language implementations get it.

## Supported versions

The latest release only.

## Scope

The limits, header parsing, gzip and deflate framing around the JDK `Inflater`, the reader contract,
the own zstd decoder (`spillgate-zstd`) and the window and input handling around the vendored brotli
decoder (`spillgate-brotli`) are in scope. The brotli decoding core is Google's code (see
`brotli/VENDORED.md`): flaws inside it also go upstream (google/brotli), but report them to us too if
the limiter should have contained them. Bugs in the JDK (`Inflater` and zlib included), the compiler
or other libraries are reported upstream; tell us as well if the limiter should have contained them.

The library has no runtime dependencies and uses no `sun.misc.Unsafe`, JNI of its own or reflection.
A memory-safety problem in it is a bug in the JVM and equally a reportable bypass.

Out of scope: the compressed size, timeouts and CPU of code after decoding, which the library does not
claim to bound (see the how-to guide).
