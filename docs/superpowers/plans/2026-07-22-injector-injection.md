# Injector Injection & Framework-Type Prohibitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beans can inject the `Injector` that built them; `Injector`/`Context`/`Factory` can never be bound, and `Context`/`Factory` never injected.

**Architecture:** `DefaultContext` goes public and self-registers an internal `Key.of(Injector.class)` provider backed by a once-settable field; `DefaultInjector(DefaultContext)` attaches itself via `setInjector`. The `Context.create()` statics die. The processor rejects explicit framework-type bindings and `Context`/`Factory` injection sites; the three types join the excluded default exposures. Spec: `docs/superpowers/specs/2026-07-22-injector-injection-design.md`.

**Tech Stack:** Java 21, Maven wrapper, palantir JavaPoet, google-compile-testing, JUnit + AssertJ.

## Global Constraints

- Build: `./mvnw clean verify` — NEVER `install`. Judge by Maven's own exit code (`./mvnw clean verify > /tmp/build.log 2>&1; echo EXIT=$?`), never through a pipe.
- Checkstyle `NoFQNInCode`: no dotted names in code — import (string constants for type names use the `X.class.getName()` pattern with imports, like the existing `PROVIDER`/`SET`/`MAP` constants in `ModelFactory`).
- NullAway is ERROR: nullable fields/returns take `@org.jspecify.annotations.Nullable` (imported).
- Javadoc on every public API member, present tense, current state only.
- Tests: JUnit + AssertJ fluent chaining (one `assertThat` chain per subject).
- Full-reactor builds for anything touching `inject-processor` or examples; `-pl inject` OK for runtime-only iteration.

---

### Task 1: Runtime — self-binding Injector, no more `Context.create()`

**Files:**
- Modify: `inject/src/main/java/be/imgn/inject/context/Context.java` (delete both `create` statics + the now-unused `Map` import)
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java` (public class, public no-arg + overrides ctors, `setInjector`, internal Injector binding)
- Modify: `inject/src/main/java/be/imgn/inject/DefaultInjector.java` (constructor takes `DefaultContext`, attaches itself)
- Modify: `inject/src/main/java/be/imgn/inject/Injector.java` (`create()`/`Builder.build()` rewiring; `Builder.bind` rejection)
- Modify: `inject/src/test/java/be/imgn/inject/context/DefaultContextTest.java` (replace the `Context.create()` test)
- Test: `inject/src/test/java/be/imgn/inject/InjectorInjectionTest.java` (new)

**Interfaces:**
- Consumes: existing `DefaultContext` constructor chain ending in `(factories, scopes, conditions, conditionContext, overrides)`; `Key.of(Class)`; `Provider`.
- Produces: `public DefaultContext()`, `public DefaultContext(Map<Key<?>, Provider<?>> overrides)`, `public void DefaultContext.setInjector(Injector)`; `DefaultInjector(DefaultContext)`. Task 2's golden tests and Task 3's example rely on `Injector.create()` behavior only.

- [ ] **Step 1: Write the failing tests**

`inject/src/test/java/be/imgn/inject/InjectorInjectionTest.java`:

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

import be.imgn.inject.context.Context;
import be.imgn.inject.context.DefaultContext;
import be.imgn.inject.context.Factory;

class InjectorInjectionTest {

    @Test
    void injectedInjectorIsTheCreatedInstance() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Injector.class)).isSameAs(injector);
        assertThat(injector.getProvider(Injector.class).get()).isSameAs(injector);
    }

    @Test
    void builderBuiltInjectorAlsoSelfBinds() {
        Injector injector = Injector.builder().bind(String.class, "x").build();
        assertThat(injector.getInstance(Injector.class)).isSameAs(injector);
    }

    @Test
    void injectorBindingIsNotABeanDefinition() {
        assertThat(Injector.create().getBeanDefinition(Key.of(Injector.class))).isEmpty();
    }

    @Test
    void bareContextWithoutInjectorRefusesResolution() {
        Context context = new DefaultContext();
        assertThatIllegalStateException()
                .isThrownBy(() -> context.getProvider(Key.of(Injector.class)).get())
                .withMessageContaining("no Injector attached");
    }

    @Test
    void setInjectorIsOnceOnly() {
        DefaultContext context = new DefaultContext();
        Injector injector = Injector.create();
        context.setInjector(injector);
        assertThatIllegalStateException()
                .isThrownBy(() -> context.setInjector(injector))
                .withMessageContaining("already");
    }

    @Test
    void builderRejectsFrameworkTypes() {
        Injector.Builder builder = Injector.builder();
        Injector injector = Injector.create();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(Injector.class, injector));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bind(Key.of(Context.class), new DefaultContext()));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> builder.bind(Key.of(Types.setOf(String.class)), java.util.Set.of()));
        // NoFQNInCode: import java.util.Set. The Set bind above is NOT rejected for being Set — remove that
        // assertion; only the three framework types are rejected. Instead assert a Factory key rejection:
    }
}
```

IMPORTANT correction to the sketch above (apply when writing the file): drop the `Types.setOf` assertion entirely — collections are legal to bind via overrides. The third rejection asserts a raw `Factory` key:

```java
    @Test
    void builderRejectsFrameworkTypes() {
        Injector.Builder builder = Injector.builder();
        Injector injector = Injector.create();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(Injector.class, injector));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bind(Key.of(Context.class), new DefaultContext()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bind(Key.of(Factory.class), "notAFactory"));
    }
```

(`Key.of(Factory.class)` is the raw-class overload — fine at compile time with a raw `Key`; if generics fight, use `Key.of((java.lang.reflect.Type) Factory.class)` — with `Type` imported.)

In `DefaultContextTest`, replace `createWiresUpServiceLoaderContextWithoutThrowing`:

```java
    @Test
    void noArgConstructorWiresUpServiceLoaderContextWithoutThrowing() {
        Context context = new DefaultContext();
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get())
                .withMessageContaining("Long");
    }
```

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw -q clean test -pl inject > /tmp/i1.log 2>&1; echo EXIT=$?`
Expected: EXIT=1 (compile errors: `DefaultContext` not public / `setInjector` missing; that IS the red signal for this task).

- [ ] **Step 3: Implement**

`Context.java` — delete both `static Context create(...)` methods and their Javadoc; drop the `java.util.Map` import if now unused. Class Javadoc: append one sentence "The default implementation is {@link DefaultContext}."

`DefaultContext.java`:
- Class declaration: `public final class DefaultContext implements Context` (was package-private). Update class Javadoc to describe construction (`new DefaultContext()` loads all services) and the injector attachment.
- Add field + key constant:

```java
    private static final Key<?> INJECTOR_KEY = Key.of(Injector.class);

    private @Nullable Injector injector;
```

(imports: `be.imgn.inject.Injector`, `org.jspecify.annotations.Nullable`.)
- Constructors: make the no-arg and `(Map<Key<?>, Provider<?>> overrides)` constructors `public` (Javadoc: "{@return a context loaded from all registered Factory, Scope, and Condition services}" / overrides variant documents shadowing). Others stay package-private.
- In the MAIN constructor, after `built.putAll(overrides);` and before `this.registry = Map.copyOf(built);` insert:

```java
        // Built-in self-binding: resolves to the Injector attached via setInjector. Not a bean - it gets no
        // BeanDefinition and joins no stereotype query.
        built.put(INJECTOR_KEY, (Provider<Injector>) this::requireInjector);
```

- Add methods:

```java
    /**
     * Attaches the {@link Injector} this context resolves {@code Key.of(Injector.class)} to. Called once by the
     * injector that owns this context, immediately after construction.
     *
     * @param injector the owning injector
     * @throws IllegalStateException if an injector is already attached
     */
    public void setInjector(Injector injector) {
        Objects.requireNonNull(injector, "injector");
        if (this.injector != null) {
            throw new IllegalStateException("An Injector is already attached to this Context.");
        }
        this.injector = injector;
    }

    private Injector requireInjector() {
        Injector attached = injector;
        if (attached == null) {
            throw new IllegalStateException("There is no Injector attached to this Context.");
        }
        return attached;
    }
```

`DefaultInjector.java` — constructor becomes:

```java
    DefaultInjector(DefaultContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.setInjector(this);
    }
```

(field stays `private final Context context`; import `be.imgn.inject.context.DefaultContext`.)

`Injector.java`:
- `create()`: `return new DefaultInjector(new DefaultContext());` (import `be.imgn.inject.context.DefaultContext`).
- `Builder.build()`: `return new DefaultInjector(new DefaultContext(Map.copyOf(overrides)));`
- `Builder.bind(Key<T> key, T instance)` gains the rejection before storing:

```java
            requireBindable(key);
```

and the helper in `Builder`:

```java
        private static void requireBindable(Key<?> key) {
            Type type = key.type();
            Type raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
            if (raw == Injector.class || raw == Context.class || raw == Factory.class) {
                throw new IllegalArgumentException(
                        ((Class<?>) raw).getSimpleName() + " is a framework type and cannot be bound.");
            }
        }
```

(imports on `Injector.java`: `java.lang.reflect.ParameterizedType`, `java.lang.reflect.Type`, `be.imgn.inject.context.Factory`. The `bind(Class,T)` overload delegates to `bind(Key.of(type), instance)` already — verify, and if it doesn't, add the same call there.)

Javadoc additions (same commit): `Injector` interface doc gains one sentence: "The injector itself is injectable: any bean may declare an {@code Injector} (or {@code Provider<Injector>}) dependency and receives the injector that built it. {@code Injector}, {@code Context}, and {@code Factory} can never be bound."

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw -q clean test -pl inject > /tmp/i1.log 2>&1; echo EXIT=$?`
Expected: EXIT=0. Then full reactor once (examples still compile — nothing else called `Context.create()`):
`./mvnw clean verify > /tmp/i1full.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(inject): injectable Injector via DefaultContext self-binding

Remove the Context.create() statics - they existed only as a cross-package
construction bridge. DefaultContext is public now; Injector.create() and
Builder.build() construct it directly and DefaultInjector attaches itself
via setInjector (once-only), so Key.of(Injector.class) resolves to the very
injector that built the bean. Builder.bind rejects the framework types
Injector, Context, and Factory."
```

---

### Task 2: Processor — prohibitions and exclusions

**Files:**
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/InjectorInjectionGenerationTest.java` (new)

**Interfaces:**
- Consumes: `ModelFactory`'s existing `typeExpression(...)`, `providesModel(...)`, `exposedTypes(...)`/`MirroredTypesException` handling for `@ExposeAs`, `contribution(...)` marker reader, `EXCLUDED_DEFAULT_EXPOSURES`, `diagnostics`.
- Produces: compile errors per the spec's table; the three types silently excluded from default supertype exposure. No new types.

- [ ] **Step 1: Write the failing golden tests**

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InjectorInjectionGenerationTest {

    @Test
    void injectorSiteCompilesAndRendersPlainKey() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Locator", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Injector;
                import be.imgn.inject.Provider;

                public class Locator {
                    @Inject
                    public Locator(Injector injector, Provider<Injector> lazy) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Locator$$Factory")
                .contentsAsUtf8String();
        source.contains("new Parameter(\"injector\", Injector.class,");
    }

    @Test
    void injectingContextIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Context;

                public class Bad {
                    @Inject
                    public Bad(Context context) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("inject Injector instead");
    }

    @Test
    void injectingProviderOfContextIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;
                import be.imgn.inject.context.Context;

                public class Bad {
                    @Inject
                    public Bad(Provider<Context> context) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("inject Injector instead");
    }

    @Test
    void injectingFactoryIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Factory;

                public class Bad {
                    @Inject
                    public Bad(Factory<String> factory) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be injected");
    }

    @Test
    void providingInjectorIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Injector;
                import be.imgn.inject.Provides;

                public class Bad {
                    @Provides
                    public static Injector injector() {
                        throw new UnsupportedOperationException();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be bound");
    }

    @Test
    void exposeAsFrameworkTypeIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Context;

                @ExposeAs(Context.class)
                public class Bad implements Context {
                    @Inject
                    public Bad() {}

                    @Override
                    public <T> be.imgn.inject.Provider<T> getProvider(be.imgn.inject.Key<T> key) {
                        throw new UnsupportedOperationException();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be bound");
    }

    @Test
    void intoSetOfFrameworkTypeIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Injector;
                import be.imgn.inject.IntoSet;

                @IntoSet(Injector.class)
                public class Bad {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be bound");
    }

    @Test
    void beanImplementingContextIsSilentlyNotExposedAsContext() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Custom", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Key;
                import be.imgn.inject.Provider;
                import be.imgn.inject.context.Context;

                public class Custom implements Context {
                    @Inject
                    public Custom() {}

                    @Override
                    public <T> Provider<T> getProvider(Key<T> key) {
                        throw new UnsupportedOperationException();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Custom$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(Custom.class)");
        source.doesNotContain("Key.of(Context.class)");
    }
}
```

Note the `exposeAsFrameworkTypeIsAnError` source uses FQNs inside the TEXT BLOCK — that is embedded compile-testing source, exempt from checkstyle by the existing inject-processor test suppression.

- [ ] **Step 2: Run to verify RED**

Run: `./mvnw clean verify > /tmp/i2.log 2>&1; echo EXIT=$?`
Expected: EXIT=1; the prohibition tests fail (compilations succeed today), the exposure test may already pass or fail on exposure content.

- [ ] **Step 3: Implement in `ModelFactory`**

Add constants beside `PROVIDER`/`SET`/`MAP`/`STRING` (imports `be.imgn.inject.Injector`; `Context`/`Factory` are in the same package as nothing — they're `be.imgn.inject.context`, import them):

```java
    private static final String INJECTOR = Injector.class.getCanonicalName();
    private static final String CONTEXT = Context.class.getCanonicalName();
    private static final String FACTORY = Factory.class.getCanonicalName();
```

CAREFUL: `ModelFactory` very likely has no import of `be.imgn.inject.context.Context`/`Factory` today and `Factory` may collide with nothing in that file — check imports; if a name collision arises with local types, fall back to string literals? NO — checkstyle. Use imports; there is no `Context`/`Factory` type in `be.imgn.inject.processor` (the generator references them via JavaPoet `ClassName` strings only), so plain imports are safe.

1. **Forbidden injection sites** — in `typeExpression(TypeMirror, Types, Element)` (runs AFTER the Provider unwrap in `dependency(...)`), before the Set/Map handling, add:

```java
        if (type instanceof DeclaredType declaredSite
                && declaredSite.asElement() instanceof TypeElement siteElement) {
            String qualified = siteElement.getQualifiedName().toString();
            if (qualified.equals(CONTEXT)) {
                diagnostics.error("Context cannot be injected; inject Injector instead.", site);
            } else if (qualified.equals(FACTORY)) {
                diagnostics.error("Factory cannot be injected.", site);
            }
        }
```

(Then fall through to the existing rendering — the error already fails the compile; the fallback `$T.class` render keeps processing alive, same pattern as existing shape errors.) `Provider<Context>` is covered because `dependency` unwraps Provider before calling `typeExpression`.

2. **Forbidden `@Provides` return** — in `providesModel(...)` (or the provides validation path if one exists — put it where `diagnostics` and the method element are both in hand, at the top of `providesModel`):

```java
        String returned = types.erasure(method.getReturnType()).toString();
        if (returned.equals(INJECTOR) || returned.equals(CONTEXT) || returned.equals(FACTORY)) {
            diagnostics.error("Framework types cannot be bound: " + returned + " cannot be provided.", method);
        }
```

Wait — `erasure(...).toString()` was just migrated away from (idiom finding). Use the declared-type element instead:

```java
        if (method.getReturnType() instanceof DeclaredType declaredReturn
                && declaredReturn.asElement() instanceof TypeElement returnElement) {
            String qualified = returnElement.getQualifiedName().toString();
            if (qualified.equals(INJECTOR) || qualified.equals(CONTEXT) || qualified.equals(FACTORY)) {
                diagnostics.error(returnElement.getSimpleName() + " is a framework type and cannot be bound.", method);
            }
        }
```

3. **Forbidden `@ExposeAs` entries** — find where `@ExposeAs` values are read (the `MirroredTypesException` dance feeding `exposedTypes`); for each exposed `TypeMirror`, apply the same qualified-name check and report `"... is a framework type and cannot be bound."` on the annotated element.

4. **Forbidden marker element types** — in `contribution(...)`, after `elementType` is resolved (the `annotationClassValue` result), same check, message `"... is a framework type and cannot be bound."`, `return null`.

5. **Excluded default exposures** — add to `EXCLUDED_DEFAULT_EXPOSURES`:

```java
            Injector.class.getName(),
            Context.class.getName(),
            Factory.class.getName(),
```

(These are `Set.of(...)` entries alongside `Object.class.getName()` etc.; `Context`/`Factory`/`Injector` imported per above.)

Extract a tiny shared helper if it reads better:

```java
    /** {@return whether {@code element} is one of the framework types that can never be bound or injected} */
    private static boolean isFrameworkType(TypeElement element) {
        String qualified = element.getQualifiedName().toString();
        return qualified.equals(INJECTOR) || qualified.equals(CONTEXT) || qualified.equals(FACTORY);
    }
```

- [ ] **Step 4: Run to verify GREEN**

Run: `./mvnw clean verify > /tmp/i2.log 2>&1; echo EXIT=$?`
Expected: EXIT=0, all existing golden tests still green.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(processor): forbid binding/injecting framework types

Injecting Context or Factory (directly or via Provider) is a compile error
pointing at Injector injection; @Provides returns, @ExposeAs entries, and
@IntoSet/@IntoMap element types naming Injector, Context, or Factory are
compile errors; the three types join the excluded default exposures so a
bean incidentally implementing one is silently not exposed under it."
```

---

### Task 3: End-to-end example + final sweep

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/BeanLocator.java`
- Modify: `inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java` (one test)

**Interfaces:**
- Consumes: Tasks 1–2 through the real processor.
- Produces: nothing downstream.

- [ ] **Step 1: Write the example bean**

```java
// BeanLocator.java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Injector;

/** Demonstrates injector injection: resolves other beans through the {@link Injector} that built it. */
public final class BeanLocator {
    private final Injector injector;

    /** @param injector the injector that built this bean */
    @Inject
    public BeanLocator(Injector injector) {
        this.injector = injector;
    }

    /** {@return the injector this bean was built by} */
    public Injector injector() {
        return injector;
    }

    /** {@return the registry resolved on demand through the injector} */
    public Registry locateRegistry() {
        return injector.getInstance(Registry.class);
    }
}
```

- [ ] **Step 2: Add the test to `ExamplesClasspathTest`**

```java
    @Test
    void injectsTheInjectorItself() {
        Injector injector = Injector.create();
        BeanLocator locator = injector.getInstance(BeanLocator.class);
        assertThat(locator.injector()).isSameAs(injector);
        assertThat(locator.locateRegistry()).isSameAs(injector.getInstance(Registry.class));
    }
```

(`Registry` is `@Singleton`, so `isSameAs` holds across resolutions.)

- [ ] **Step 3: Full verify**

Run: `./mvnw clean verify > /tmp/i3.log 2>&1; echo EXIT=$?`
Expected: EXIT=0; `grep -E "Passed: 3" /tmp/i3.log` shows the invoker ITs pass.

- [ ] **Step 4: Commit**

```bash
git add inject-examples
git commit -m "feat(examples): bean injecting the Injector that built it"
```

---

## Self-Review Notes

- Spec coverage: self-binding + same-instance (T1 tests), once-only setInjector (T1), bare-context refusal (T1), Builder rejection (T1), create() statics removed (T1), Injector site + Provider<Injector> (T2 golden + T1 runtime), Context/Factory injection errors incl. Provider composition (T2), @Provides/@ExposeAs/marker binding errors (T2), excluded exposure (T2 golden `beanImplementingContextIsSilentlyNotExposedAsContext`), no BeanDefinition for the Injector key (T1), e2e (T3).
- Type consistency: `setInjector(Injector)` public on public `DefaultContext`; `DefaultInjector(DefaultContext)` package-private; `requireBindable` private static in `Builder`; `isFrameworkType(TypeElement)` private static in `ModelFactory`.
- Known ripple: `Context.java` loses its `Map` import; `DefaultContextTest` keeps all other tests unchanged.
