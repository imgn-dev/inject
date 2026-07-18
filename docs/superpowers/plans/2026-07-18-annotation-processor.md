# Annotation Processor Implementation Plan (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `inject-processor` — a JavaPoet-based annotation processor that generates one zero-reflection `Factory` subclass per injectable class (and per `@Provides` method), registers them for `ServiceLoader`, validates the dependency graph at compile time, and exposes a plugin SPI. Generated code targets the current runtime API from Plan 1 (post-refactor).

**Architecture:** `@SupportedAnnotationTypes("*")` processor running a 4-stage pipeline (Gather → Plan → Generate → Write). Gather builds an intermediate model from `@Inject`/`@Provides`/meta-annotated elements; Plan validates (missing deps, duplicates, cycles) and assigns scopes/qualifiers/exposed keys; Generate emits factories via `BeanFactoryGenerator`/`ProvidesFactoryGenerator` + `ScopeBinding` into the bean's package; Write flushes files and `META-INF/services` registrations. Plugins are discovered via `ServiceLoader<InjectPlugin>` with default-no-op hooks per stage.

**Tech Stack:** Java 21, Maven, JavaPoet (`com.palantir.javapoet`), `com.google.testing.compile:compile-testing` for processor tests, JUnit 6 + AssertJ for plain unit tests.

## Global Constraints

- Java 21. Build `./mvnw clean verify` — never `install`; always `./mvnw`. Host JDK 24+ (Surefire flag). Use LATEST stable plugin/dependency versions (the project requires currency; `versions-maven-plugin` is configured).
- No fully-qualified names in code (Checkstyle `NoFQNInCode`); `@NullMarked` packages (jspecify `@Nullable` for nullable); validate inputs (`Objects.requireNonNull`); Spotless (Palantir) auto-formats.
- Generated factories live in the **bean's own package**, extend `be.imgn.inject.context.Factory`, and override its `protected` methods (protected is accessible to subclasses across packages). They call the `protected final` helpers `getInstanceForConstructor/Method/Field` and `getProviderForConstructor/Method/Field`.
- **Generated-type naming:** `$` + enclosing-class chain joined by `$` + `$$<Kind>`. `Bean` → `$Bean$$Factory`; `Bean.SubBean` → `$Bean$SubBean$$Factory`; scope for `@Singleton` → `$Singleton$$Scope`; `@Provides provideX` on `Config` → `$Config$provideX$$Factory` (overloads add `$<index>`).
- **Type tokens are erased to `Class`:** generated `Parameter`/field types use `<RawType>.class` literals (a `Class` is a `java.lang.reflect.Type`). `Provider<Foo>` dependencies set `isProvider=true` and use `Foo.class`.
- **Validation:** dependency needs a factory — `@Inject`/`@Provides` → generated; else must have an accessible no-arg constructor → default factory generated; else **compile error**. Also compile-error on: missing satisfiable factory, duplicate exposed key, dependency cycle, `@Provides` on a non-method, a pre-existing hand-authored `META-INF/services/be.imgn.inject.context.Factory` on the source path.
- **Module path:** processor cannot edit the consumer `module-info.java`; it emits a compile-time NOTE listing the exact `provides be.imgn.inject.context.Factory with …` directives. Classpath uses the generated `META-INF/services` files.

## Runtime API the generated code targets (from Plan 1, verbatim signatures)

`be.imgn.inject.context.Factory<T>`: `protected Factory(Class<T>)`; overridable `protected ConstructorInjectionPoint constructorInjectionPoint()`, `List<MethodInjectionPoint> methodInjectionPoints()`, `List<FieldInjectionPoint> fieldInjectionPoints()`, `Optional<Class<? extends Annotation>> scope()`, `List<Key<?>> exposedKeys()`; `protected abstract T createInstance(Context)`; overridable `void injectMethods(T,Context)` / `injectFields(T,Context)`; `protected final <D> D getInstanceForConstructor(int,Context)` / `<D> Provider<D> getProviderForConstructor(int,Context)` and `…ForMethod(int methodIndex,int paramIndex,Context)` / `…ForField(int fieldIndex,Context)`.
`be.imgn.inject.context.ConstructorInjectionPoint(Factory<?>, List<Parameter>, AnnotationMetadata)`; `MethodInjectionPoint(Factory<?>, String methodName, List<Parameter>, AnnotationMetadata)`; `FieldInjectionPoint(Factory<?>, String fieldName, Class<?> fieldType, @Nullable Qualifier, AnnotationMetadata, boolean isProvider)` (+ convenience ctors without qualifier/isProvider).
`be.imgn.inject.context.Parameter(String name, Type type, @Nullable Qualifier, AnnotationMetadata, boolean isProvider)` (+ convenience ctors `(name,type)`, `(name,type,qualifier)`, `(name,type,isProvider)`, `(name,type,qualifier,isProvider)`).
`be.imgn.inject.context.AnnotationMetadata.empty()`. `be.imgn.inject.Qualifier(Class<? extends Annotation>, Map<String,Object>)`. `be.imgn.inject.Key.of(Class)`.

---

## File Structure

- `inject-processor/pom.xml` — module (depends on `inject`; JavaPoet; test: compile-testing, junit, assertj).
- `inject-processor/src/main/java/be/imgn/inject/processor/InjectProcessor.java` — `AbstractProcessor` entry, runs the pipeline.
- `.../processor/GeneratedNames.java` — naming rules.
- `.../processor/model/*.java` — `BeanModel`, `ProvidesModel`, `ParameterModel`, `FieldModel`, `MethodModel`, `DependencyModel` (intermediate model).
- `.../processor/GatherStage.java`, `PlanStage.java` — pipeline stages (or fold into `InjectProcessor` initially, split as they grow).
- `.../processor/BeanFactoryGenerator.java`, `ProvidesFactoryGenerator.java`, `ScopeBindingGenerator.java` — JavaPoet emitters.
- `.../processor/spi/InjectPlugin.java` (+ `GatherContext`/`PlanContext`/`GenerateContext` param types) — plugin SPI.
- `inject-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor` — registers `InjectProcessor`.
- `inject-processor/src/test/java/...` — compile-testing tests + `GeneratedNames` unit test.
- Parent `pom.xml` — add `<module>inject-processor</module>` and manage the new dependency versions.

---

## Task 1: Module skeleton + no-op processor

**Files:** `pom.xml` (add module + dep mgmt), `inject-processor/pom.xml`, `InjectProcessor.java`, `META-INF/services/javax.annotation.processing.Processor`, test `InjectProcessorSmokeTest.java`.

**Deliverable:** the module builds; a no-op `@SupportedAnnotationTypes("*")` `InjectProcessor` (SourceVersion RELEASE_21, `process()` returns `false`) runs over a trivial class without error, verified by compile-testing.

- [ ] Add `<module>inject-processor</module>` to parent `<modules>`; add dependency-management entries for JavaPoet (`com.palantir.javapoet:javapoet`, latest stable) and `com.google.testing.compile:compile-testing` (latest stable, test scope). Use the latest stable versions (check `versions:display-dependency-updates`).
- [ ] Write `inject-processor/pom.xml`: parent = `inject-parent`; depends on `inject` (compile) + `javapoet`; test deps `junit-jupiter`, `assertj-core`, `compile-testing`. It is a classpath annotation processor — no `module-info.java`.
- [ ] Write `InjectProcessor extends AbstractProcessor` with `@SupportedAnnotationTypes("*")`, `getSupportedSourceVersion()` = `RELEASE_21`, `process()` returning `false` (no-op for now).
- [ ] Register it in `META-INF/services/javax.annotation.processing.Processor`.
- [ ] Test (compile-testing): compile a trivial `@Inject`-free class with the processor applied → assert `Compilation` succeeded with no errors.
- [ ] `./mvnw clean verify` green. Commit: `build: scaffold inject-processor module with no-op processor`.

## Task 2: Generated-type naming

**Files:** `GeneratedNames.java`, test `GeneratedNamesTest.java`.

**Deliverable:** pure functions producing the factory/scope/provides simple names from an element's nesting, unit-tested with plain JUnit+AssertJ (no compile-testing).

- [ ] TDD `GeneratedNamesTest`: `factoryName("Bean")` → `$Bean$$Factory`; nested `Bean.SubBean` → `$Bean$SubBean$$Factory`; `A.B.C` → `$A$B$C$$Factory`; `scopeName("Singleton")` → `$Singleton$$Scope`; `providesFactoryName("Config","provideX",0)` → `$Config$provideX$$Factory` and with a duplicate index `1` → `$Config$provideX$1$$Factory`. Model the input as the enclosing simple-name chain (e.g. `List<String>` of `["Bean","SubBean"]`) so the util needs no `javax.lang.model` types (keeps it unit-testable).
- [ ] Implement `GeneratedNames` accordingly.
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): add generated-type naming`.

## Task 3: BeanFactoryGenerator — constructor injection (core)

**Files:** model classes as needed, `GatherStage`/gather logic, `BeanFactoryGenerator.java`, wire into `InjectProcessor.process()`, test `ConstructorInjectionTest.java`.

**Deliverable:** for a class with an `@Inject` constructor whose parameters are plain (unqualified, non-Provider) types, generate the factory into the bean's package. Verified by compile-testing asserting the generated source matches the expected contract AND the whole thing compiles.

**Contract — for**
```java
package com.example;
import be.imgn.inject.Inject;
public class Greeter {
    private final String name;
    @Inject public Greeter(String name) { this.name = name; }
}
```
**generate `com.example.$Greeter$$Factory`:**
```java
package com.example;

import be.imgn.inject.context.AnnotationMetadata;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.context.Parameter;
import java.util.List;

public final class $Greeter$$Factory extends Factory<Greeter> {
    public $Greeter$$Factory() {
        super(Greeter.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(
                this, List.of(new Parameter("name", String.class)), AnnotationMetadata.empty());
    }

    @Override
    protected Greeter createInstance(Context context) {
        return new Greeter(getInstanceForConstructor(0, context));
    }
}
```
- [ ] TDD `ConstructorInjectionTest` (compile-testing): compile `Greeter`, assert the generated file `com.example.$Greeter$$Factory` is generated with source equivalent to the contract (use compile-testing's `generatedSourceFile(...)` + `hasSourceEquivalentTo`, or assert it `compilesWithoutError` and contains the key statements). Prefer source-equivalence where practical.
- [ ] Implement the Gather step (find the injectable constructor — the `@Inject` one, or the sole public/no-arg one), build a minimal `BeanModel` (package, simple-name chain, ordered parameters of name + erased `Class`), and `BeanFactoryGenerator` producing the contract via JavaPoet. Wire into `process()` and write via `Filer`. Use `GeneratedNames`. Multiple parameters → multiple `Parameter`s and `getInstanceForConstructor(i, context)` args in order.
- [ ] Add a two-parameter test case to be sure indices/ordering are right.
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): generate bean factories for constructor injection`.

## Task 4: Field & method injection

**Files:** extend model + `BeanFactoryGenerator`, test `FieldAndMethodInjectionTest.java`.

**Deliverable:** `@Inject` fields and `@Inject` methods generate `injectFields`/`injectMethods` overrides plus `fieldInjectionPoints()`/`methodInjectionPoints()`, using `getInstanceForField`/`getInstanceForMethod`. Package-private members are legal because the generated class is in the bean's package.

**Contract sketch — for a field `@Inject String greeting;` and method `@Inject void setClock(Clock clock)`** the factory additionally emits:
```java
    @Override
    protected List<FieldInjectionPoint> fieldInjectionPoints() {
        return List.of(new FieldInjectionPoint(this, "greeting", String.class, AnnotationMetadata.empty()));
    }

    @Override
    protected void injectFields(Bean instance, Context context) {
        instance.greeting = getInstanceForField(0, context);
    }

    @Override
    protected List<MethodInjectionPoint> methodInjectionPoints() {
        return List.of(new MethodInjectionPoint(
                this, "setClock", List.of(new Parameter("clock", Clock.class)), AnnotationMetadata.empty()));
    }

    @Override
    protected void injectMethods(Bean instance, Context context) {
        instance.setClock(getInstanceForMethod(0, 0, context));
    }
```
- [ ] TDD compile-testing tests for field-only, method-only, and combined (constructor + field + method) beans. Assert generated source and that it compiles.
- [ ] Implement field/method gather + generation. Fields assigned directly (`instance.field = …`); methods called with resolved args in order. Multiple fields/methods indexed correctly.
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): generate field and method injection`.

## Task 5: Qualifiers, scopes, exposedKeys, Provider<T> injection

**Files:** extend model + generator, test `QualifierScopeProviderTest.java`.

**Deliverable:**
- A parameter/field annotated with a **qualifying annotation** (user annotation meta-annotated `@QualifyingAnnotation`, e.g. `@Named("db")`) generates a `Qualifier` argument: `new Parameter("x", X.class, new Qualifier(Named.class, java.util.Map.of("value", "db")))`. Support scalar attributes (String, primitive wrappers, `Class`, enum constants). A qualified **bean/exposed key** (bean itself carries a qualifier — rare; primarily `@Provides`) or a qualified dependency drives `exposedKeys()` only when the bean is itself qualified.
- A bean carrying a **scoping annotation** (meta-annotated `@ScopingAnnotation`, e.g. `@Singleton`) generates `@Override protected Optional<Class<? extends Annotation>> scope() { return Optional.of(Singleton.class); }`.
- A dependency declared as `Provider<Foo>` sets `isProvider=true` with type `Foo.class` and the constructor/field/method call uses `getProviderForConstructor/Field/Method` (injecting the provider) instead of `getInstance…`.

- [ ] TDD compile-testing: `@Named`-qualified constructor param → correct `Qualifier` in `Parameter`; `@Singleton` bean → `scope()` override; `Provider<Foo>` param → `isProvider=true` + `getProviderForConstructor`. Include a qualifier with a non-string attribute (e.g. an enum or int) to exercise attribute rendering, or explicitly scope to string/primitive/Class/enum and NOTE arrays/annotation-valued attributes as unsupported (compile error or documented limitation) — pick one and make it explicit.
- [ ] Implement qualifier detection (walk annotation mirrors for one meta-annotated `@QualifyingAnnotation`; extract attribute values to a `Map` literal), scope detection (one meta-annotated `@ScopingAnnotation`), and `Provider<T>` unwrapping (detect `be.imgn.inject.Provider<X>`, set inner type + isProvider). Emit `exposedKeys()` override when the bean itself is qualified.
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): generate qualifiers, scopes, and Provider injection`.

## Task 6: Undeclared-dependency factories + compile-time validation

**Files:** `PlanStage` (dependency graph + validation), default-ctor factory generation, tests `ValidationTest.java`, `DefaultFactoryTest.java`.

**Deliverable:**
- A dependency type that is neither `@Inject` nor `@Provides` but has an accessible no-arg constructor gets a generated default factory (same shape as Task 3, empty parameter list, `createInstance` = `new Dep()`).
- Compile errors (via `Messager.ERROR`, attached to the offending element) for: a dependency with no factory and no accessible no-arg constructor; a dependency **cycle** (build the graph over generated factories and reject); a duplicate exposed key among generated factories; `@Provides` on a non-method (if reachable — `@Provides` `@Target(METHOD)` already blocks most, but validate defensively).

- [ ] TDD compile-testing: (a) a bean depending on a plain class with a default ctor → both factories generated, compiles; (b) a bean depending on a class with only a non-default ctor and no `@Inject` → `compilation.failed()` with a clear message naming the type; (c) a 2-bean cycle `A(@Inject B)`, `B(@Inject A)` → failed with a message naming the cycle; (d) two `@Provides` for the same key → failed duplicate-binding message.
- [ ] Implement default-factory generation for undeclared deps, and the Plan-stage graph build + cycle/missing/duplicate detection with `Messager` errors. Prefer emitting all detected errors (don't stop at the first).
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): default-ctor factories and compile-time graph validation`.

## Task 7: @Provides factories + ScopeBinding

**Files:** `ProvidesFactoryGenerator.java`, `ScopeBindingGenerator.java`, tests `ProvidesTest.java`, `ScopeBindingTest.java`.

**Deliverable:**
- `@Provides` static method → a factory whose `createInstance` calls `Host.method(args…)` directly (args resolved via `getInstanceForConstructor`-style helpers over the method's parameters, using a `ConstructorInjectionPoint` built from the method params). `exposedKeys()` = the produced type (+ qualifier if the method carries one). `scope()` if the method carries a scoping annotation. Naming `$Host$method$$Factory`.
- `@Provides` instance method → the factory first resolves the host via its own factory (`getInstanceForConstructor(hostParamIndex, context)` where the host is modeled as parameter 0), then calls `host.method(args…)`. (Model the host as an implicit first dependency.)
- Overloaded `@Provides` methods disambiguate names with the index suffix.
- `ScopeBinding`: for each user `@ScopingAnnotation`, no runtime binding is needed beyond the existing `Scope` SPI — but a `@ScopingAnnotation` that has NO registered `Scope` should be flagged at Plan time OR left to the runtime `ScopeResolutionException`. Generate a `$<Ann>$$Scope` **only if** the design requires a binding artifact; if the runtime already resolves scopes purely by `ServiceLoader<Scope>` + annotation type (it does — Plan 1), then ScopeBinding generation is a **no-op placeholder** for user-defined scopes and this task only needs to ensure `@Singleton`-scoped beans work end to end (already covered by `scope()`). NOTE this decision explicitly in the code/test comments and keep `ScopeBindingGenerator` minimal or omitted if unneeded.

- [ ] TDD compile-testing: static `@Provides String provideName()` → `$…$provideName$$Factory` with `createInstance` calling the static method; instance `@Provides` with the host resolved first; a `@Provides` with parameters resolved as dependencies; a `@Named`/`@Singleton`-annotated `@Provides` → qualifier in `exposedKeys` / `scope()` override; overloaded `@Provides` → indexed names.
- [ ] Implement `ProvidesFactoryGenerator` (+ minimal/So-decided `ScopeBinding` handling).
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): generate @Provides factories`.

## Task 8: Service registration, conflict rule, module-path note, plugin SPI, end-to-end

**Files:** Write-stage service-file generation in `InjectProcessor`, `spi/InjectPlugin.java` (+ context param types), tests `ServiceRegistrationTest.java`, `EndToEndTest.java`, `PluginSpiTest.java`.

**Deliverable:**
- The processor writes `META-INF/services/be.imgn.inject.context.Factory` listing every generated factory FQN (and `…Scope` if any scope artifacts are generated).
- If a hand-authored `META-INF/services/be.imgn.inject.context.Factory` already exists on the source path, **compilation fails** with a clear message.
- The processor emits a compile-time NOTE listing the `provides be.imgn.inject.context.Factory with …` directives needed on the module path.
- `InjectPlugin` SPI (`default`-no-op `onGather`/`onPlan`/`onGenerate` with `GatherContext`/`PlanContext`/`GenerateContext` param types), discovered via `ServiceLoader<InjectPlugin>` and invoked at each stage. Full plugin behavior (`@Log`) is Plan 3; here just wire the hooks and prove a registered no-op plugin's methods are invoked.
- **End-to-end runtime test:** compile a small set of beans with the processor (compile-testing produces the generated `.class` files), load them plus the generated `META-INF/services` via a classloader, and assert `Injector.create()` resolves a bean through the generated factory. This closes the ServiceLoader-discovery coverage gap from Plan 1.

- [ ] TDD: service-file content assertion; pre-existing-file conflict → failed compilation; module-path NOTE emitted; plugin hooks invoked (register a test `InjectPlugin` that records calls); end-to-end resolution via `Injector.create()` over generated output on a classpath classloader.
- [ ] Implement Write-stage registration + conflict check + NOTE, the plugin SPI + ServiceLoader discovery + stage invocation, and the end-to-end test harness.
- [ ] `./mvnw clean verify` green. Commit: `feat(processor): service registration, module-path note, plugin SPI, e2e`.

---

## Self-Review Notes

- Spec §6 pipeline (Gather/Plan/Generate/Write) → Tasks 3–8; naming §"Generated type naming" → Task 2; `BeanFactoryGenerator` → Tasks 3–5; `ProvidesFactoryGenerator` → Task 7; validation/undeclared-deps → Task 6; service registration + conflict + module-path note (§"Service registration & conflict rule", §7) → Task 8; plugin SPI (§"Plugin SPI") → Task 8 (hooks) with the `@Log` demo deferred to Plan 3 (§8 examples).
- Deferred to Plan 3 (examples): the `@Log` plugin implementation and the module-path `provides` demonstration in `inject-examples`.
- Erasure decision: type tokens are `Class` literals (`java.lang.reflect.Type`-compatible); parameterized generic dependency types beyond `Provider<T>` are erased to their raw `Class`. Documented as a v1 limitation; revisit if parameterized bindings are needed.
- Qualifier attribute rendering scoped to scalar values (String, primitives, `Class`, enum). Array/annotation-valued qualifier attributes are a documented v1 limitation (Task 5 makes the choice explicit).
- **Duplicate-binding detection is deferred to runtime** (`DuplicateBindingException` in `DefaultContext`), not enforced at compile time. Under the ServiceLoader architecture, factories are discovered across modules, so cross-module duplicates cannot be seen at compile time; runtime detection is the single consistent safety net. (Deviates from Task 6's listed intra-compilation duplicate check.)
- **`Provider<T>`-broken cycles are allowed**: a `Provider<T>` dependency is excluded from the compile-time cycle graph (it is injected un-resolved and breaks the cycle at runtime), while still requiring the target type to have a factory. Direct (non-`Provider`) cycles are rejected.
- **Accessibility validation**: `private` `@Inject` constructors, and `private`/`static` `@Inject` fields/methods, and field/method-only beans with no accessible no-arg constructor, are rejected with clear `Messager` errors (Task 6 hardening from review).
