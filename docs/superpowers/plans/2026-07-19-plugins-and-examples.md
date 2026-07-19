# Plugins & Examples Implementation Plan (Plan 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the plugin system with a real `@Log` plugin that injects a `java.util.logging.Logger` (a value not otherwise injectable), and ship an `inject-examples` module that exercises the whole framework end to end on both the classpath and the module path.

**Architecture:** The provisional notification-only plugin SPI (`onGather/onPlan/onGenerate`) is replaced by a purpose-built hook: a plugin inspects an injection site (a field annotated with an annotation meta-annotated `@InjectingAnnotation`) and returns an initializer expression. The processor discovers plugins via `ServiceLoader`, routes injecting-annotation fields to them, and generates `instance.field = <plugin expression>` — no `Context` lookup. `@Log` + `LogPlugin` live in a separate `inject-log` module (a plugin must be compiled before the code it processes). `inject-examples` consumes it.

**Tech Stack:** Java 21, Maven, JavaPoet, JUnit 6 + AssertJ, compile-testing (processor tests only).

## Global Constraints

- Java 21. `./mvnw clean verify` — never `install`; always `./mvnw`; host JDK 24+. Latest stable dependency/plugin versions.
- No fully-qualified names in code (Checkstyle `NoFQNInCode`); `@NullMarked` packages (jspecify `@Nullable`); validate inputs; Spotless (Palantir).
- A plugin lives in a module compiled BEFORE the module whose sources it processes (annotation processing runs during compile). Hence `inject-log` is separate from `inject-examples`.

## The plugin SPI (replaces the provisional one)

```java
package be.imgn.inject.processor.spi;

public interface InjectPlugin {
    /** {@return an initializer expression for the given injection site, or empty to decline} */
    java.util.Optional<com.palantir.javapoet.CodeBlock> fieldInitializer(InjectionSite site);
}

public record InjectionSite(
        javax.lang.model.element.Element element,          // the annotated field
        javax.lang.model.element.TypeElement beanType,     // the bean the field belongs to
        javax.lang.model.type.TypeMirror fieldType,        // the field's declared type
        javax.annotation.processing.ProcessingEnvironment processingEnvironment) {}
```

`GatherContext`/`PlanContext`/`GenerateContext` and the three `onX` hooks are deleted.

## Generated code contract for a plugin field

For a bean `example.Service` with `@Log java.util.logging.Logger logger;`, `LogPlugin` returns
`CodeBlock.of("$T.getLogger($T.class.getName())", Logger, Service)`, and the generated factory emits:
```java
    @Override
    protected void injectFields(Service instance, Context context) {
        instance.logger = Logger.getLogger(Service.class.getName());
    }
```
Plugin fields get NO `fieldInjectionPoints()` entry and no `getInstanceForField` call — they are set directly.

---

## Task 1: Plugin SPI redesign + processor support

**Files:** `inject-processor/.../spi/InjectPlugin.java` (rewrite), `spi/InjectionSite.java` (new), delete `spi/GatherContext.java`/`PlanContext.java`/`GenerateContext.java`; `InjectProcessor.java` (route injecting-annotation fields, discover beans by injecting annotations); `PluginField.java` (new model); `BeanModel.java` + `BeanFactoryGenerator.java` (plugin-field injection); test `InjectingAnnotationTest.java` + a stub plugin in tests; rewrite `RecordingPlugin`/`PluginSpiTest` to the new SPI (or fold into `InjectingAnnotationTest`).

**Deliverable:** a field annotated with a plugin-handled `@InjectingAnnotation` gets a generated `instance.field = <plugin expr>` and no injection point; verified by compile-testing with a stub plugin registered via a test `META-INF/services` file.

- [ ] Rewrite `InjectPlugin` to the `fieldInitializer(InjectionSite)` hook; add `InjectionSite`; delete the three context types.
- [ ] `PluginField(String name, CodeBlock initializer)` record. `BeanModel` gains `List<PluginField> pluginFields`.
- [ ] `InjectProcessor`: (a) also discover beans whose members carry an annotation meta-annotated `@InjectingAnnotation` (iterate the `annotations` set, keep those meta-annotated, `getElementsAnnotatedWith` → enclosing type). (b) In `buildModel`, for each field annotated with an injecting annotation, query each plugin's `fieldInitializer`; first non-empty wins → `PluginField`. A field with an injecting annotation and NO handling plugin → `Messager.ERROR`. (c) Remove the old `onGather/onPlan/onGenerate` calls.
- [ ] `BeanFactoryGenerator`: emit `injectFields`/`fieldInjectionPoints` so context fields keep `getInstanceForField(i, context)` with indices among context fields only, and plugin fields emit `instance.name = <initializer>`; generate `injectFields` when either list is non-empty, `fieldInjectionPoints` only when context fields exist.
- [ ] TDD compile-testing: register a stub plugin (a test `@Marker` `@InjectingAnnotation` + a `StubPlugin` returning a constant initializer) via test resources; assert the generated factory contains `instance.<field> = <expr>` and does NOT list the field in `fieldInjectionPoints`. Update/replace `PluginSpiTest`/`RecordingPlugin`.
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): plugin field-initializer SPI for @InjectingAnnotation`.

## Task 2: `inject-log` module (`@Log` + `LogPlugin`)

**Files:** `inject-log/pom.xml`, `inject-log/src/main/java/be/imgn/inject/log/Log.java`, `LogPlugin.java`, `package-info.java`, `META-INF/services/be.imgn.inject.processor.spi.InjectPlugin`; parent `pom.xml` (add module).

**Deliverable:** a reusable plugin module.

- [ ] Add `<module>inject-log</module>` to the parent.
- [ ] `inject-log/pom.xml`: depends on `inject` (for `@InjectingAnnotation`) + `inject-processor` (for the SPI) + `javapoet`. No `module-info` (it is on the processor path).
- [ ] `@Log` — `@InjectingAnnotation @Retention(RUNTIME) @Target(FIELD) @interface Log {}`.
- [ ] `LogPlugin implements InjectPlugin`: if `site.element().getAnnotation(Log.class) != null`, return `CodeBlock.of("$T.getLogger($T.class.getName())", Logger, ClassName.get(site.beanType()))` where `Logger` = `java.util.logging.Logger`; else empty.
- [ ] Register `LogPlugin` in `META-INF/services/be.imgn.inject.processor.spi.InjectPlugin`.
- [ ] `./mvnw clean verify` green. Commit: `feat(log): add @Log injecting annotation and LogPlugin`.

## Task 3: `inject-examples` — classpath end-to-end

**Files:** `inject-examples/pom.xml`, example beans under `inject-examples/src/main/java/be/imgn/inject/examples/` (a `@Provides` config, `@Inject` beans, a `@Log` field, a `@Named` qualifier, a `Provider<T>` use, a `@Singleton`), and `inject-examples/src/test/java/.../ExamplesClasspathTest.java`; parent `pom.xml` (add module).

**Deliverable:** `Injector.create()` builds the example graph on the classpath, resolves beans, the `@Log` field is a live `Logger`, `@Singleton` returns the same instance, and a `@Named` binding resolves.

- [ ] Add `<module>inject-examples</module>` to the parent.
- [ ] `inject-examples/pom.xml`: depends on `inject` + `inject-log` (for `@Log`); annotation-processor path = `inject-processor` + `inject-log` (so `LogPlugin` is discovered). It is a classpath module — no `module-info` here (Task 4 adds a module-path variant).
- [ ] Example beans: e.g. `GreetingService(@Inject)(@Named("prefix") String prefix, Provider<Clock> clock)`, `@Log Logger log;`, a `@Singleton Registry`, and a `Config` with `@Provides @Named("prefix") static String prefix()` and `@Provides static Clock clock()`.
- [ ] `ExamplesClasspathTest`: `Injector injector = Injector.create();` assert the service resolves, its `@Log` logger is non-null and named after the bean, the singleton is `isSameAs` across two `getInstance`, and the `@Named` value is applied. (These run against the factories the processor generated for this module, discovered via the generated `META-INF/services`.)
- [ ] `./mvnw clean verify` green. Commit: `feat(examples): classpath end-to-end example`.

## Task 4: Module-path demonstration

**Files:** a module-path example — either a dedicated `inject-examples-module` module with a `module-info.java` declaring the required `provides`/`requires`/`uses`, plus a test that runs on the module path; OR a `module-info.java` added to `inject-examples` with a documented module-path run. Prefer a small dedicated module to keep the classpath example clean.

**Deliverable:** the framework demonstrably resolves generated factories on the module path via `provides` directives (the case the processor emits a NOTE for).

- [ ] Create `inject-examples-module` (or add `module-info` to a copy): `module ... { requires be.imgn.inject; requires <log module>; uses be.imgn.inject.context.Factory; uses be.imgn.inject.context.Scope; provides be.imgn.inject.context.Factory with <generated factory FQNs>; }`. The generated factory names come from the processor's compile-time NOTE.
- [ ] A test (run with the module built on the module path) that `Injector.create()` resolves a bean — proving module-path ServiceLoader works with the hand-written `provides`.
- [ ] If a full module-path Surefire run proves too heavy, at minimum: compile the module WITH the `provides` directives green (proving the generated factory names are correct and the module descriptor is valid), and document the manual module-path run in the module README. Make the choice explicit in the commit message.
- [ ] `./mvnw clean verify` green. Commit: `feat(examples): module-path provides demonstration`.

---

## Self-Review Notes

- Spec §6 Plugin SPI (provisional) → Task 1 makes it concrete (`fieldInitializer`); the `@Log` example (§6/§8) → Tasks 2–3; module-path `provides` demo (§7/§8) → Task 4.
- The SPI change deletes the untested notification hooks the review flagged and replaces them with a hook that actually contributes injection — this is the "expect to adapt" the spec anticipated.
- Erasure/qualifier/validation limitations from Plan 2 still apply.
