# Lifecycle Callbacks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@PostConstruct` runs as the last build step; `Injector` is `AutoCloseable` and `close()` invokes `@PreDestroy` on the scoped instances it created, LIFO.

**Architecture:** `Factory` gains `postConstruct(T)`/`preDestroy(T)`/`hasPreDestroy()` hooks that generated factories override with direct calls; `build()` calls `postConstruct` last. `Context` gains `close()`; `DefaultContext` wraps scope-wrapped providers of `@PreDestroy` factories in a tracker that records `(factory, instance)` LIFO on first creation, and `close()` drains it reversed with collect-suppressed exception handling. Processor gathers/validates the callbacks and emits the overrides; the assisted generator emits the `@PostConstruct` call in `create(...)`. Spec: `docs/superpowers/specs/2026-07-23-lifecycle-callbacks-design.md`.

**Tech Stack:** Java 21, Maven wrapper, palantir JavaPoet, google-compile-testing, JUnit + AssertJ. Processor touched; no new ServiceLoader services (module-path unaffected).

## Global Constraints

- Build: `./mvnw clean verify` — NEVER `install`. Judge by Maven's own exit code (`> /tmp/x.log 2>&1; echo EXIT=$?`), never through a pipe. `-pl inject` fine for runtime-only iteration; full reactor before every commit and for anything touching `inject-processor`/examples.
- Checkstyle `NoFQNInCode` — always import (test text blocks in `inject-processor` are exempt via existing suppression). NullAway is ERROR (`@org.jspecify.annotations.Nullable`, imported). Javadoc on every public API member, present tense. AssertJ fluent chaining.
- Package split: `be.imgn.inject` (annotations, Injector, …) vs `be.imgn.inject.context` (Context, DefaultContext, ChildContext, Factory, …). `DefaultContext`'s `(factories, scopes, …)` constructors are package-private; runtime tests using them live in `be.imgn.inject.context`.

---

### Task 1: `@PostConstruct`/`@PreDestroy` annotations + `Factory` hooks

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/PostConstruct.java`
- Create: `inject/src/main/java/be/imgn/inject/PreDestroy.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/Factory.java`
- Test: `inject/src/test/java/be/imgn/inject/context/FactoryLifecycleTest.java`

**Interfaces:**
- Consumes: existing `Factory.build(Context)`.
- Produces: `@PostConstruct`, `@PreDestroy` (RUNTIME, `@Target(METHOD)`); `protected void Factory.postConstruct(T)` (default no-op, called last by `build`), `protected void Factory.preDestroy(T)` (default no-op), `protected boolean Factory.hasPreDestroy()` (default `false`). Tasks 2 and 3 rely on these three names.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FactoryLifecycleTest {

    /** Records the order of construction, injection, and the postConstruct hook. */
    static final class Bean {
        final List<String> events = new ArrayList<>();
    }

    static final class TrackingFactory extends Factory<Bean> {
        TrackingFactory() {
            super(Bean.class);
        }

        @Override
        protected Bean createInstance(Context context) {
            Bean bean = new Bean();
            bean.events.add("construct");
            return bean;
        }

        @Override
        protected void injectFields(Bean instance, Context context) {
            instance.events.add("fields");
        }

        @Override
        protected void postConstruct(Bean instance) {
            instance.events.add("postConstruct");
        }

        @Override
        protected boolean hasPreDestroy() {
            return true;
        }

        @Override
        protected void preDestroy(Bean instance) {
            instance.events.add("preDestroy");
        }
    }

    @Test
    void postConstructRunsLastInBuild() {
        Bean bean = new TrackingFactory().build(new DefaultContext(List.of(), List.of()));
        assertThat(bean.events).containsExactly("construct", "fields", "postConstruct");
    }

    @Test
    void defaultsAreInertNoOps() {
        Factory<Object> plain = new Factory<>(Object.class) {
            @Override
            protected Object createInstance(Context context) {
                return new Object();
            }
        };
        assertThat(plain.hasPreDestroy()).isFalse();
        // no-op hooks must not throw
        plain.preDestroy(new Object());
    }

    @Test
    void preDestroyHookInvokesTheCallback() {
        TrackingFactory factory = new TrackingFactory();
        Bean bean = factory.build(new DefaultContext(List.of(), List.of()));
        bean.events.clear();
        factory.preDestroy(bean);
        assertThat(bean.events).containsExactly("preDestroy");
    }
}
```

- [ ] **Step 2: RED**

Run: `./mvnw -q clean test -pl inject > /tmp/l1.log 2>&1; echo EXIT=$?`
Expected: EXIT=1 (`postConstruct`/`preDestroy`/`hasPreDestroy` undefined on `Factory`).

- [ ] **Step 3: Implement**

`PostConstruct.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method invoked once the bean is fully injected — after constructor, {@code @Inject} methods, and
 * {@code @Inject} fields. At most one per class; the method must be a non-private, non-static, no-argument
 * {@code void} method declared on the bean itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostConstruct {}
```

`PreDestroy.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method invoked when the owning injector closes. Only a scoped bean may declare it — prototypes and
 * assisted beans are caller-owned and never destroyed by the container. At most one per class; the method must be a
 * non-private, non-static, no-argument {@code void} method declared on the bean itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreDestroy {}
```

In `Factory.java`, change `build` to call the hook last and add the three hooks after it:

```java
    public final T build(Context context) {
        Objects.requireNonNull(context, "context");
        T instance = createInstance(context);
        injectMethods(instance, context);
        injectFields(instance, context);
        postConstruct(instance);
        return instance;
    }

    /**
     * Invoked by {@link #build} after field injection. Empty by default; a generated factory overrides it to call the
     * bean's {@code @PostConstruct} method.
     *
     * @param instance the fully injected instance
     */
    protected void postConstruct(T instance) {}

    /**
     * Invoked by the owning context when the injector closes. Empty by default; a generated factory whose bean
     * declares {@code @PreDestroy} overrides it to call that method.
     *
     * @param instance the instance to destroy
     */
    protected void preDestroy(T instance) {}

    /** {@return whether this factory's bean declares a {@code @PreDestroy} callback} */
    protected boolean hasPreDestroy() {
        return false;
    }
```

- [ ] **Step 4: GREEN, commit**

Run: `./mvnw -q clean test -pl inject > /tmp/l1.log 2>&1; echo EXIT=$?` then `./mvnw clean verify > /tmp/l1full.log 2>&1; echo EXIT=$?` — both EXIT=0.

```bash
git add -A
git commit -m "feat(inject): @PostConstruct/@PreDestroy annotations and Factory hooks

build() calls a new postConstruct(T) hook last; preDestroy(T) and
hasPreDestroy() default to no-op/false. Generated factories override them;
the annotations are @Target(METHOD), RUNTIME."
```

---

### Task 2: `Injector` closeable, `Context.close`, scoped destruction

**Files:**
- Modify: `inject/src/main/java/be/imgn/inject/Injector.java` (extend `AutoCloseable`, `void close()`)
- Modify: `inject/src/main/java/be/imgn/inject/DefaultInjector.java` (`close()` delegates)
- Modify: `inject/src/main/java/be/imgn/inject/context/Context.java` (default `close()`)
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java` (tracking + `close()` + closed guard)
- Modify: `inject/src/main/java/be/imgn/inject/context/ChildContext.java` (`close()` no-op + closed guard)
- Test: `inject/src/test/java/be/imgn/inject/context/LifecycleCloseTest.java`

**Interfaces:**
- Consumes: `Factory.preDestroy`/`hasPreDestroy` (Task 1), existing `DefaultContext.wrap`, `ChildContext`.
- Produces: `void Injector.close()`; `default void Context.close() {}`; `DefaultContext.close()` (LIFO scoped destruction, collect-suppressed, idempotent, post-close resolution throws `IllegalStateException`); `ChildContext.close()` (no-op destruction, closed guard).

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Singleton;

class LifecycleCloseTest {

    /** A named component recording its destruction into a shared log. */
    record Component(String name) {}

    /** Singleton factory whose preDestroy logs the name; optionally throws. */
    static final class ComponentFactory extends Factory<Component> {
        private final String name;
        private final List<String> destroyed;
        private final boolean throwOnDestroy;

        ComponentFactory(String name, List<String> destroyed, boolean throwOnDestroy) {
            super(Component.class);
            this.name = name;
            this.destroyed = destroyed;
            this.throwOnDestroy = throwOnDestroy;
        }

        @Override
        protected Component createInstance(Context context) {
            return new Component(name);
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Component.class, () -> name)); // qualifier by name so two register distinctly
        }

        @Override
        protected Optional<Class<? extends java.lang.annotation.Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected boolean hasPreDestroy() {
            return true;
        }

        @Override
        protected void preDestroy(Component instance) {
            destroyed.add(instance.name());
            if (throwOnDestroy) {
                throw new IllegalStateException("boom-" + instance.name());
            }
        }
    }

    // NOTE: Key.of(Class, Annotation) needs a real annotation instance; the () -> name lambda above will NOT
    // compile. Replace the exposedKeys override with distinct KEYS via a helper: bind each component under
    // Key.of(Component.class, Names.named(name)). Import be.imgn.inject.Names and use Names.named(name).

    @Test
    void closeInvokesPreDestroyLifoForCreatedSingletons() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context = new DefaultContext(
                List.of(
                        new ComponentFactory("a", destroyed, false),
                        new ComponentFactory("b", destroyed, false)),
                List.of(new SingletonScope()));
        // create both, in order a then b
        context.getProvider(Key.of(Component.class, be.imgn.inject.Names.named("a"))).get();
        context.getProvider(Key.of(Component.class, be.imgn.inject.Names.named("b"))).get();
        context.close();
        assertThat(destroyed).containsExactly("b", "a"); // LIFO
    }

    @Test
    void uncreatedSingletonIsNotDestroyed() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context = new DefaultContext(
                List.of(new ComponentFactory("a", destroyed, false)), List.of(new SingletonScope()));
        context.close(); // never resolved
        assertThat(destroyed).isEmpty();
    }

    @Test
    void closeCompletesEvenWhenACallbackThrows() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context = new DefaultContext(
                List.of(
                        new ComponentFactory("a", destroyed, false),
                        new ComponentFactory("b", destroyed, true)),
                List.of(new SingletonScope()));
        context.getProvider(Key.of(Component.class, be.imgn.inject.Names.named("a"))).get();
        context.getProvider(Key.of(Component.class, be.imgn.inject.Names.named("b"))).get();
        assertThatThrownBy(context::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom-b");
        assertThat(destroyed).containsExactly("b", "a"); // b threw, a still destroyed
    }

    @Test
    void doubleCloseIsANoOp() {
        List<String> destroyed = new ArrayList<>();
        DefaultContext context = new DefaultContext(
                List.of(new ComponentFactory("a", destroyed, false)), List.of(new SingletonScope()));
        context.getProvider(Key.of(Component.class, be.imgn.inject.Names.named("a"))).get();
        context.close();
        context.close();
        assertThat(destroyed).containsExactly("a"); // once
    }

    @Test
    void resolutionAfterCloseThrows() {
        DefaultContext context = new DefaultContext(List.of(), List.of());
        context.close();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
    }
}
```

IMPORTANT (apply when writing the file): the inline `Key.of(Component.class, () -> name)` in `exposedKeys` does not compile — a `Key` qualifier needs a real `Annotation`. Rewrite `exposedKeys` as:

```java
        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Component.class, be.imgn.inject.Names.named(name)));
        }
```

and import `be.imgn.inject.Names` (drop the inline FQNs — use imports for `Names`, `Annotation`). Delete the explanatory NOTE comment block.

Add a child-close test to `ChildContextTest` (package `be.imgn.inject.context`, reuses its helpers):

```java
    @Test
    void childCloseDoesNotCloseTheParent() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory()), List.of());
        ChildContext context = child(parent, Map.of());
        context.close();
        // child closed: its own resolution fails
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
        // parent still open
        assertThat(parent.getProvider(Key.of(String.class)).get()).isEqualTo("parent");
    }
```

(imports: `assertThatExceptionOfType`.)

- [ ] **Step 2: RED**

Run: `./mvnw -q clean test -pl inject > /tmp/l2.log 2>&1; echo EXIT=$?` — Expected: EXIT=1 (`close` undefined).

- [ ] **Step 3: Implement**

`Context.java` — add before the closing brace:

```java
    /**
     * Destroys this context's scoped instances (invoking their {@code @PreDestroy} callbacks) and marks it closed.
     * The default is a no-op for minimal implementations. Idempotent.
     */
    default void close() {}
```

`Injector.java` — change the interface declaration to `public interface Injector extends AutoCloseable {` and override `close` to drop the checked exception:

```java
    /**
     * Closes this injector: invokes {@code @PreDestroy} on every scoped instance it created, in reverse creation
     * order, then rejects further resolution. Idempotent. Child injectors own no scoped instances, so closing a
     * child destroys nothing and does not close its parent.
     */
    @Override
    void close();
```

`DefaultInjector.java` — add:

```java
    @Override
    public void close() {
        context.close();
    }
```

`ChildContext.java` — add a `private boolean closed;` field, guard `getProvider`'s top with:

```java
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
```

and add:

```java
    @Override
    public void close() {
        closed = true;
    }
```

`DefaultContext.java`:
- Add fields:

```java
    private final Deque<TrackedInstance<?>> destroyables = new ArrayDeque<>();
    private volatile boolean closed;
```

(imports `java.util.ArrayDeque`, `java.util.Deque`.)
- Add the tracked-instance holder (private nested record) and its destroy helper:

```java
    /** A created scoped instance paired with the factory that can destroy it. */
    private record TrackedInstance<T>(Factory<T> factory, T instance) {
        void destroy() {
            factory.preDestroy(instance);
        }
    }
```

- In `wrap`, after computing the scope-wrapped provider, wrap once more to track `@PreDestroy` instances. Replace the current tail of `wrap`:

```java
        Scope scope = scopes.get(scopeAnnotation.get());
        if (scope == null) {
            throw new ScopeResolutionException(
                    "No scope registered for @" + scopeAnnotation.get().getSimpleName());
        }
        Provider<T> scoped = scope.wrap(guarded);
        if (!factory.hasPreDestroy()) {
            return scoped;
        }
        return tracking(factory, scoped);
    }

    /** {@return a provider that records the created instance once, for destruction at {@link #close}} */
    private <T> Provider<T> tracking(Factory<T> factory, Provider<T> scoped) {
        java.util.concurrent.atomic.AtomicBoolean recorded = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            T instance = scoped.get();
            if (recorded.compareAndSet(false, true)) {
                synchronized (destroyables) {
                    destroyables.push(new TrackedInstance<>(factory, instance));
                }
            }
            return instance;
        };
    }
```

(Replace the inline `java.util.concurrent.atomic.AtomicBoolean` FQN with an import of `AtomicBoolean`; NoFQNInCode.)
- Guard `getProvider`'s top (after the `requireNonNull`):

```java
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
```

- Add `close`:

```java
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        synchronized (destroyables) {
            while (!destroyables.isEmpty()) {
                TrackedInstance<?> tracked = destroyables.pop(); // LIFO: last created destroyed first
                try {
                    tracked.destroy();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
```

Note on ordering: `destroyables` is a stack (`push` on record). The tracker pushes in creation order, so the first `pop` at close returns the last-created — LIFO. (The test expects `b, a` after creating `a, b`.)

- [ ] **Step 4: GREEN, commit**

Run: `./mvnw -q clean test -pl inject > /tmp/l2.log 2>&1; echo EXIT=$?` then full reactor `./mvnw clean verify > /tmp/l2full.log 2>&1; echo EXIT=$?` — both EXIT=0. (Existing `InjectorInjectionTest`/`ChildInjectorTest`/`DefaultContextTest` are regression guards — `Injector` becoming `AutoCloseable` must not break them; no existing test declares a checked exception on `close`.)

```bash
git add -A
git commit -m "feat(inject): closeable injectors destroy scoped beans LIFO

Injector is AutoCloseable; close() drains the context's tracked scoped
instances in reverse creation order, invoking @PreDestroy with collect-
suppressed exception handling, idempotently, and rejects later resolution.
Only scoped factories declaring @PreDestroy are tracked, once, on creation.
Child close is a no-op that never touches the parent."
```

---

### Task 3: Processor — gather, validate, generate callbacks

**Files:**
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/LifecycleCallbacks.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanModel.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/AssistedFactoryGenerator.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/LifecycleGenerationTest.java`

**Interfaces:**
- Consumes: `ModelFactory` gathering (`buildModel`, `validateAssistedRecipe`), `GatherStage` bean loop, `Annotations.scope`, `BeanFactoryGenerator.generate`, `AssistedFactoryGenerator.constructorBody`.
- Produces: `record LifecycleCallbacks(@Nullable String postConstruct, @Nullable String preDestroy)`; `ModelFactory.lifecycleCallbacks(TypeElement bean, boolean scoped, boolean assisted)` (validating) and `ModelFactory.postConstructName(TypeElement)` (pure, generation-time); `BeanModel` grows `@Nullable String postConstruct, @Nullable String preDestroy`. Generated factories override `postConstruct`/`preDestroy`/`hasPreDestroy` when present; assisted `create(...)` calls the recipe's `@PostConstruct` last.

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

class LifecycleGenerationTest {

    @Test
    void generatesPostConstructAndPreDestroyOverrides() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Db", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;
                import be.imgn.inject.PreDestroy;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Db {
                    @Inject
                    public Db() {}

                    @PostConstruct
                    void connect() {}

                    @PreDestroy
                    void disconnect() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Db$$Factory")
                .contentsAsUtf8String();
        source.contains("protected void postConstruct(Db instance)");
        source.contains("instance.connect()");
        source.contains("protected void preDestroy(Db instance)");
        source.contains("instance.disconnect()");
        source.contains("protected boolean hasPreDestroy()");
        source.contains("return true");
    }

    @Test
    void prototypeWithPostConstructOnlyGeneratesNoPreDestroy() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Task", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Task {
                    @Inject
                    public Task() {}

                    @PostConstruct
                    void start() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Task$$Factory")
                .contentsAsUtf8String();
        source.contains("instance.start()");
        source.doesNotContain("hasPreDestroy");
    }

    @Test
    void assistedBeanCallsPostConstructInCreate() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Report {
                    @Inject
                    public Report(@Assisted String id) {}

                    @PostConstruct
                    void render() {}
                }
                """);
        JavaFileObject factory = JavaFileObjects.forSourceString("example.ReportFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean, factory);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$ReportFactory$$Impl")
                .contentsAsUtf8String()
                .contains("instance.render()");
    }

    @Test
    void preDestroyOnUnscopedBeanIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PreDestroy;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PreDestroy
                    void stop() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("requires a scoped bean");
    }

    @Test
    void preDestroyOnAssistedBeanIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.PreDestroy;

                public class Bad {
                    @Inject
                    public Bad(@Assisted String id) {}

                    @PreDestroy
                    void stop() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("caller-owned");
    }

    @Test
    void twoPostConstructMethodsIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PostConstruct
                    void a() {}

                    @PostConstruct
                    void b() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("at most one");
    }

    @Test
    void callbackWithParametersIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PostConstruct
                    void start(String x) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("callback");
    }

    @Test
    void privateCallbackIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PostConstruct
                    private void start() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("callback");
    }
}
```

- [ ] **Step 2: RED**

Run: `./mvnw clean verify > /tmp/l3.log 2>&1; echo EXIT=$?` — Expected: EXIT=1 (callbacks ungenerated; errors unreported).

- [ ] **Step 3: Implement**

`LifecycleCallbacks.java`:

```java
package be.imgn.inject.processor;

import org.jspecify.annotations.Nullable;

/**
 * The lifecycle callback method names declared on a bean.
 *
 * @param postConstruct the {@code @PostConstruct} method name, or {@code null}
 * @param preDestroy the {@code @PreDestroy} method name, or {@code null}
 */
record LifecycleCallbacks(@Nullable String postConstruct, @Nullable String preDestroy) {}
```

`ModelFactory` — add gathering, validation, and a pure reader (imports: `be.imgn.inject.PostConstruct`, `be.imgn.inject.PreDestroy`, `javax.lang.model.type.TypeKind`):

```java
    /**
     * {@return the bean's validated lifecycle callbacks} Reports callback-shape, count, and scope/assisted misuse.
     *
     * @param bean the bean type
     * @param scoped whether the bean has a scope
     * @param assisted whether the bean is assisted (caller-owned)
     */
    LifecycleCallbacks lifecycleCallbacks(TypeElement bean, boolean scoped, boolean assisted) {
        ExecutableElement postConstruct = singleCallback(bean, PostConstruct.class);
        ExecutableElement preDestroy = singleCallback(bean, PreDestroy.class);
        if (preDestroy != null) {
            if (assisted) {
                diagnostics.error(
                        "@PreDestroy is not allowed on an assisted bean; assisted instances are caller-owned.",
                        preDestroy);
            } else if (!scoped) {
                diagnostics.error(
                        "@PreDestroy requires a scoped bean; an unscoped bean is never destroyed by the container.",
                        preDestroy);
            }
        }
        return new LifecycleCallbacks(name(postConstruct), name(preDestroy));
    }

    /** {@return the bean's @PostConstruct method name without reporting diagnostics (generation-time reader)} */
    @Nullable String postConstructName(TypeElement bean) {
        return name(firstCallback(bean, PostConstruct.class));
    }

    private @Nullable ExecutableElement singleCallback(TypeElement bean, Class<? extends Annotation> annotation) {
        ExecutableElement found = null;
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || enclosed.getAnnotation(annotation) == null) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            if (found != null) {
                diagnostics.error(
                        "At most one @" + annotation.getSimpleName() + " method is allowed per class.", method);
                continue;
            }
            if (!isValidCallback(method)) {
                diagnostics.error(
                        "A @" + annotation.getSimpleName()
                                + " callback must be a non-private, non-static, no-argument void method.",
                        method);
            }
            found = method;
        }
        return found;
    }

    private static @Nullable ExecutableElement firstCallback(TypeElement bean, Class<? extends Annotation> annotation) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && enclosed.getAnnotation(annotation) != null) {
                return (ExecutableElement) enclosed;
            }
        }
        return null;
    }

    private static boolean isValidCallback(ExecutableElement method) {
        return method.getReturnType().getKind() == TypeKind.VOID
                && method.getParameters().isEmpty()
                && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }

    private static @Nullable String name(@Nullable ExecutableElement method) {
        return method == null ? null : method.getSimpleName().toString();
    }
```

In `buildModel`, compute callbacks and thread them into the model. After the existing `MemberInjection members = members(bean);` line add:

```java
        LifecycleCallbacks callbacks = lifecycleCallbacks(bean, Annotations.scope(bean).isPresent(), false);
```

and pass `callbacks.postConstruct(), callbacks.preDestroy()` as the two new trailing `BeanModel` arguments.

`BeanModel.java` — append two components (and document them):

```java
        @Nullable ContributionModel contribution,
        @Nullable String postConstruct,
        @Nullable String preDestroy) {}
```

Every `new BeanModel(...)` site must pass them: `buildModel` passes `callbacks.postConstruct()`/`callbacks.preDestroy()`; `defaultModel` passes `null, null` (a default no-arg bean has no @Inject members and validation there is unnecessary — but still gather to honor callbacks on such beans: in `defaultModel` add `LifecycleCallbacks callbacks = lifecycleCallbacks(type, Annotations.scope(type).isPresent(), false);` and pass its names).

`GatherStage` — assisted beans are diverted before `buildModel`, so validate their lifecycle in the assisted branch. Where `models.validateAssistedRecipe(bean)` is called for an assisted constructor bean (a `TypeElement`), add:

```java
                models.lifecycleCallbacks(bean, false, true); // validate: @PreDestroy on assisted → error
```

(The return is ignored; the assisted generator re-reads `postConstructName`.)

`BeanFactoryGenerator` — in `generate`, after the injection methods, emit the overrides:

```java
        if (model.postConstruct() != null) {
            type.addMethod(postConstruct(model));
        }
        if (model.preDestroy() != null) {
            type.addMethod(preDestroy(model)).addMethod(hasPreDestroy());
        }
```

(place this block before the `contribution`/`exposedKeys` block) and add the builders:

```java
    private static MethodSpec postConstruct(BeanModel model) {
        return MethodSpec.methodBuilder("postConstruct")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(model.beanType(), "instance")
                .addStatement("instance.$L()", model.postConstruct())
                .build();
    }

    private static MethodSpec preDestroy(BeanModel model) {
        return MethodSpec.methodBuilder("preDestroy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(model.beanType(), "instance")
                .addStatement("instance.$L()", model.preDestroy())
                .build();
    }

    private static MethodSpec hasPreDestroy() {
        return MethodSpec.methodBuilder("hasPreDestroy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.BOOLEAN)
                .addStatement("return true")
                .build();
    }
```

`AssistedFactoryGenerator.constructorBody` — after the field/plugin loops and before `return instance`, emit the recipe's `@PostConstruct` call. The generator has a `ModelFactory models` reference (it already calls `models.members(...)`/`models.recipeParameters(...)`); add:

```java
        String postConstruct = models.postConstructName(recipe.owner());
        if (postConstruct != null) {
            method.addStatement("instance.$L()", postConstruct);
        }
        method.addStatement("return instance");
```

(Thread `models` into `constructorBody` if it is currently `static` — pass it as a parameter, mirroring how `members`/`recipeParameters` are already accessed at the call site.)

- [ ] **Step 4: GREEN, commit**

Run: `./mvnw clean verify > /tmp/l3.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

```bash
git add -A
git commit -m "feat(processor): generate and validate lifecycle callbacks

Gather one @PostConstruct and one @PreDestroy per bean, validating callback
shape, count, and that @PreDestroy targets a scoped, non-assisted bean.
Generated factories override postConstruct/preDestroy/hasPreDestroy; assisted
create() calls the recipe's @PostConstruct last."
```

---

### Task 4: Classpath end-to-end example

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Ledger.java`
- Modify: `inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java`

**Interfaces:**
- Consumes: Tasks 1-3 through the real processor and runtime.
- Produces: `Ledger` — a `@Singleton` with both callbacks, exercised via try-with-resources.

- [ ] **Step 1: Write the bean**

```java
// Ledger.java
package be.imgn.inject.examples;

import java.util.ArrayList;
import java.util.List;

import be.imgn.inject.Inject;
import be.imgn.inject.PostConstruct;
import be.imgn.inject.PreDestroy;
import be.imgn.inject.Singleton;

/** A singleton recording its own lifecycle, to demonstrate @PostConstruct and @PreDestroy. */
@Singleton
public final class Ledger {
    private final List<String> events = new ArrayList<>();

    /** Creates an empty ledger. */
    @Inject
    public Ledger() {}

    /** {@return the recorded lifecycle events, in order} */
    public List<String> events() {
        return events;
    }

    @PostConstruct
    void opened() {
        events.add("opened");
    }

    @PreDestroy
    void closed() {
        events.add("closed");
    }
}
```

- [ ] **Step 2: Add the test to `ExamplesClasspathTest`**

```java
    @Test
    void lifecycleCallbacksRunOnConstructionAndClose() {
        Ledger ledger;
        try (Injector app = Injector.create()) {
            ledger = app.getInstance(Ledger.class);
            assertThat(ledger.events()).containsExactly("opened"); // @PostConstruct ran during build
        }
        // close() invoked @PreDestroy on the created singleton
        assertThat(ledger.events()).containsExactly("opened", "closed");
    }
```

- [ ] **Step 3: Full verify**

Run: `./mvnw clean verify > /tmp/l4.log 2>&1; echo EXIT=$?` — Expected: EXIT=0; `grep -E "Passed: 3" /tmp/l4.log` shows the invoker ITs pass.

- [ ] **Step 4: Commit**

```bash
git add inject-examples
git commit -m "feat(examples): singleton exercising @PostConstruct and @PreDestroy"
```

---

## Self-Review Notes

- Spec coverage: `@PostConstruct` last in build (T1 `postConstructRunsLastInBuild`, T3 golden, T4), close destroys scoped LIFO (T2), untracked prototype never destroyed (T2), collect-suppressed exception policy (T2), idempotent close + post-close resolution ISE (T2), child close no-op not touching parent (T2 addition to ChildContextTest), assisted create calls @PostConstruct (T3), assisted @PreDestroy error (T3), every error-table row (T3), e2e try-with-resources (T4). `@Provides`-product exclusion is delivered by never generating callbacks for provides factories (nothing added there) — no test needed, no code path.
- Type consistency: `LifecycleCallbacks(postConstruct, preDestroy)` produced by `lifecycleCallbacks(...)`, consumed by `buildModel`/`defaultModel` into `BeanModel`; `postConstructName(TypeElement)` (pure) consumed only by `AssistedFactoryGenerator.constructorBody`; `Factory.postConstruct/preDestroy/hasPreDestroy` (T1) overridden by `BeanFactoryGenerator` (T3) and called by `DefaultContext` (T2); `Context.close`/`Injector.close`/`DefaultContext.close`/`ChildContext.close` all `void`.
- Known ripple: `Injector extends AutoCloseable` — verify no existing caller treated `Injector` as non-closeable in a way that breaks (it only adds a method); `BeanModel`'s two new trailing components ripple through `buildModel`, `defaultModel`, and any other `new BeanModel(` site (grep before editing).
- Module-path: unaffected — no new ServiceLoader services; the existing `inject-examples-module` needs no change (a lifecycle example there would only re-prove generation already covered).
