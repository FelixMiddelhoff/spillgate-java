# Releasing to Maven Central

The workflow `.github/workflows/release.yml` publishes the three artifacts `spillgate`,
`spillgate-zstd` and `spillgate-brotli` (and the parent pom they need) to Maven Central through the
**Central Portal** with `org.sonatype.central:central-publishing-maven-plugin`, from a GitHub
environment that needs your approval. Artifacts are signed with GPG (Central requires it). The
upload goes through a portal **user token**; Central does not offer OIDC trusted publishing for
Maven, so the token and the key are stored as secrets of the protected environment only.

Checked on 2026-11-11 against <https://central.sonatype.org/publish/publish-portal-maven/> (plugin
version 0.12.0; publishing does not work with Maven 4), <https://central.sonatype.org/register/namespace/>,
<https://central.sonatype.org/publish/requirements/> and
<https://central.sonatype.org/publish/requirements/gpg/>. Re-check them before the first release; the
portal UI may have changed. The workflow has never run on GitHub: do the first release from a
throw-away tag if you want to see it fail cheaply (the upload stops at "validated" and nothing is
published until you press Publish in the portal).

## One-time setup (maintainer)

1. **Central Portal account and namespace.** Sign up at <https://central.sonatype.com> with GitHub.
   `io.github.felixmiddelhoff` is verified through GitHub: if it was not created automatically,
   **View Namespaces, Add Namespace**, enter `io.github.felixmiddelhoff`, **Verify Namespace**, copy
   the verification key and create a temporary public repository named exactly like the key under
   your GitHub account (`github.com/FelixMiddelhoff/<key>`); delete it after the namespace shows as
   verified. (Only the GitHub user you signed up with works automatically, not organizations.)
2. **GPG key.** Create a key for signing (`gpg --full-generate-key`; RSA 4096 or ed25519), use the
   **primary key** for signing (Central cannot verify signatures by a sub key), keep the default
   2-year validity and calendar the renewal. Publish the public key to a keyserver Central checks:
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>` (also `keys.openpgp.org` after
   verifying your email there). Export the private key for CI:
   `gpg --armor --export-secret-keys <KEYID>`. Keep a revocation certificate offline.
3. **Portal token.** In the portal: your name, **View Account, Generate User Token**. It gives a
   username and a password (both random strings). Treat both as secrets.
4. **GitHub environment.** Repository **Settings, Environments, New environment**, name
   `maven-central`. Add yourself under *Required reviewers*, so that every publish needs an explicit
   approval, and restrict *Deployment branches and tags* to the pattern `v*`. Add these
   **environment secrets** (not repository secrets, so no other job can read them):
   - `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`: the user token from step 3
   - `GPG_PRIVATE_KEY`: the armored private key from step 2
   - `GPG_PASSPHRASE`: its passphrase
5. **Tag protection.** Add a tag ruleset for `v*` (only maintainers create or delete them).

## What the build needs (already in the poms)

The `release` profile of the parent pom adds `maven-source-plugin`, `maven-javadoc-plugin`
(doclint on, warnings fail the build), `maven-gpg-plugin` (signs in `verify`) and
`central-publishing-maven-plugin` (bundles the whole reactor and uploads it in `deploy`). Normal
builds do not use the profile and need no keys. The unpublished modules (`spillgate-test-support`,
`spillgate-conformance`, `spillgate-howto`) set `maven.deploy.skip` and `skipPublishing`. Metadata
Central requires (name, description, url, license, developers, scm with `connection` and
`developerConnection`) is in the parent and in each module. Check without keys:

```
mvn -B -DskipTests -Prelease -Dgpg.skip package
```

## Each release

1. Update `CHANGELOG.md` (move the Unreleased entries under the version and date) and set the
   version in all poms: `mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false` (it also
   updates the module parents). Commit to `main` and wait for a green CI. Versions must not end in
   `-SNAPSHOT`.
2. Tag exactly that version and push the tag:
   ```
   git tag -a v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```
3. The `release` workflow checks that the tag equals the pom version, runs the whole suite (all
   codecs, no skipped vector, rule coverage), builds the jars with sources and javadoc, installs
   them into a local repository and runs a consumer project against them, and then waits for your
   approval in the `maven-central` environment. After the approval it runs
   `mvn -Prelease -DskipTests deploy`, which signs and uploads the bundle to the portal.
4. The bundle stops at **validated** in the portal (`autoPublish` is false in the workflow, on purpose):
   open <https://central.sonatype.com/publishing/deployments>, look at the validated deployment, and
   press **Publish**. Publishing to Central cannot be undone. (The plugin can publish by itself with
   `-DautoPublish=true`; the workflow does not do that.)
5. Check from a clean directory: add `io.github.felixmiddelhoff:spillgate:0.1.0` to a new project
   and build it (propagation to <https://repo1.maven.org/maven2/io/github/felixmiddelhoff/> can take
   up to some hours after publishing). Check the Javadoc and the signatures.
6. Create the GitHub release from the tag with the changelog text.

The artifacts are published in one bundle, so there is no publish order. The parent pom
(`spillgate-parent`) is part of the bundle because the modules inherit from it.

## Never

Never re-publish a version: Central versions are immutable. A wrong release stays in the repository;
you fix it with the next patch version (you can ask Central Support to deprecate or remove a release
only in exceptional cases). Versions at or above 1.0 need separate, explicit consent.
