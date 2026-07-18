# CLAUDE.md — `be.imgn.inject`

A zero-reflection dependency injection framework for Java 21 (classpath + module
path). Design spec: `docs/superpowers/specs/2026-07-18-imgn-inject-di-framework-design.md`.

## Build

- Build command is **`./mvnw clean verify`**. Never `install`.
- Use the Maven wrapper (`./mvnw`) everywhere — never a globally-installed `mvn`.
- `verify` runs the full quality pipeline: Spotless format → Checkstyle → compile
  with Error Prone + NullAway → tests (Surefire, Mockito agent where used) →
  JaCoCo → Javadoc.

## Documentation

- Documentation **must be written and kept current at all times**, including
  Javadoc on public API.
- Docs must describe the **current** state of the code — nothing else.
- Do **not** reference things that no longer exist or never will. No historical
  asides ("this is because we previously had an issue with X"): if the issue is
  gone, the reference is noise. Delete it.
- When code changes, update its docs in the same change. Stale docs are a defect.

## Testing

- JUnit 6 + AssertJ. Validate all inputs; test the thrown exceptions.
- Use AssertJ **fluent chaining** — one `assertThat(instance)` with chained
  assertions, never twenty repeated `assertThat(instance)` calls on the same
  object.
