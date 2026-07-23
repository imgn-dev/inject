# Default Bindings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `@Provides` method marked `@Default` becomes the fallback binding for its key, used only when no other live binding satisfies it.

**Architecture:** One new annotation (`@Default`), one new `Factory` flag (`isDefault()`), and a selection pass in `DefaultContext` that inserts defaults after ordinary bindings and contributions. The processor validates placement and generates the `isDefault()` override. Spec: `docs/superpowers/specs/2026-07-23-default-bindings-design.md`.

**Tech Stack:** Java 21, JavaPoet (processor), JUnit 6 + AssertJ (runtime tests), google-compile-testing (processor tests), Maven wrapper.

## Global Constraints

- Build with `./mvnw clean verify` — never `install`, never a global `mvn`. Capture the exit code directly (`./mvnw clean verify; echo $?` style), never through a pipe.
- Per-module fast loop: `./mvnw -pl <module> test` is acceptable mid-task; run the full `./mvnw clean verify` before each commit.
- Javadoc on every public/protected API member; docs describe current state only.
- AssertJ fluent chaining — one `assertThat(subject)` with chained assertions, not repeated `assertThat` on the same subject.
- No fully-qualified names in code bodies (Checkstyle rule); import instead.
- Follow existing file/naming/style conventions exactly; Spotless formats on build.

---

### Task 1: `@Default` annotation, `Factory.isDefault()`, `DefaultContext` selection

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/Default.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/Factory.java` (after `contribution()`, ~line 131)
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java` (constructor, lines 92–130)
- Test: `inject/src/test/java/be/imgn/inject/context/DefaultBindingTest.java`

**Interfaces:**
- Consumes: existing `Factory`, `DefaultContext`, `AnnotationMetadata`/`AnnotationValue`, `OnPropertyCondition`, `SingletonScope`.
- Produces: `be.imgn.inject.Default` (annotation, `@Target(METHOD)`, `@Retention(RUNTIME)`, no members); `protected boolean isDefault()` on `Factory<T>` returning `false`; `DefaultContext` selection semantics per spec §3. Task 2 generates overrides of `isDefault()`; Tasks 3–4 rely on the runtime behavior.

- [ ] **Step 1: Write the failing test**

Create `inject/src/test/java/be/imgn/inject/context/DefaultBindingTest.java`. Fixture style copied from `ConditionalBeanTest` (same package, hand-written factories):

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Singleton;
import be.imgn.inject.UnsatisfiedDependencyException;

class DefaultBindingTest {

    /** A conditional factory for {@code String}, the "real" binding a default competes with. */
    static final class GatedFactory extends Factory<String> {
        private final AnnotationMetadata metadata;

        GatedFactory(AnnotationValue condition) {
            super(String.class);
            this.metadata = AnnotationMetadata.of(condition);
        }

        @Override
        protected String createInstance(Context context) {
            return "real";
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return metadata;
        }
    }

    /** A default factory for {@code String}, optionally scoped and optionally conditional. */
    static final class FallbackFactory extends Factory<String> {
        private final AnnotationMetadata metadata;
        private final Optional<Class<? extends Annotation>> scope;
        private int built;

        FallbackFactory() {
            this(AnnotationMetadata.empty(), Optional.empty());
        }

        FallbackFactory(AnnotationMetadata metadata, Optional<Class<? extends Annotation>> scope) {
            super(String.class);
            this.metadata = metadata;
            this.scope = scope;
        }

        @Override
        protected String createInstance(Context context) {
            built++;
            return "fallback-" + built;
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return metadata;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return scope;
        }

        @Override
        protected boolean isDefault() {
            return true;
        }
    }

    private static AnnotationValue onProperty(String name, String havingValue) {
        return AnnotationValue.of(
                ConditionalOnProperty.class,
                Map.of("value", name, "havingValue", havingValue, "matchIfMissing", false),
                Set.of(ConditioningAnnotation.class));
    }

    private static Context context(Map<String, String> properties, Factory<?>... factories) {
        return new DefaultContext(
                List.of(factories), List.of(new SingletonScope()), List.of(new OnPropertyCondition()), properties);
    }

    @Test
    void factoryIsNotDefaultByDefault() {
        assertThat(new GatedFactory(onProperty("x", "")).isDefault()).isFalse();
    }

    @Test
    void defaultLosesToALiveBinding() {
        Context context = context(
                Map.of("feature.x", "on"), new GatedFactory(onProperty("feature.x", "on")), new FallbackFactory());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("real");
    }

    @Test
    void defaultWinsWhenTheConditionFails() {
        Context context =
                context(Map.of(), new GatedFactory(onProperty("feature.x", "on")), new FallbackFactory());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("fallback-1");
    }

    @Test
    void defaultWinsWhenNoOtherBindingExists() {
        Context context = context(Map.of(), new FallbackFactory());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("fallback-1");
    }

    @Test
    void duplicateLiveDefaultsThrowEvenWhenARealBindingWins() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> context(
                        Map.of("feature.x", "on"),
                        new GatedFactory(onProperty("feature.x", "on")),
                        new FallbackFactory(),
                        new FallbackFactory()))
                .withMessageContaining("Duplicate default binding");
    }

    @Test
    void conditionalDefaultOutOfTheGraphWhenItsConditionFails() {
        Context context = context(
                Map.of(), new FallbackFactory(AnnotationMetadata.of(onProperty("fallback.x", "on")), Optional.empty()));
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
        assertThat(context.getBeanDefinition(Key.of(String.class))).isEmpty();
    }

    @Test
    void scopedDefaultIsWrappedInItsScope() {
        Context context =
                context(Map.of(), new FallbackFactory(AnnotationMetadata.empty(), Optional.of(Singleton.class)));
        assertThat(context.getProvider(Key.of(String.class)).get())
                .isEqualTo("fallback-1")
                .isEqualTo(context.getProvider(Key.of(String.class)).get());
    }

    @Test
    void winningDefaultIsTheBeanDefinition() {
        FallbackFactory fallback = new FallbackFactory();
        Context context = context(Map.of(), fallback);
        assertThat(context.getBeanDefinition(Key.of(String.class))).containsSame(fallback);
    }

    @Test
    void discardedDefaultContributesNoBeanDefinition() {
        GatedFactory real = new GatedFactory(onProperty("feature.x", "on"));
        Context context = context(Map.of("feature.x", "on"), real, new FallbackFactory());
        assertThat(context.getBeanDefinition(Key.of(String.class))).containsSame(real);
    }
}
```

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw -pl inject test -Dtest=DefaultBindingTest`
Expected: COMPILATION ERROR — `isDefault()` does not exist on `Factory`.

- [ ] **Step 3: Implement**

Create `inject/src/main/java/be/imgn/inject/Default.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Provides} method as the fallback recipe for its key: the binding is used only when no other live
 * binding satisfies the same key. Two live defaults for one key are a configuration error
 * ({@link DuplicateBindingException} at bootstrap).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Default {}
```

In `Factory.java`, directly after `contribution()` (~line 131), add:

```java
    /** {@return whether this factory is a fallback binding, used only when no other live binding claims its key} */
    protected boolean isDefault() {
        return false;
    }
```

In `DefaultContext.java`, rework the constructor's factory loop (lines 92–130). Buffer defaults during the main loop and insert them after contributions, before overrides:

```java
        Map<Key<?>, Provider<?>> built = new HashMap<>();
        Map<Key<?>, BeanDefinition> definitionsByKey = new HashMap<>();
        List<BeanDefinition> definitionList = new ArrayList<>();
        Map<Key<?>, List<Contributor>> contributions = new LinkedHashMap<>();
        List<PendingDefault> defaults = new ArrayList<>();
        for (Factory<?> factory : factories) {
            if (!conditionsPass(factory, conditionsByAnnotation, conditionContext)) {
                continue; // an unmatched condition removes the bean from the graph entirely
            }
            Provider<?> provider = wrap(factory, scopesByAnnotation);
            Optional<Contribution> contribution = factory.contribution();
            if (contribution.isPresent()) {
                contributions
                        .computeIfAbsent(contribution.get().collectionKey(), key -> new ArrayList<>())
                        .add(new Contributor(contribution.get(), provider));
                definitionList.add(factory);
                continue;
            }
            if (factory.isDefault()) {
                defaults.add(new PendingDefault(factory, provider));
                continue;
            }
            for (Key<?> key : factory.exposedKeys()) {
                if (built.putIfAbsent(key, provider) != null) {
                    throw new DuplicateBindingException("Duplicate binding for " + key);
                }
                definitionsByKey.put(key, factory);
            }
            definitionList.add(factory);
        }
        contributions.forEach((key, contributors) -> {
            if (built.putIfAbsent(key, aggregate(key, contributors)) != null) {
                throw new DuplicateBindingException("Duplicate binding for " + key);
            }
        });
        // Defaults join last: a default claims only keys no live binding took. Two live defaults for one key are
        // a configuration error even when a real binding wins that key.
        Map<Key<?>, Provider<?>> defaultClaims = new HashMap<>();
        for (PendingDefault pending : defaults) {
            boolean winner = false;
            for (Key<?> key : pending.factory().exposedKeys()) {
                if (defaultClaims.putIfAbsent(key, pending.provider()) != null) {
                    throw new DuplicateBindingException("Duplicate default binding for " + key);
                }
                if (built.putIfAbsent(key, pending.provider()) == null) {
                    definitionsByKey.put(key, pending.factory());
                    winner = true;
                }
            }
            if (winner) {
                definitionList.add(pending.factory());
            }
        }
```

The rest of the constructor (overrides, injector self-binding, `Map.copyOf`) is unchanged. Next to the existing `Contributor` record at the bottom of the class, add:

```java
    /** One buffered default binding: its factory and its fully wrapped (scoped, cycle-guarded) provider. */
    private record PendingDefault(Factory<?> factory, Provider<?> provider) {}
```

Update the class-level Javadoc of `DefaultContext` — extend the first sentence to mention default selection, e.g. "…and resolves keys lazily. Fallback factories ({@code Factory#isDefault()}) bind only keys no other live factory claims."

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw -pl inject test -Dtest=DefaultBindingTest` — expected: all tests PASS.
Then the module suite: `./mvnw -pl inject test` — expected: PASS, no regressions.

- [ ] **Step 5: Full verify, commit**

Run: `./mvnw clean verify` — expected exit code 0.

```bash
git add inject/src/main/java/be/imgn/inject/Default.java \
        inject/src/main/java/be/imgn/inject/context/Factory.java \
        inject/src/main/java/be/imgn/inject/context/DefaultContext.java \
        inject/src/test/java/be/imgn/inject/context/DefaultBindingTest.java
git commit -m "feat(inject): @Default marker; DefaultContext fallback selection"
```

---

### Task 2: Processor — validation, model, generated `isDefault()` override

**Files:**
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java` (marker checks, ~line 58)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ProvidesModel.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java` (`providesModel`, ~line 166)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ProvidesFactoryGenerator.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/DefaultBindingProcessorTest.java`

**Interfaces:**
- Consumes: `be.imgn.inject.Default` from Task 1; existing `Diagnostics.error(String, Element)`; `ProvidesModel` construction in `ModelFactory.providesModel(ExecutableElement, TypeElement, int)`.
- Produces: `ProvidesModel` gains a final record component `boolean isDefault`; generated `@Provides` factories for `@Default` methods override `protected boolean isDefault()` to return `true`; compile errors `"@Default requires @Provides."` and `"@Default cannot join a collection contribution (@IntoSet/@IntoMap)."`.

- [ ] **Step 1: Write the failing tests**

Create `inject-processor/src/test/java/be/imgn/inject/processor/DefaultBindingProcessorTest.java` (idiom copied from `ProvidesTest`/`ValidationTest`):

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class DefaultBindingProcessorTest {

    @Test
    void generatesIsDefaultOverrideForDefaultProvidesMethod() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$provideName$$Factory")
                .contentsAsUtf8String()
                .contains("protected boolean isDefault()");
    }

    @Test
    void ordinaryProvidesMethodGetsNoIsDefaultOverride() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String provideName() {
                        return "hi";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$provideName$$Factory")
                .contentsAsUtf8String()
                .doesNotContain("isDefault");
    }

    @Test
    void defaultWithoutProvidesIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;

                public class Config {
                    @Default
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default requires @Provides.");
    }

    @Test
    void defaultWithIntoSetIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    @IntoSet
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default cannot join a collection contribution");
    }

    @Test
    void defaultWithIntoMapIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Default;
                import be.imgn.inject.IntoMap;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    @IntoMap(value = String.class, key = "k")
                    public static String provideName() {
                        return "fallback";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default cannot join a collection contribution");
    }
}
```

(`IntoMap` declares `Class<?> value()` — the map's value type — and `String key()`; the usage above matches that shape.)

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw -pl inject-processor test -Dtest=DefaultBindingProcessorTest`
Expected: FAIL — no `isDefault` in generated source, no errors reported for misuse.

- [ ] **Step 3: Implement**

`GatherStage.java` — add after the `@IntoSet`/`@IntoMap` marker check block (~line 65), plus the matching imports (`be.imgn.inject.Default`):

```java
        for (Element element : roundEnv.getElementsAnnotatedWith(Default.class)) {
            if (element.getAnnotation(Provides.class) == null) {
                diagnostics.error("@Default requires @Provides.", element);
            } else if (element.getAnnotation(IntoSet.class) != null || element.getAnnotation(IntoMap.class) != null) {
                diagnostics.error("@Default cannot join a collection contribution (@IntoSet/@IntoMap).", element);
            }
        }
```

`ProvidesModel.java` — append a final record component `boolean isDefault` with Javadoc line `@param isDefault whether the method is a {@code @Default} fallback recipe`.

`ModelFactory.java` — in `providesModel(...)` (~line 186), pass the new final argument to the `ProvidesModel` constructor:

```java
                method.getAnnotation(Default.class) != null);
```

with the import `be.imgn.inject.Default`.

`ProvidesFactoryGenerator.java` — after the existing `scope`/`contribution` override wiring (~line 50):

```java
        if (model.isDefault()) {
            type.addMethod(isDefaultMethod());
        }
```

and next to the other private `MethodSpec` helpers:

```java
    private static MethodSpec isDefaultMethod() {
        return MethodSpec.methodBuilder("isDefault")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(boolean.class)
                .addStatement("return true")
                .build();
    }
```

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw -pl inject-processor test -Dtest=DefaultBindingProcessorTest` — expected: PASS.
Then: `./mvnw -pl inject-processor test` — expected: PASS, no regressions (other `ProvidesModel` construction sites, if any test builds models directly, must compile with the new component).

- [ ] **Step 5: Full verify, commit**

Run: `./mvnw clean verify` — expected exit code 0.

```bash
git add inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java \
        inject-processor/src/main/java/be/imgn/inject/processor/ProvidesModel.java \
        inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java \
        inject-processor/src/main/java/be/imgn/inject/processor/ProvidesFactoryGenerator.java \
        inject-processor/src/test/java/be/imgn/inject/processor/DefaultBindingProcessorTest.java
git commit -m "feat(processor): validate @Default and generate isDefault() overrides"
```

---

### Task 3: Classpath example

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/UsageTracker.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/MeteredTracker.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/TrackerConfig.java`
- Test: `inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java` (add tests)

**Interfaces:**
- Consumes: `@Default`, `@Provides`, `@ConditionalOnProperty`, `@Inject` — all published API after Tasks 1–2.
- Produces: example beans only; Task 4 mirrors the same shape on the module path.

- [ ] **Step 1: Write the failing tests**

Add to `ExamplesClasspathTest` (mirror the existing `examples.premium` System-property tests at lines 75–79 for set/clear discipline):

```java
    @Test
    void defaultTrackerFallsBackWhenMeteringIsOff() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(UsageTracker.class).describe()).isEqualTo("noop");
    }

    @Test
    void meteredTrackerWinsOverTheDefaultWhenMeteringIsOn() {
        System.setProperty("examples.metering", "true");
        try {
            Injector injector = Injector.create();
            assertThat(injector.getInstance(UsageTracker.class).describe()).isEqualTo("metered");
        } finally {
            System.clearProperty("examples.metering");
        }
    }
```

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw -pl inject-examples test -Dtest=ExamplesClasspathTest`
Expected: COMPILATION ERROR — `UsageTracker` does not exist.

- [ ] **Step 3: Implement the example beans**

`UsageTracker.java`:

```java
package be.imgn.inject.examples;

/** Reports how bean usage is tracked; the container falls back to a no-op tracker when metering is off. */
public interface UsageTracker {

    /** {@return a short description of the tracking mode} */
    String describe();
}
```

`MeteredTracker.java`:

```java
package be.imgn.inject.examples;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;

/** The real tracker, present only when {@code examples.metering=true}; it displaces the default tracker. */
@ConditionalOnProperty(value = "examples.metering", havingValue = "true")
public final class MeteredTracker implements UsageTracker {

    @Inject
    MeteredTracker() {}

    @Override
    public String describe() {
        return "metered";
    }
}
```

`TrackerConfig.java`:

```java
package be.imgn.inject.examples;

import be.imgn.inject.Default;
import be.imgn.inject.Provides;

/** Hosts the fallback {@link UsageTracker} recipe, used only when no live tracker binding exists. */
public final class TrackerConfig {

    private TrackerConfig() {}

    /** {@return the no-op tracker bound when metering is disabled} */
    @Provides
    @Default
    static UsageTracker noopTracker() {
        return () -> "noop";
    }
}
```

Note: `MeteredTracker`'s default exposed keys include its supertype `UsageTracker` (see `ExposeAs` Javadoc — default exposure is the bean type plus its supertypes), which is exactly the key the default claims. If the build reveals the interface must be a `@FunctionalInterface` for the lambda in `noopTracker`, return an anonymous class instead — do not annotate the example interface just for the lambda.

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw -pl inject-examples test` — expected: PASS including both new tests and no regressions.

- [ ] **Step 5: Full verify, commit**

Run: `./mvnw clean verify` — expected exit code 0.

```bash
git add inject-examples/src/main/java/be/imgn/inject/examples/UsageTracker.java \
        inject-examples/src/main/java/be/imgn/inject/examples/MeteredTracker.java \
        inject-examples/src/main/java/be/imgn/inject/examples/TrackerConfig.java \
        inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java
git commit -m "feat(examples): conditional tracker with a @Default fallback"
```

---

### Task 4: Module-path example

**Files:**
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/Telemetry.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/LiveTelemetry.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TelemetryConfig.java`
- Modify: `inject-examples-module/src/main/java/module-info.java`
- Test: `inject-examples-module/src/test/java/be/imgn/inject/examples/modular/ModulePathTest.java` (add tests)

**Interfaces:**
- Consumes: everything from Tasks 1–3; existing `provides be.imgn.inject.context.Factory with …` directive pattern in the module descriptor.
- Produces: module-path proof that a `@Default` factory registers through JPMS `provides` like any other factory.

- [ ] **Step 1: Write the failing tests**

Add to `ModulePathTest` (mirror the System-property set/clear idiom from Task 3):

```java
    @Test
    void defaultTelemetryFallsBackWhenLiveTelemetryIsOff() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Telemetry.class).mode()).isEqualTo("off");
    }

    @Test
    void liveTelemetryWinsOverTheDefaultWhenEnabled() {
        System.setProperty("modular.telemetry", "on");
        try {
            Injector injector = Injector.create();
            assertThat(injector.getInstance(Telemetry.class).mode()).isEqualTo("live");
        } finally {
            System.clearProperty("modular.telemetry");
        }
    }
```

Match `ModulePathTest`'s existing imports/assertion style when inserting (read the file first — it may build the injector differently).

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw -pl inject-examples-module test`
Expected: COMPILATION ERROR — `Telemetry` does not exist.

- [ ] **Step 3: Implement**

`Telemetry.java`:

```java
package be.imgn.inject.examples.modular;

/** Reports the telemetry mode; a {@code @Default} recipe supplies the off mode when live telemetry is disabled. */
public interface Telemetry {

    /** {@return the active telemetry mode} */
    String mode();
}
```

`LiveTelemetry.java`:

```java
package be.imgn.inject.examples.modular;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;

/** The real telemetry bean, present only when {@code modular.telemetry=on}. */
@ConditionalOnProperty(value = "modular.telemetry", havingValue = "on")
public final class LiveTelemetry implements Telemetry {

    @Inject
    LiveTelemetry() {}

    @Override
    public String mode() {
        return "live";
    }
}
```

`TelemetryConfig.java`:

```java
package be.imgn.inject.examples.modular;

import be.imgn.inject.Default;
import be.imgn.inject.Provides;

/** Hosts the fallback {@link Telemetry} recipe for the module-path example. */
public final class TelemetryConfig {

    private TelemetryConfig() {}

    /** {@return the disabled-mode telemetry bound when no live telemetry exists} */
    @Provides
    @Default
    static Telemetry offTelemetry() {
        return () -> "off";
    }
}
```

`module-info.java` — extend the `provides` list with the two new factories (a static `@Provides` host gets no host factory of its own; verify the exact names against the processor's compile-time note in the build output):

```java
    provides be.imgn.inject.context.Factory with
            be.imgn.inject.examples.modular.$Widget$$Factory,
            be.imgn.inject.examples.modular.$Service$$Factory,
            be.imgn.inject.examples.modular.$AlphaPlugin$$Factory,
            be.imgn.inject.examples.modular.$BetaPlugin$$Factory,
            be.imgn.inject.examples.modular.$PluginHost$$Factory,
            be.imgn.inject.examples.modular.$LiveTelemetry$$Factory,
            be.imgn.inject.examples.modular.$TelemetryConfig$offTelemetry$$Factory;
```

Note: the base of this branch may already list more factories than shown here (e.g. assisted-injection entries after a rebase) — append the two new lines to whatever the current list is; never remove existing entries.

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw -pl inject-examples-module test` — expected: PASS.

- [ ] **Step 5: Full verify, commit**

Run: `./mvnw clean verify` — expected exit code 0.

```bash
git add inject-examples-module/src/main/java/be/imgn/inject/examples/modular/Telemetry.java \
        inject-examples-module/src/main/java/be/imgn/inject/examples/modular/LiveTelemetry.java \
        inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TelemetryConfig.java \
        inject-examples-module/src/main/java/module-info.java \
        inject-examples-module/src/test/java/be/imgn/inject/examples/modular/ModulePathTest.java
git commit -m "feat(examples): @Default fallback on the module path"
```

---

### Task 5: Assisted-recipe prohibition — DEFERRED until `feat/assisted-injection` merges

**Prerequisite:** `main` must contain the assisted-injection feature (`@Assisted` in `be.imgn.inject`). If it does not, SKIP this task, leave its checkboxes unchecked, and record it as a follow-up in the final report. If it does, rebase this branch onto `main` first.

**Files:**
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java` (the `@Default` check block added in Task 2)
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/DefaultBindingProcessorTest.java` (add one test)

**Interfaces:**
- Consumes: `be.imgn.inject.Assisted` (from the merged assisted-injection feature); the Task 2 validation block.
- Produces: compile error `"@Default cannot mark an assisted recipe."`.

- [ ] **Step 1: Write the failing test**

Add to `DefaultBindingProcessorTest`:

```java
    @Test
    void defaultOnAnAssistedRecipeIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Default;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @Default
                    public static String provideName(@Assisted String seed) {
                        return seed;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Default cannot mark an assisted recipe.");
    }
```

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw -pl inject-processor test -Dtest=DefaultBindingProcessorTest`
Expected: FAIL — the assisted `@Provides` method is not rejected.

- [ ] **Step 3: Implement**

Extend the Task 2 block in `GatherStage` with an assisted check. An assisted recipe is a `@Provides` method with at least one `@Assisted` parameter — reuse the merged code's existing helper if one exists (search `GatherStage`/`ModelFactory` for how the assisted-injection feature detects assisted `@Provides` methods and call the same predicate); otherwise:

```java
            } else if (element instanceof ExecutableElement method
                    && method.getParameters().stream()
                            .anyMatch(parameter -> parameter.getAnnotation(Assisted.class) != null)) {
                diagnostics.error("@Default cannot mark an assisted recipe.", element);
            }
```

appended to the `else if` chain of the `@Default` validation loop, with the import `be.imgn.inject.Assisted`.

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw -pl inject-processor test -Dtest=DefaultBindingProcessorTest` — expected: PASS.

- [ ] **Step 5: Full verify, commit**

Run: `./mvnw clean verify` — expected exit code 0.

```bash
git add inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java \
        inject-processor/src/test/java/be/imgn/inject/processor/DefaultBindingProcessorTest.java
git commit -m "feat(processor): forbid @Default on assisted recipes"
```
