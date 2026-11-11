## What and why

## Checklist
- [ ] Test written first; it failed before the change
- [ ] New or changed rule: spec/SPEC.md, a conformance vector and a test with the ID in its name or a `// Covers:` comment
- [ ] No expected outcome in conformance/manifest.json weakened
- [ ] `mvn -B verify` is green and `mvn -B -Prelease -Dgpg.skip -DskipTests package` builds javadoc without warnings
- [ ] Public API change: docs/how-to.md (snippets in howto/.../HowToSnippetsTest), README.md and CHANGELOG.md updated
- [ ] No dependency added to a published module (the core has none)
