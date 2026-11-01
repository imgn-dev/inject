# CLAUDE.md — `be.imgn.inject`

A zero-reflection dependency injection framework for Java 21 (classpath + module
path). Design spec: `docs/superpowers/specs/2026-07-18-imgn-inject-di-framework-design.md`.

## Build

- Build command is **`./mvnw clean verify`**. Never use `install` to build.
  Use `./mvnw clean install` only to put this project's artifacts into `~/.m2`
  when a project that depends on it updates to them.
- Use the Maven wrapper (`./mvnw`) everywhere — never a globally-installed `mvn`.
- The root POM inherits `be.imgn.parent:parent` and imports `be.imgn.parent:bom`;
  versions and quality gates come from there. Override only what this project
  needs (see spec §10). It also imports `be.imgn.gaia:gaia-bom`: the processor
  modules build on `gaia-processing` and test with `gaia-processing-testing`.
- `verify` runs the full quality pipeline: Enforcer → Spotless format →
  Checkstyle (shared ruleset + `checkstyle-suppressions.xml`) → compile with
  `-Xlint:all -Werror`, Error Prone + NullAway → tests (Surefire) → JaCoCo →
  Javadoc → dependency analysis.
- CI, the release workflow and Maven Central publishing: `docs/releasing.md`.

## Documentation

- Documentation **must be written and kept current at all times**, including
  Javadoc on public API.
- Docs must describe the **current** state of the code — nothing else.
- Do **not** reference things that no longer exist or never will. No historical
  asides ("this is because we previously had an issue with X"): if the issue is
  gone, the reference is noise. Delete it.
- When code changes, update its docs in the same change. Stale docs are a defect.
- `README.md` says only what inject does and how to use it.
- The guide is the `docs` module (`inject-docs`, never deployed): one page per
  feature in `docs/src/main/markdown`, in the navigation order of `pages.txt`.
  Pages link to each other (`scopes.md#custom-scopes`), to the API
  (`api:be.imgn.inject.Injector`) or to the web; the build fails on any other
  link, a broken one, an image or raw HTML.
- `.github/workflows/docs.yml` publishes the guide and the API documentation
  (`scripts/javadoc.sh`, one javadoc run over `inject` and `inject-processor`)
  on GitHub Pages: `api/snapshot/` on every push to `main`, `api/<version>/`
  and `api/latest/` when `release.yml` calls it.
- `WONTDO.md` lists rejected features, with the reason and what to use
  instead. Read it before proposing API; add to it when a feature is rejected.
- Javadoc: `@since <version>` on every new public type or member, from 0.1.2
  on; the API of earlier versions has none. JDK links are offline, from
  `scripts/jdk-21/element-list`, so no build reaches docs.oracle.com.

## Changelog

- `CHANGELOG.md` records every change since 0.1.0, the first release. Keep it
  current at all times.
- One section per release, newest first; changes not yet released go under
  `## Unreleased`. Within a section, list entries in the order the changes
  landed.
- Each entry says what changed for users and references its commits by short
  hash, linked to `https://github.com/imgn-dev/inject/commit/<hash>`. A
  commit cannot name its own hash, so the entry follows in the next commit.
- On release, rename `## Unreleased` to `## X.Y.Z — YYYY-MM-DD` and reference
  the `Release X.Y.Z` commit.

## Testing

- JUnit 6 + AssertJ. Validate all inputs; test the thrown exceptions.
- Use AssertJ **fluent chaining** — one `assertThat(instance)` with chained
  assertions, never twenty repeated `assertThat(instance)` calls on the same
  object.
