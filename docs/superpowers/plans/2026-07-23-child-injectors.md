# Child Injectors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `parent.childBuilder().bind(...).bindProvider(...).build()` — request/session/test-local bindings layered over a parent injector, with shadowing for prototypes and scope anchoring for singletons.

**Architecture:** Pure runtime feature. A `ChildContext implements Context` holds the parent context, a local binding map, and its own resolution stack: local hit → local provider; parent `BeanDefinition` present and unscoped (no `ScopingAnnotation` stereotype in its metadata) → rebuild via `factory.build(childContext)` under the child's cycle guard; otherwise delegate to the parent (anchoring). A shared `ResolutionStack` is extracted from `DefaultContext` first so both contexts guard cycles the same way. Spec: `docs/superpowers/specs/2026-07-23-child-injectors-design.md`.

**Tech Stack:** Java 21, Maven wrapper, JUnit + AssertJ. Processor untouched.

## Global Constraints

- Build: `./mvnw clean verify` — NEVER `install`. Judge by Maven's own exit code (`> /tmp/x.log 2>&1; echo EXIT=$?`), never through a pipe. `-pl inject` fine for runtime-only iteration; full reactor before each commit.
- Checkstyle `NoFQNInCode` — always import. NullAway is ERROR (`@org.jspecify.annotations.Nullable`). Javadoc on every public API member, present tense. AssertJ fluent chaining.
- Package boundaries: `be.imgn.inject` (Injector, Builder, ChildBuilder, Key, …) vs `be.imgn.inject.context` (Context, DefaultContext, ChildContext, Factory, …). `DefaultContext`'s hand-wiring constructors (`(factories, scopes, …)`) are package-private — tests needing them live in `be.imgn.inject.context`.

---

### Task 1: Extract `ResolutionStack` (behavior-preserving refactor)

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/context/ResolutionStack.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/CycleGuardProvider.java`

**Interfaces:**
- Consumes: current `DefaultContext` cycle plumbing (`ThreadLocal<Deque<Key<?>>> stack`, `enter(Key)`, `exit(Key)`, `requiredBySuffix()`) and `CycleGuardProvider`'s constructor — READ BOTH FILES FIRST; the integration below names the current members, adjust to what you find.
- Produces: `final class ResolutionStack` (package-private, `be.imgn.inject.context`) with `void enter(Key<?>)` (throws `CircularDependencyException` with the full path on re-entry), `void exit()`, `String requiredBySuffix()` (empty string when the stack is empty, else `" (required by a -> b)"`). Task 2's `ChildContext` instantiates its own.

- [ ] **Step 1: Write the class**

```java
package be.imgn.inject.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.StringJoiner;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.Key;

/**
 * The per-thread resolution path of one context: detects dependency cycles and renders the requester chain for
 * missing-binding errors.
 */
final class ResolutionStack {
    private final ThreadLocal<Deque<Key<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Pushes {@code key}, first failing on a cycle.
     *
     * @param key the key being resolved
     * @throws CircularDependencyException if {@code key} is already being resolved on this thread
     */
    void enter(Key<?> key) {
        Deque<Key<?>> current = stack.get();
        if (current.contains(key)) {
            StringJoiner path = new StringJoiner(" -> ");
            current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
            path.add(key.toString());
            throw new CircularDependencyException("Dependency cycle: " + path);
        }
        current.push(key);
    }

    /** Pops the innermost key. */
    void exit() {
        stack.get().pop();
    }

    /** {@return the requester-chain suffix for error messages; empty when nothing is resolving} */
    String requiredBySuffix() {
        Deque<Key<?>> current = stack.get();
        if (current.isEmpty()) {
            return "";
        }
        StringJoiner path = new StringJoiner(" -> ");
        current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
        return " (required by " + path + ")";
    }
}
```

- [ ] **Step 2: Repoint `DefaultContext` and `CycleGuardProvider`**

- `DefaultContext`: replace the `ThreadLocal<Deque<Key<?>>> stack` field with `private final ResolutionStack stack = new ResolutionStack();`; its `enter(key)`/`exit(key)` methods and `requiredBySuffix()` delegate to (or are replaced by) the stack — whatever `CycleGuardProvider` calls today, keep the call-shape working by handing it the `ResolutionStack` instead of the `DefaultContext`. Move the cycle-path construction OUT of `DefaultContext` (it now lives in `ResolutionStack.enter`).
- `CycleGuardProvider`: change its context/callback parameter to `ResolutionStack` and call `stack.enter(key)` / `stack.exit()` around the delegate `get()`. Keep the class package-private; Task 2 reuses it for child rebuilds.
- All existing tests must pass unchanged — this is pure motion. If `DefaultContext`'s current code differs from the description (member names, where the cycle exception is built), adapt the motion, not the behavior.

- [ ] **Step 3: Verify GREEN, commit**

Run: `./mvnw clean verify > /tmp/c1.log 2>&1; echo EXIT=$?` — Expected: EXIT=0, zero behavior change (existing `DefaultContextTest.cycleThrowsWithPath` and `missingTransitiveDependencyReportsRequesterChain` are the guards).

```bash
git add -A
git commit -m "refactor(context): extract ResolutionStack from DefaultContext

Pure motion: the per-thread cycle stack and requester-chain rendering move
into their own class so the upcoming child contexts can guard their
resolution paths with the same machinery."
```

---

### Task 2: `ChildContext`, `ChildBuilder`, `Injector.childBuilder()`, root `bindProvider`

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/context/ChildContext.java`
- Modify: `inject/src/main/java/be/imgn/inject/Injector.java` (interface method + `ChildBuilder` nested class + `Builder.bindProvider` + `requireBindable` visibility)
- Modify: `inject/src/main/java/be/imgn/inject/DefaultInjector.java` (plain `(Context)` constructor; implement `childBuilder()`)
- Test: `inject/src/test/java/be/imgn/inject/context/ChildContextTest.java`
- Test: `inject/src/test/java/be/imgn/inject/ChildInjectorTest.java`

**Interfaces:**
- Consumes: `ResolutionStack`, `CycleGuardProvider` (Task 1), `Context.getBeanDefinition`, `AnnotationMetadata.hasStereotype`, `ScopingAnnotation`, `Factory.build(Context)` (public), existing `requireBindable`.
- Produces: `public final class ChildContext implements Context` with `public ChildContext(Context parent, Map<Key<?>, Provider<?>> bindings)` and `public void setInjector(Injector)`; `Injector.childBuilder()` returning `Injector.ChildBuilder` (`bind(Class,T)`, `bind(Key,T)`, `bindProvider(Class,Provider)`, `bindProvider(Key,Provider)`, `build()`); `Injector.Builder.bindProvider(Class,Provider)`/`(Key,Provider)`.

- [ ] **Step 1: Write the failing tests**

`ChildContextTest` (package `be.imgn.inject.context` — needs the hand-wiring `DefaultContext` constructors):

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.Singleton;
import be.imgn.inject.UnsatisfiedDependencyException;

class ChildContextTest {

    /** An unscoped bean holding the String dependency it was built with. */
    record Holder(String value) {}

    /** Unscoped factory: Holder(String) — the rebuild path must let children re-resolve the String. */
    static final class HolderFactory extends Factory<Holder> {
        HolderFactory() {
            super(Holder.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("value", String.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Holder createInstance(Context context) {
            return new Holder(getInstanceForConstructor(0, context));
        }
    }

    /** Singleton flavor of the same shape: must anchor to the parent. */
    static final class SingletonHolderFactory extends Factory<Holder> {
        SingletonHolderFactory() {
            super(Holder.class);
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return AnnotationMetadata.of(AnnotationValue.of(
                    Singleton.class, Map.of(), java.util.Set.of(be.imgn.inject.ScopingAnnotation.class)));
        }

        @Override
        protected Optional<Class<? extends java.lang.annotation.Annotation>> scope() {
            return Optional.of(Singleton.class);
        }

        @Override
        protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
            return Optional.of(new ConstructorInjectionPoint(
                    this, List.of(new Parameter("value", String.class)), AnnotationMetadata.empty()));
        }

        @Override
        protected Holder createInstance(Context context) {
            return new Holder(getInstanceForConstructor(0, context));
        }
    }

    /** Leaf String bound in the parent. */
    static final class ParentStringFactory extends Factory<String> {
        ParentStringFactory() {
            super(String.class);
        }

        @Override
        protected String createInstance(Context context) {
            return "parent";
        }
    }

    private static ChildContext child(Context parent, Map<Key<?>, Provider<?>> bindings) {
        ChildContext context = new ChildContext(parent, bindings);
        context.setInjector(Injector.builder().build());
        return context;
    }

    @Test
    void localBindingWins() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory()), List.of());
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> "child"));
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("child");
        assertThat(parent.getProvider(Key.of(String.class)).get()).isEqualTo("parent");
    }

    @Test
    void unscopedParentBeanRebuildsWithChildBindings() {
        DefaultContext parent =
                new DefaultContext(List.of(new ParentStringFactory(), new HolderFactory()), List.of());
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> "child"));
        Holder viaChild = (Holder) context.getProvider(Key.of(Holder.class)).get();
        Holder viaParent = (Holder) parent.getProvider(Key.of(Holder.class)).get();
        assertThat(viaChild.value()).isEqualTo("child");
        assertThat(viaParent.value()).isEqualTo("parent");
    }

    @Test
    void scopedParentBeanAnchorsToParent() {
        DefaultContext parent = new DefaultContext(
                List.of(new ParentStringFactory(), new SingletonHolderFactory()),
                List.of(new SingletonScope()));
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> "child"));
        Holder viaChild = (Holder) context.getProvider(Key.of(Holder.class)).get();
        assertThat(viaChild.value()).isEqualTo("parent");
        assertThat(viaChild).isSameAs(parent.getProvider(Key.of(Holder.class)).get());
    }

    @Test
    void missReportsAfterChainMiss() {
        DefaultContext parent = new DefaultContext(List.of(), List.of());
        ChildContext context = child(parent, Map.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get());
    }

    @Test
    void cycleThroughChildRebuildIsReported() {
        // Holder depends on String; bind String through a provider that resolves Holder -> cycle via the child.
        DefaultContext parent = new DefaultContext(List.of(new HolderFactory()), List.of());
        ChildContext[] hole = new ChildContext[1];
        ChildContext context = child(parent, Map.of(Key.of(String.class), () -> {
            hole[0].getProvider(Key.of(Holder.class)).get();
            return "unreachable";
        }));
        hole[0] = context;
        assertThatExceptionOfType(CircularDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Holder.class)).get());
    }

    @Test
    void injectorKeyResolvesTheAttachedInjector() {
        DefaultContext parent = new DefaultContext(List.of(), List.of());
        ChildContext context = new ChildContext(parent, Map.of());
        Injector attached = Injector.builder().build();
        context.setInjector(attached);
        assertThat(context.getProvider(Key.of(Injector.class)).get()).isSameAs(attached);
        assertThatIllegalStateException().isThrownBy(() -> context.setInjector(attached));
    }

    @Test
    void introspectionDelegatesToParent() {
        DefaultContext parent = new DefaultContext(List.of(new ParentStringFactory()), List.of());
        ChildContext context = child(parent, Map.of(Key.of(Integer.class), () -> 1));
        assertThat(context.getBeanDefinition(Key.of(String.class))).isPresent();
        assertThat(context.getBeanDefinition(Key.of(Integer.class))).isEmpty();
    }
}
```

(Fix the two inline FQNs in `SingletonHolderFactory` — import `java.lang.annotation.Annotation`, `java.util.Set`, `be.imgn.inject.ScopingAnnotation`. NoFQNInCode.)

`ChildInjectorTest` (package `be.imgn.inject` — public API surface):

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;

class ChildInjectorTest {

    @Test
    void childSeesOwnAndParentBindings() {
        Injector parent = Injector.builder().bind(String.class, "parent").build();
        Injector child = parent.childBuilder().bind(Integer.class, 7).build();
        assertThat(child.getInstance(Integer.class)).isEqualTo(7);
        assertThat(child.getInstance(String.class)).isEqualTo("parent");
    }

    @Test
    void childShadowsParentWithoutAffectingIt() {
        Injector parent = Injector.builder().bind(String.class, "parent").build();
        Injector child = parent.childBuilder().bind(String.class, "child").build();
        assertThat(child.getInstance(String.class)).isEqualTo("child");
        assertThat(parent.getInstance(String.class)).isEqualTo("parent");
    }

    @Test
    void bindProviderIsFreshPerResolution() {
        AtomicInteger counter = new AtomicInteger();
        Injector child = Injector.create()
                .childBuilder()
                .bindProvider(Integer.class, counter::incrementAndGet)
                .build();
        assertThat(child.getInstance(Integer.class)).isEqualTo(1);
        assertThat(child.getInstance(Integer.class)).isEqualTo(2);
    }

    @Test
    void nestedChildrenResolveNearestWins() {
        Injector parent = Injector.builder().bind(String.class, "root").build();
        Injector child = parent.childBuilder().bind(String.class, "mid").build();
        Injector grandchild = child.childBuilder().bind(Integer.class, 1).build();
        assertThat(grandchild.getInstance(String.class)).isEqualTo("mid");
        assertThat(grandchild.getInstance(Integer.class)).isEqualTo(1);
    }

    @Test
    void injectorThroughChildIsTheChild() {
        Injector parent = Injector.create();
        Injector child = parent.childBuilder().build();
        assertThat(child.getInstance(Injector.class)).isSameAs(child);
        assertThat(parent.getInstance(Injector.class)).isSameAs(parent);
    }

    @Test
    void doubleBindInOneChildBuilderThrows() {
        Injector.ChildBuilder builder = Injector.create().childBuilder().bind(String.class, "a");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bind(String.class, "b"))
                .withMessageContaining("already bound");
    }

    @Test
    void childBuilderRejectsFrameworkTypes() {
        Injector.ChildBuilder builder = Injector.create().childBuilder();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.bind(Injector.class, Injector.create()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.bindProvider(Context.class, () -> null));
        // Factory rejection mirrors InjectorInjectionTest's raw-Key approach.
    }

    @Test
    void rootBuilderBindProviderWorks() {
        AtomicInteger counter = new AtomicInteger();
        Injector injector = Injector.builder()
                .bindProvider(Integer.class, counter::incrementAndGet)
                .build();
        assertThat(injector.getInstance(Integer.class)).isEqualTo(1);
        assertThat(injector.getInstance(Integer.class)).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("NullAway")
    void childBuilderRejectsNulls() {
        Injector.ChildBuilder builder = Injector.create().childBuilder();
        assertThatNullPointerException().isThrownBy(() -> builder.bind(String.class, null));
        assertThatNullPointerException().isThrownBy(() -> builder.bindProvider(String.class, null));
    }
}
```

(`bindProvider(Context.class, () -> null)` — the rejection fires before the provider runs, `() -> null` never evaluated; if NullAway complains about the lambda, use `() -> { throw new UnsupportedOperationException(); }`. The `Factory` raw-Key rejection line: copy the working pattern from `InjectorInjectionTest.builderRejectsFrameworkTypes`.)

- [ ] **Step 2: RED**

Run: `./mvnw -q clean test -pl inject > /tmp/c2.log 2>&1; echo EXIT=$?` — Expected: EXIT=1 (compile errors: no `ChildContext`, no `childBuilder()`).

- [ ] **Step 3: Implement**

`ChildContext.java`:

```java
package be.imgn.inject.context;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopingAnnotation;

/**
 * A child {@link Context}: local instance/provider bindings first, then the parent. Unscoped parent beans are
 * rebuilt with this context so their dependencies see child bindings; scoped beans and everything without a
 * {@link BeanDefinition} anchor to the parent unchanged.
 */
public final class ChildContext implements Context {

    private static final Key<?> INJECTOR_KEY = Key.of(Injector.class);

    private final Context parent;
    private final Map<Key<?>, Provider<?>> bindings;
    private final ResolutionStack stack = new ResolutionStack();
    private @Nullable Injector injector;

    /**
     * @param parent the parent context resolutions fall back to
     * @param bindings the child's own bindings, by key
     */
    public ChildContext(Context parent, Map<Key<?>, Provider<?>> bindings) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }

    /**
     * Attaches the {@link Injector} this context resolves {@code Key.of(Injector.class)} to. Called once by the
     * child injector that owns this context, immediately after construction.
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

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> getProvider(Key<T> key) {
        Objects.requireNonNull(key, "key");
        if (INJECTOR_KEY.equals(key)) {
            return (Provider<T>) (Provider<Injector>) this::requireInjector;
        }
        Provider<?> local = bindings.get(key);
        if (local != null) {
            return (Provider<T>) local;
        }
        Optional<BeanDefinition> definition = parent.getBeanDefinition(key);
        if (definition.isPresent()
                && definition.get() instanceof Factory<?> factory
                && !definition.get().annotationMetadata().hasStereotype(ScopingAnnotation.class)) {
            // Unscoped parent bean: rebuild here so its dependencies resolve through this child.
            return new CycleGuardProvider<>(key, (Provider<T>) (Provider<?>) () -> factory.build(this), stack);
        }
        return parent.getProvider(key);
    }

    @Override
    public Optional<BeanDefinition> getBeanDefinition(Key<?> key) {
        return parent.getBeanDefinition(key);
    }

    @Override
    public List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype) {
        return parent.getBeanDefinitionsByStereotype(stereotype);
    }

    private Injector requireInjector() {
        Injector attached = injector;
        if (attached == null) {
            throw new IllegalStateException("There is no Injector attached to this Context.");
        }
        return attached;
    }
}
```

(The double-cast on the rebuild provider may need shaping against `CycleGuardProvider`'s actual generic signature — read it; the intent is a `Provider<T>` whose `get()` is `factory.build(this)`.)

`Injector.java`:
- Interface gains:

```java
    /** {@return a builder for a child of this injector: its bindings layer over this injector's graph} */
    ChildBuilder childBuilder();
```

- `Builder`: add the two `bindProvider` overloads (same null checks + `requireBindable`, storing the provider as-is; keep the existing last-wins `overrides.put`); change `requireBindable` from `private` to package-private `static void requireBindable(Key<?> key)` so `ChildBuilder` shares it.
- New nested class:

```java
    /**
     * Builds a child {@link Injector}: explicit instance and provider bindings layered over the parent's graph.
     * Shadowing a parent binding affects resolutions through the child only; binding the same key twice in one
     * builder is an error.
     */
    final class ChildBuilder {
        private final Context parentContext;
        private final Map<Key<?>, Provider<?>> bindings = new HashMap<>();

        ChildBuilder(Context parentContext) {
            this.parentContext = parentContext;
        }

        /**
         * {@return this builder, binding {@code instance} as the unqualified key for {@code type}}
         *
         * @param type the bound type
         * @param instance the instance to return for that type
         * @param <T> the bound type
         */
        public <T> ChildBuilder bind(Class<T> type, T instance) {
            return bind(Key.of(type), instance);
        }

        /**
         * {@return this builder, binding {@code instance} to {@code key}}
         *
         * @param key the binding key
         * @param instance the instance to return for that key
         * @param <T> the bound type
         */
        public <T> ChildBuilder bind(Key<T> key, T instance) {
            Objects.requireNonNull(instance, "instance");
            return bindProvider(key, () -> instance);
        }

        /**
         * {@return this builder, binding a provider evaluated on every resolution through the child}
         *
         * @param type the bound type
         * @param provider the provider to consult for that type
         * @param <T> the bound type
         */
        public <T> ChildBuilder bindProvider(Class<T> type, Provider<T> provider) {
            return bindProvider(Key.of(type), provider);
        }

        /**
         * {@return this builder, binding a provider evaluated on every resolution through the child}
         *
         * @param key the binding key
         * @param provider the provider to consult for that key
         * @param <T> the bound type
         */
        public <T> ChildBuilder bindProvider(Key<T> key, Provider<T> provider) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(provider, "provider");
            Builder.requireBindable(key);
            if (bindings.putIfAbsent(key, provider) != null) {
                throw new IllegalArgumentException(key + " is already bound in this builder.");
            }
            return this;
        }

        /** {@return the child injector applying these bindings over the parent} */
        public Injector build() {
            ChildContext context = new ChildContext(parentContext, bindings);
            DefaultInjector child = new DefaultInjector(context);
            context.setInjector(child);
            return child;
        }
    }
```

(import `be.imgn.inject.context.ChildContext`.) NOTE: `bind(Key, T)` routes through `bindProvider`, so its null-instance check happens before the wrap.

`DefaultInjector.java`:
- Constructor becomes plain `DefaultInjector(Context context) { this.context = Objects.requireNonNull(context, "context"); }` — NO attach inside. The two existing call sites attach explicitly:

```java
    // Injector.create():
    static Injector create() {
        DefaultContext context = new DefaultContext();
        DefaultInjector injector = new DefaultInjector(context);
        context.setInjector(injector);
        return injector;
    }

    // Builder.build():
    public Injector build() {
        DefaultContext context = new DefaultContext(Map.copyOf(overrides));
        DefaultInjector injector = new DefaultInjector(context);
        context.setInjector(injector);
        return injector;
    }
```

- Implement the new interface method:

```java
    @Override
    public ChildBuilder childBuilder() {
        return new ChildBuilder(context);
    }
```

- [ ] **Step 4: GREEN, commit**

Run: `./mvnw -q clean test -pl inject > /tmp/c2.log 2>&1; echo EXIT=$?` then full reactor `./mvnw clean verify > /tmp/c2full.log 2>&1; echo EXIT=$?` — Expected: both EXIT=0.

```bash
git add -A
git commit -m "feat(inject): child injectors - instance/provider layers over a parent

parent.childBuilder().bind(...).bindProvider(...).build() creates a child
whose bindings win for resolutions through it: unscoped parent beans are
rebuilt with the child as resolution context (their dependencies see child
bindings), scoped beans anchor to the parent's cache, and the parent's graph
is never touched. Injector through a child resolves to the child. The root
builder gains the same bindProvider surface; double-binding one key in one
child builder is an error."
```

---

### Task 3: End-to-end example + final sweep

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/RequestContext.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/RequestGreeter.java`
- Modify: `inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java`

```java
// RequestContext.java
package be.imgn.inject.examples;

/** Request-local data bound into a child injector per request. */
public record RequestContext(String user) {}
```

```java
// RequestGreeter.java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;

/** Unscoped bean combining an application singleton with request-local data from the child injector. */
public final class RequestGreeter {
    private final Registry registry;
    private final RequestContext request;

    /**
     * @param registry the application-wide registry
     * @param request the request-local context
     */
    @Inject
    public RequestGreeter(Registry registry, RequestContext request) {
        this.registry = registry;
        this.request = request;
    }

    /** {@return the application-wide registry} */
    public Registry registry() {
        return registry;
    }

    /** {@return the request-local context} */
    public RequestContext request() {
        return request;
    }
}
```

Test to add to `ExamplesClasspathTest`:

```java
    @Test
    void childInjectorLayersRequestData() {
        Injector app = Injector.create();
        Injector requestA = app.childBuilder()
                .bind(RequestContext.class, new RequestContext("ada"))
                .build();
        Injector requestB = app.childBuilder()
                .bind(RequestContext.class, new RequestContext("bob"))
                .build();

        RequestGreeter greeterA = requestA.getInstance(RequestGreeter.class);
        RequestGreeter greeterB = requestB.getInstance(RequestGreeter.class);
        assertThat(greeterA.request().user()).isEqualTo("ada");
        assertThat(greeterB.request().user()).isEqualTo("bob");
        // the singleton registry is shared across children (scope anchoring)
        assertThat(greeterA.registry())
                .isSameAs(greeterB.registry())
                .isSameAs(app.getInstance(Registry.class));
    }
```

Note: `RequestGreeter` has no binding for `RequestContext` at the APP level — resolving it via `app` directly would
fail. That is exactly the point (request data exists only in children); resolving through the children succeeds
because the unscoped `RequestGreeter` rebuilds with the child context. The compile-time `PlanStage` default-factory
machinery WILL see `RequestContext` as an in-compilation dependency with an accessible… it's a record with a
required arg, so no accessible no-arg constructor and no `@Inject` — the processor will report "Cannot satisfy
dependency". AVOID that: give `RequestContext` an `@Inject`-free shape the planner accepts — bind it in the graph
via the child only. The clean way: `RequestContext` is a plain record (no annotations) and `RequestGreeter` injects
it; the planner demands a default factory → error. SOLUTION: mark the dependency as child-supplied by making
`RequestGreeter` take `Provider<RequestContext>`? Same planning issue. The honest v1 answer: `RequestContext` must
be constructible or bound at compile time — give it a public no-arg constructor default and let the child SHADOW
it:

```java
// RequestContext.java (final shape)
package be.imgn.inject.examples;

/** Request-local data; the application default is shadowed by each request's child injector. */
public record RequestContext(String user) {
    /** The application-level default: no user. */
    public RequestContext() {
        this("anonymous");
    }
}
```

With the no-arg constructor the planner generates a default factory (app-level default "anonymous"), and each child
shadows it — which also makes the example richer: assert `app.getInstance(RequestGreeter.class).request().user()`
is `"anonymous"`. ADD that assertion to the test above.

Run: `./mvnw clean verify > /tmp/c3.log 2>&1; echo EXIT=$?` — Expected: EXIT=0; `grep -E "Passed: 3" /tmp/c3.log` shows invoker ITs pass.

```bash
git add inject-examples
git commit -m "feat(examples): request-scoped child injectors shadowing an app default"
```

---

## Self-Review Notes

- Spec coverage: resolution order + shadowing (T2 tests 1-2, T3), provider freshness (T2), scope anchoring (T2 `scopedParentBeanAnchorsToParent`, T3 registry assertion), nested children (T2), injector self-binding both levels (T2), framework-type rejection + double-bind + nulls (T2), chain-miss error + cycle through rebuild (T2 context tests), introspection delegation (T2), root `bindProvider` (T2), collections-anchor-to-parent falls out of the no-definition branch (documented in spec; not separately tested — the aggregate key has no BeanDefinition, hits the delegate branch exercised by `scopedParentBeanAnchorsToParent`'s sibling paths).
- Type consistency: `ChildContext(Context, Map<Key<?>, Provider<?>>)` consumed by `ChildBuilder.build()`; `ResolutionStack.enter/exit/requiredBySuffix` consumed by `DefaultContext`, `CycleGuardProvider`, `ChildContext`; `requireBindable` shared as `Builder` package-static.
- Known judgment point for the implementer: `CycleGuardProvider`'s current constructor shape is read-first; Task 1 hands it a `ResolutionStack` — Task 2's usage must match whatever Task 1 landed.
