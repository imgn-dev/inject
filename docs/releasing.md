# CI and Releasing

## CI

`.github/workflows/ci.yml` runs `./mvnw clean verify` on every push to `main`,
every pull request, and on demand, against JDK 25 and JDK 26. The code targets
Java 21, but the build needs JDK 24+ (the test JVMs run with
`--sun-misc-unsafe-memory-access=allow`). The newest JDK is built too because
Error Prone reflects into javac internals and is the first thing to break on a
new release. When a build fails, the Surefire reports and the invoker
integration-test logs are uploaded as an artifact.

The `inject-maven-plugin` integration tests run child Maven builds whose
`src/it/settings.xml` mirrors every remote repository to `~/.m2`, so they never
reach the network. Each IT project pins the plugins of its lifecycle (resources,
compiler, surefire, jar) to the versions the main build uses, through invoker
filtering (`@maven-surefire-plugin.version@`, …). The main build therefore
downloads everything the child builds need, which is what lets them pass on a
runner that starts with an empty `~/.m2`. A plugin added to an IT project must
be pinned the same way.

`.github/dependabot.yml` opens weekly pull requests for Maven dependencies and
GitHub Actions.

## Releasing

Manual, via the **Release** workflow in GitHub Actions, from `main`. It takes
the release version and the next development version, both
`MAJOR.MINOR.PATCH` (`-SNAPSHOT` is added to the next one), and then:

1. checks both versions, and that the next one is later;
2. sets the release version with `versions:set`;
3. runs `clean verify`, integration tests included;
4. deploys with `-Prelease`: from `be.imgn.parent:parent`, this rejects
   `SNAPSHOT` versions and dependencies, attaches sources and javadoc, signs
   with GPG, and uploads the bundle with `central-publishing-maven-plugin`;
5. commits `Release X`, tags `vX`, sets the next `-SNAPSHOT` version, commits
   `Start Y-SNAPSHOT`, and pushes both commits and the tag;
6. creates the GitHub release with generated notes.

`autoPublish` is false: the deployment waits in the
[Central portal](https://central.sonatype.com/publishing/deployments) until a
human presses **Publish**. One deployment holds four components:

| Artifact | Published files |
|---|---|
| `be.imgn.inject:inject-parent` | POM |
| `be.imgn.inject:inject` | jar, sources, javadoc |
| `be.imgn.inject:inject-processor` | jar, sources, javadoc |
| `be.imgn.inject:inject-maven-plugin` | jar, sources, javadoc |

`inject-examples`, `inject-examples-module`, and the `@Log` modules
`inject-log` and `inject-log-processor`, which exercise the processor plugin
SPI, are built but excluded from the bundle (`excludeArtifacts` in the root
POM).

`scripts/publish.sh <releaseVersion> <nextVersion>` walks through the one-time
setup below (signing key, keyserver, repository secrets, a vault copy of the
key), then starts the workflow and watches it. Every step detects what is
already done, so it is safe to re-run.

### Releasing from your machine

`scripts/release.sh [--dry-run] <releaseVersion> <nextVersion>` does what the
Release workflow does, locally, with no GitHub Actions minutes: the same
version checks, `versions:set`, `clean verify`, `-Prelease deploy`, release
commit and tag, next `-SNAPSHOT` commit, push, and a GitHub release through
`gh`, which uses no Actions minutes.

```bash
scripts/release.sh --dry-run 0.1.0 0.2.0   # every prompt and the same build; upload nothing
scripts/release.sh 0.1.0 0.2.0             # the release
```

It needs JDK 24+, `curl`, a clean tree on `main` in sync with `origin`, and a
free `vX` tag. It walks through choosing the signing key, the same step as
`publish.sh`, and prompts for the Central token and the key passphrase without
echoing them. They are kept only in the Maven process's environment:
`scripts/release-settings.xml` reads the token from `CENTRAL_TOKEN_USERNAME`
and `CENTRAL_TOKEN_PASSWORD`, and `maven-gpg-plugin` reads
`MAVEN_GPG_PASSPHRASE`. Export those three beforehand to skip the prompts.

Before building, the script checks both secrets. It asks the Central portal
whether the release is already published, a call that needs a valid token: a
refused token is asked again, up to three times. If the portal cannot be
reached, it warns and goes on, and the upload will tell. It then signs a
throwaway text with the key and the passphrase, as `maven-gpg-plugin` will: a
wrong passphrase is asked again, up to three times. A wrong token or
passphrase from the environment stops the script at once. The parent signs
with `--pinentry-mode loopback`, so gpg-agent never asks for the passphrase
itself.

`--dry-run` goes through every check, prompt and question of a release, in the
same order, so each one can be foreseen. It runs the deployment's build with
`verify` instead of `deploy`: everything is built, verified and signed, and
nothing is uploaded. It then restores the POMs, and prints what a release
would upload, commit, tag, push and create instead of doing it. Run it first.
If the build fails before the upload, the POMs are restored too. Once the
upload has succeeded, nothing is rolled back.

The `be.imgn` releases so far were signed by key `81F8496A2F65610C`
(RSA 4096, *The IMGN developers &lt;imgn@imgn.be&gt;*, on keyserver.ubuntu.com).
Its private half exists only as the `GPG_PRIVATE_KEY` secret of
`imgn-dev/imgn-parent`, which GitHub never returns, and in the vault bundle
`publish.sh` offered to write. To reuse it, `gpg --import` that bundle
before running the script. Otherwise the script generates a new key and sends
it to the keyserver: Central accepts any key it can find there, so a new key
does not affect the namespace. A Central token can likewise be regenerated at
any time from the portal's account page.

Stay on Maven 3.9.16, the wrapper's version. With Maven 3.10.0,
`central-publishing-maven-plugin` 0.11.0 bundles local-repository metadata,
and Central rejects the deployment.

### Required repository secrets

| Secret | What it is |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | Central Portal user token, name half |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal user token, secret half |
| `GPG_PRIVATE_KEY` | ASCII-armoured private key, exported with `gpg --armor --export-secret-keys` |
| `GPG_PASSPHRASE` | Passphrase for that key |

The public half of the signing key must be on a keyserver, or Central rejects
the bundle. The key and token that publish `be.imgn.parent` can be reused:
both projects publish under the same `be.imgn` namespace.

### Before the first publish

- **Namespace.** `be.imgn` must show as verified in the Central portal; it
  covers `be.imgn.inject`.
- **Developers and licence.** The POMs inherit `<developers>` (The IMGN
  developers) and the Apache-2.0 licence from `be.imgn.parent:parent`. `LICENSE`
  is the same file as the parent's, copyright The IMGN developers.
- **Workflow permissions.** The release pushes two commits and a tag to `main`
  as `github-actions[bot]`. *Settings → Actions → General → Workflow
  permissions* must allow read and write, and branch protection on `main`, if
  any, must let the bot push.
- **Secrets.** Set the four above, with `scripts/publish.sh` or by hand.
