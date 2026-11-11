# Contributing

## Run the checks

Java 17 or newer and Maven 3.9 (not Maven 4: the Central publishing plugin does not support it).

```
mvn -B verify                                       # all modules: unit, conformance, differential, memory tests, how-to snippets
mvn -B -pl conformance -am test                     # the conformance corpus alone; no vector may be skipped
mvn -B -pl conformance -am test -Dtest=SpecTraceTest -Dsurefire.failIfNoSpecifiedTests=false  # every rule of the specification is named by a test or vector
mvn -B -Prelease -Dgpg.skip -DskipTests package     # sources and javadoc jars; javadoc must build without warnings
SPILLGATE_FUZZ_ROUNDS=200000 mvn -B -pl zstd,brotli -am test   # the seeded mutation tests with many more rounds
```

The surefire configurations are part of the tests: core runs under `-Xmx96m`, zstd under `-Xmx64m`
(plus a `bigheap` execution), brotli under `-Xmx256m`, the how-to snippets under `-Xmx64m`, so an
allocation sized from a limit or a declared size fails the build. Do not raise them to make a test
pass.

## Rules

- The specification comes first. Behavior that the spec does not describe is not added here: change `spec/SPEC.md` in the reference repository, add vectors there, then sync.
- A test names the rules it protects with `// Covers: SG-R-...` or in its name; `SpecTraceTest` reads that. It is a checklist, not proof that the test asserts everything the rule says. Review the assertions too.
- Test first for every bug. Never weaken a test or a conformance vector to make code pass.
- `conformance/` and `spec/` are copies. Do not edit them here; update them from the reference repository and record the release in `conformance/SOURCE`.
- The core has no dependencies. `org.brotli:dec`, `zstd-jni`, Gson and JUnit are test-scope only and must never be used in `src/main`. `GZIPInputStream`, `InflaterInputStream`, `readAllBytes` on unbounded input and `available()` are not used in `src/main` either (see `docs/design.md`).
- No allocation sized from a value in the data; arithmetic on sizes from the stream uses `long` and `Math.*Exact` or explicit checks; no unchecked narrowing casts of untrusted values.
- `-Xlint:all -Werror` is on. Javadoc is built with doclint (missing tags aside) and must have no warnings.
- Public API changes update `docs/how-to.md`, `README.md` and `CHANGELOG.md` in the same change. The Java snippets of the how-to guide live in `howto/src/test/java/.../HowToSnippetsTest.java` between `// --- snippet:NAME` and `// --- end` markers; change the test first, then paste the block into the guide. Blocks that name a library in a `// needs:` comment are not compiled; check them against that library's current API.
- Java 17 is the baseline (`maven.compiler.release` 17). Do not use newer language features or APIs, and do not raise the baseline without a changelog entry.
- Code is read by people: short methods, a comment that explains why, rule IDs where a rule applies.
