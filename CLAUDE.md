# CLAUDE.md — `be.imgn.inject`

A zero-reflection dependency injection framework for Java 21 (classpath + module
path). Design spec: `docs/superpowers/specs/2026-07-18-imgn-inject-di-framework-design.md`.

## Build

- Build command is **`./mvnw clean verify`**. Never `install`.
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
