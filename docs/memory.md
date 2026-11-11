# Memory bounds (SG-R-MEM-03)

spillgate never sizes an allocation from a value in the data (declared sizes, header fields, lengths
in the stream). What it does allocate is bounded by constants or by a window cap you set. The
figures below come from the constants in `core/src` (named in the table) and from the tests named at
the end; "derived" marks a figure that is computed from the code or from the zlib documentation and
not measured by a test.

| Part | Bound | Source |
| --- | --- | --- |
| Input meter buffer, per layer | 4 KiB fetched at a time from the source (`BUFFER_SIZE`) | `InputMeter.java` |
| One decode step, per layer | at most 64 KiB of output per codec call (`MAX_STEP`), whatever buffer size the caller reads with; the guard's scratch array is allocated once per layer | `LayerGuard.java` |
| `Inflater` (gzip, deflate), per layer | **native memory, outside the heap**: the zlib inflate state, about 40 KiB (a 32 KiB window plus bookkeeping; derived from the zlib manual, not measured); released by `Inflater.end()` in `close()`, at the clean end and on every error; no `Cleaner` is relied on | `RawInflate.java` |
| gzip header fields | FNAME and FCOMMENT are refused above 512 bytes; the extra field is skipped, not buffered | `GzipCodec.java` |
| `Content-Encoding` header | at most 1024 bytes, 16 elements, tokens of at most 32 bytes | `Header.java` |
| Streaming (`reader`) | constant: 4 KiB + up to 64 KiB per layer on the heap, whatever the decoded size; about 1.1 MiB for a 16-layer chain (derived) | `Decoder.java` |
| Whole-body function (`decodeAll`) | grows with the bytes actually produced; never preallocated from `maxOutput` or from a declared size. Peak: **up to about three times the bytes produced** while the buffer doubles (old and new array are live together) and at the final trim to the exact size; at most `2^31 - 9` bytes, a larger body is `OUTPUT_LIMIT` | `Decoder.java` |

Budget for `decodeAll`: three times `maxOutput` per concurrent call, plus the per-layer figures
above. Use `reader(...)` and stream when the body does not have to be in memory.

The optional codecs add their decoder window, bounded by the window cap of the codec, not by
`maxOutput`:

| Codec | Default cap | Per stream |
| --- | --- | --- |
| `spillgate-brotli` (`BrotliOptions.withMaxWindowBits`) | 24 window bits (`2^24 - 16` bytes, about 16 MiB); range 10..24 | the window (allocated after the cap check, from the first byte of the stream) plus the decoder's tables: about 0.1 MiB for an ordinary stream, up to about 3.5 MiB for a hostile one (measured live peak about 3.3 MiB: the decode of a table-hog stream survives `-Xmx5m`, not `-Xmx4m`) |
| `spillgate-zstd` (`ZstdOptions.withMaxWindowBytes`) | 8 MiB (`DEFAULT_MAX_WINDOW`); range 1 KiB..1 GiB | the window (grows with the output, never eagerly to the declared size) plus block-sized buffers of at most 128 KiB each (compressed block, regenerated block, literals) |

The cap is checked from the frame or stream header before anything is allocated: a hostile header
allocates nothing. A stream that asks for more is refused as `MEMORY_LIMIT`. Budget concurrent
streams: for example 64 open brotli streams at the default cap are about 1 GiB of heap. Lower
`withMaxWindowBits` / `withMaxWindowBytes` on busy servers (the guide's example uses 22 bits and 4 MiB).

## What the tests measure

- `core/.../CoreMemoryTest` (the module's surefire runs with **`-Xmx96m`**, and the test asserts that
  the small heap is in effect): a 4 GiB bomb under a 100 MiB budget ends with `OUTPUT_LIMIT` after
  exactly 100 MiB, every read is at most 64 KiB, and the heap retained after a full collection grows
  by less than 16 MiB; one-byte reads cost constant memory; `decodeAll` with `maxOutput` from
  `2^40` up to `Long.MAX_VALUE` does not preallocate (an allocation of that size would be an
  `OutOfMemoryError` on a 96 MiB heap); a failed `decodeAll` returns no data; a chain of 16 layers
  can be built and dropped 2000 times with less than 16 MiB retained.
- `zstd` tests run under **`-Xmx64m`** (surefire `default-test`): an eager window allocation from a
  hostile header would fail there; `ZstdHostileTest` checks the window growth and the block buffers.
  The few tests that need a 64 MiB window are tagged `bigheap` and run in a second execution with
  `-Xmx600m`.
- `brotli` tests run under **`-Xmx256m`**; `BrotliMemoryTest` measures the bytes allocated for a
  hostile table-hog header (under 6 MiB in total, about 3.3 MiB live), that nothing is retained
  (under 4 MiB) across 20 failed decodes of a 1 GiB bomb and hostile headers, and that a refused
  window creates no decoder state. See also `brotli/VENDORED.md`.
- `howto/.../HowToSnippetsTest` decodes a 200 MB bomb under **`-Xmx64m`** through the public API.
- The native memory of the `Inflater` is not visible to these heap measurements; it is released by
  `end()` (see `RawInflate`), and the bound above is derived, not measured.

## Memory you still have to plan

- `decodeAll` x concurrency (see the budget above).
- Window caps of brotli and zstd x concurrent streams.
- The compressed body, if you buffer it before decoding (the guide's WebFlux and Netty examples):
  bound it with the server, the aggregator or `withMaxInput`.
