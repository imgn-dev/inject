# Multibindings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Set<T>` and `Map<String, T>` collection bindings: beans contribute explicitly via `@IntoSet`/`@IntoMap`, `DefaultContext` aggregates at build time, injection sites request the collection.

**Architecture:** Explicit markers replace a bean's normal exposure with a single `Contribution` (collection `Key` + optional map key). `DefaultContext` groups contributing factories by collection key and registers one aggregate provider per group. The processor detects `Set<E>`/`Map<String,V>` at injection sites and renders keys with a new `Types` utility whose `ParameterizedType` equals/hashCode are structural (JDK-compatible), so generated keys equal `TypeLiteral`-captured ones. Spec: `docs/superpowers/specs/2026-07-21-multibindings-design.md`.

**Tech Stack:** Java 21, Maven (wrapper), palantir JavaPoet 0.7.0 (`com.palantir.javapoet`), google-compile-testing, JUnit + AssertJ.

## Global Constraints

- Build/verify command: `./mvnw clean verify` — NEVER `install`. Judge success by Maven's own exit code (`./mvnw clean verify > /tmp/build.log 2>&1; echo EXIT=$?`), never through a pipe.
- Checkstyle `NoFQNInCode`: no fully-qualified names in code — always import (this bites `java.io.File`, `java.util.List` etc. in new files).
- NullAway runs as ERROR: annotate nullable things `@org.jspecify.annotations.Nullable` (import it).
- Every public API member needs Javadoc, present tense, describing current state only (project rule).
- Tests: JUnit + AssertJ with fluent chaining — one `assertThat(x)` with chained assertions, not repeated `assertThat`.
- Spotless auto-formats on build; don't fight its output. If a golden-string assertion breaks on formatting, assert a shorter stable substring.
- Single-module test runs work for `inject` (`./mvnw -q clean test -pl inject`); anything involving `inject-processor` or examples needs the full reactor (`./mvnw clean verify`) because siblings resolve from the reactor, never from `~/.m2`.

---

### Task 1: `Types` utility

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/Types.java`
- Test: `inject/src/test/java/be/imgn/inject/TypesTest.java`

**Interfaces:**
- Consumes: `be.imgn.inject.TypeLiteral` (existing), `be.imgn.inject.Key` (existing).
- Produces: `public static Type Types.setOf(Class<?> elementType)` and `public static Type Types.mapOf(Class<?> keyType, Class<?> valueType)`. Tasks 3–7 build collection keys as `Key.of(Types.setOf(X.class))`.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TypesTest {
    @Test
    void setOfEqualsJdkCapturedTypeBothDirections() {
        Type ours = Types.setOf(String.class);
        Type jdk = new TypeLiteral<Set<String>>() {}.getType();
        assertThat(ours).isEqualTo(jdk).hasSameHashCodeAs(jdk);
        assertThat(jdk).isEqualTo(ours);
    }

    @Test
    void mapOfEqualsJdkCapturedTypeBothDirections() {
        Type ours = Types.mapOf(String.class, Integer.class);
        Type jdk = new TypeLiteral<Map<String, Integer>>() {}.getType();
        assertThat(ours).isEqualTo(jdk).hasSameHashCodeAs(jdk);
        assertThat(jdk).isEqualTo(ours);
    }

    @Test
    void keysBuiltFromEitherSideAreEqual() {
        assertThat(Key.of(Types.setOf(String.class)))
                .isEqualTo(Key.of(new TypeLiteral<Set<String>>() {}))
                .isNotEqualTo(Key.of(Types.setOf(Integer.class)));
    }

    @Test
    void distinctTypesAreNotEqual() {
        assertThat(Types.setOf(String.class))
                .isNotEqualTo(Types.setOf(Integer.class))
                .isNotEqualTo(new TypeLiteral<List<String>>() {}.getType());
    }

    @Test
    void hasReadableToString() {
        assertThat(Types.setOf(String.class).getTypeName()).contains("Set").contains("String");
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> Types.setOf(null));
        assertThatNullPointerException().isThrownBy(() -> Types.mapOf(null, String.class));
        assertThatNullPointerException().isThrownBy(() -> Types.mapOf(String.class, null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q clean test -pl inject > /tmp/t1.log 2>&1; echo EXIT=$?`
Expected: EXIT=1, log contains `cannot find symbol` for `Types`.

- [ ] **Step 3: Write the implementation**

```java
package be.imgn.inject;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Factories for the parameterized {@link Type}s used as collection binding keys.
 *
 * <p>{@link ParameterizedType} specifies no equality contract, so a type produced here implements structural
 * {@code equals}/{@code hashCode} compatible with the JDK's own implementation: a key built from
 * {@code Types.setOf(X.class)} equals one captured through {@code new TypeLiteral<Set<X>>() {}}.
 */
public final class Types {

    private Types() {}

    /**
     * {@return the type {@code Set<elementType>}}
     *
     * @param elementType the set's element type
     */
    public static Type setOf(Class<?> elementType) {
        return new ParameterizedTypeImpl(Set.class, requireNonNull(elementType, "elementType"));
    }

    /**
     * {@return the type {@code Map<keyType, valueType>}}
     *
     * @param keyType the map's key type
     * @param valueType the map's value type
     */
    public static Type mapOf(Class<?> keyType, Class<?> valueType) {
        return new ParameterizedTypeImpl(
                Map.class, requireNonNull(keyType, "keyType"), requireNonNull(valueType, "valueType"));
    }

    /** Structural {@link ParameterizedType}; equality and hash code match the JDK implementation's semantics. */
    private static final class ParameterizedTypeImpl implements ParameterizedType {
        private final Class<?> rawType;
        private final Type[] actualTypeArguments;

        ParameterizedTypeImpl(Class<?> rawType, Type... actualTypeArguments) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments.clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType other
                    && Objects.equals(getOwnerType(), other.getOwnerType())
                    && rawType.equals(other.getRawType())
                    && Arrays.equals(actualTypeArguments, other.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            // Matches the JDK's ParameterizedTypeImpl so registry lookups work across implementations.
            return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(getOwnerType()) ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            StringJoiner arguments = new StringJoiner(", ", "<", ">");
            for (Type argument : actualTypeArguments) {
                arguments.add(argument.getTypeName());
            }
            return rawType.getName() + arguments;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q clean test -pl inject > /tmp/t1.log 2>&1; echo EXIT=$?`
Expected: EXIT=0.

- [ ] **Step 5: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/Types.java inject/src/test/java/be/imgn/inject/TypesTest.java
git commit -m "feat(inject): Types.setOf/mapOf with JDK-compatible structural equality"
```

---

### Task 2: Markers, `Contribution`, `Factory.contribution()`

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/IntoSet.java`
- Create: `inject/src/main/java/be/imgn/inject/IntoMap.java`
- Create: `inject/src/main/java/be/imgn/inject/context/Contribution.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/Factory.java` (add one method after `exposedKeys()`)
- Test: `inject/src/test/java/be/imgn/inject/context/ContributionTest.java`

**Interfaces:**
- Consumes: `Key`, `Types` (Task 1).
- Produces: `@IntoSet(Class value)`, `@IntoMap(Class value, String key)`, `public record Contribution(Key<?> collectionKey, @Nullable String mapKey)`, `public Optional<Contribution> Factory.contribution()` (default empty). Task 3 reads `contribution()`; Task 5 generates overrides of it.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Types;

class ContributionTest {
    @Test
    void holdsCollectionKeyAndOptionalMapKey() {
        Key<?> key = Key.of(Types.setOf(String.class));
        assertThat(new Contribution(key, null))
                .returns(key, Contribution::collectionKey)
                .returns(null, Contribution::mapKey);
        assertThat(new Contribution(Key.of(Types.mapOf(String.class, String.class)), "auth").mapKey())
                .isEqualTo("auth");
    }

    @Test
    void factoriesContributeNothingByDefault() {
        Factory<Object> factory = new Factory<>(Object.class) {
            @Override
            protected Object createInstance(Context context) {
                return new Object();
            }
        };
        assertThat(factory.contribution()).isEmpty();
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNullCollectionKey() {
        assertThatNullPointerException().isThrownBy(() -> new Contribution(null, "x"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q clean test -pl inject > /tmp/t2.log 2>&1; echo EXIT=$?`
Expected: EXIT=1, `cannot find symbol` for `Contribution`.

- [ ] **Step 3: Write the implementations**

`IntoSet.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Contributes the annotated bean (or {@code @Provides} result) to the {@code Set<value>} binding instead of binding it
 * under its own keys. Inject the collection as {@code Set<value>}; iteration order is unspecified.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IntoSet {
    /** {@return the set's element type; the bean must be assignable to it} */
    Class<?> value();
}
```

`IntoMap.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Contributes the annotated bean (or {@code @Provides} result) to the {@code Map<String, value>} binding under
 * {@link #key()}, instead of binding it under its own keys. Two contributions with the same element type and key fail
 * at container construction.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IntoMap {
    /** {@return the map's value type; the bean must be assignable to it} */
    Class<?> value();

    /** {@return the map key for this contribution; must not be blank} */
    String key();
}
```

`Contribution.java`:

```java
package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Key;

/**
 * A factory's collection membership: instead of binding under its own keys, the factory's bean joins the collection
 * bound at {@code collectionKey}.
 *
 * @param collectionKey the collection binding key, e.g. {@code Key.of(Types.setOf(Handler.class))}
 * @param mapKey the map key for a {@code Map} contribution, or {@code null} for a {@code Set} contribution
 */
public record Contribution(Key<?> collectionKey, @Nullable String mapKey) {
    /**
     * @param collectionKey the collection binding key
     * @param mapKey the map key, or {@code null} for a set contribution
     */
    public Contribution {
        requireNonNull(collectionKey, "collectionKey");
    }
}
```

In `Factory.java`, directly after the `exposedKeys()` method:

```java
    /**
     * {@return this factory's collection contribution, or empty when the bean binds under its own keys}
     *
     * <p>A contributing factory exposes no keys of its own; its bean is reachable only through the collection.
     */
    public Optional<Contribution> contribution() {
        return Optional.empty();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q clean test -pl inject > /tmp/t2.log 2>&1; echo EXIT=$?`
Expected: EXIT=0.

- [ ] **Step 5: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/IntoSet.java inject/src/main/java/be/imgn/inject/IntoMap.java \
  inject/src/main/java/be/imgn/inject/context/Contribution.java \
  inject/src/main/java/be/imgn/inject/context/Factory.java \
  inject/src/test/java/be/imgn/inject/context/ContributionTest.java
git commit -m "feat(inject): @IntoSet/@IntoMap markers and Factory.contribution()"
```

---

### Task 3: `DefaultContext` aggregation

**Files:**
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java` (the registry-building constructor and a few private helpers)
- Test: `inject/src/test/java/be/imgn/inject/context/MultibindingTest.java`

**Interfaces:**
- Consumes: `Factory.contribution()` (Task 2), `Types` (Task 1), existing `wrap(...)`, `DuplicateBindingException`, `UnsatisfiedDependencyException`.
- Produces: runtime behavior only — a registered `Provider<Set<T>>`/`Provider<Map<String,T>>` per contribution group. No new public API.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.Types;
import be.imgn.inject.UnsatisfiedDependencyException;

class MultibindingTest {

    /** A contributing factory: exposes nothing, joins the CharSequence collection. */
    static final class ContribFactory extends Factory<String> {
        private final String value;
        private final Contribution contribution;

        ContribFactory(String value, Contribution contribution) {
            super(String.class);
            this.value = value;
            this.contribution = contribution;
        }

        @Override
        protected String createInstance(Context context) {
            return value;
        }

        @Override
        public List<Key<?>> exposedKeys() {
            return List.of();
        }

        @Override
        public Optional<Contribution> contribution() {
            return Optional.of(contribution);
        }
    }

    private static final Key<?> SET_KEY = Key.of(Types.setOf(CharSequence.class));
    private static final Key<?> MAP_KEY = Key.of(Types.mapOf(String.class, CharSequence.class));

    private static ContribFactory intoSet(String value) {
        return new ContribFactory(value, new Contribution(SET_KEY, null));
    }

    private static ContribFactory intoMap(String value, String key) {
        return new ContribFactory(value, new Contribution(MAP_KEY, key));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesSetContributions() {
        Context context = new DefaultContext(List.of(intoSet("a"), intoSet("b")), List.of());
        Set<CharSequence> set = (Set<CharSequence>) context.getProvider(SET_KEY).get();
        assertThat(set).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(set::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesMapContributions() {
        Context context = new DefaultContext(List.of(intoMap("a", "first"), intoMap("b", "second")), List.of());
        Map<String, CharSequence> map = (Map<String, CharSequence>) context.getProvider(MAP_KEY).get();
        assertThat(map).containsOnlyKeys("first", "second").containsEntry("first", "a");
    }

    @Test
    void contributorsExposeNoIndividualKeys() {
        Context context = new DefaultContext(List.of(intoSet("a")), List.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)).get());
    }

    @Test
    void absentCollectionIsAMissingBinding() {
        Context context = new DefaultContext(List.of(), List.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(SET_KEY).get());
    }

    @Test
    void duplicateMapKeyFailsAtBuild() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> new DefaultContext(List.of(intoMap("a", "same"), intoMap("b", "same")), List.of()))
                .withMessageContaining("same");
    }

    @Test
    void directBindingOfTheCollectionTypeCollides() {
        Factory<Object> direct = new Factory<>(Object.class) {
            @Override
            protected Object createInstance(Context context) {
                return Set.of();
            }

            @Override
            public List<Key<?>> exposedKeys() {
                return List.of(SET_KEY);
            }
        };
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> new DefaultContext(List.of(direct, intoSet("a")), List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prototypeContributorsAreFreshPerGet() {
        Context context = new DefaultContext(List.of(intoSet("a")), List.of());
        Provider<?> provider = context.getProvider(SET_KEY);
        assertThat((Set<CharSequence>) provider.get()).isEqualTo(provider.get());
    }

    @Test
    void collectionKeyHasNoBeanDefinition() {
        Context context = new DefaultContext(List.of(intoSet("a")), List.of());
        assertThat(context.getBeanDefinition(SET_KEY)).isEmpty();
    }
}
```

Note `ContribFactory` overrides `exposedKeys()`/`contribution()` as `public` — Task 2 declared them public; `exposedKeys()` is currently `protected`, widen it to `public` in this task (generated code already overrides it; widening is source-compatible). If you prefer not to widen, make the test's overrides `protected` and keep visibility as-is — but `DefaultContext` already calls both, so either works; **widen `exposedKeys()` to `public`** for symmetry with `contribution()` and `annotationMetadata()`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q clean test -pl inject > /tmp/t3.log 2>&1; echo EXIT=$?`
Expected: EXIT=1. Aggregation tests fail (`UnsatisfiedDependencyException` where a set was expected); duplicate-map-key test fails (no exception).

- [ ] **Step 3: Implement aggregation in `DefaultContext`**

In the main constructor, replace the factory loop and registration with:

```java
        Map<Key<?>, Provider<?>> built = new HashMap<>();
        Map<Key<?>, BeanDefinition> definitionsByKey = new HashMap<>();
        List<BeanDefinition> definitionList = new ArrayList<>();
        Map<Key<?>, List<Contributor>> contributions = new LinkedHashMap<>();
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
        // Overrides intentionally shadow discovered bindings (test seams); they win without a duplicate check.
        built.putAll(overrides);
        this.registry = Map.copyOf(built);
        this.definitions = Map.copyOf(definitionsByKey);
        this.allDefinitions = List.copyOf(definitionList);
```

Add the private helpers (imports: `java.util.LinkedHashMap`, `java.util.stream.Collectors`):

```java
    /** One collection member: its contribution and its fully wrapped (scoped, cycle-guarded) provider. */
    private record Contributor(Contribution contribution, Provider<?> provider) {}

    /** {@return the aggregate provider for one collection key: a set provider, or a map provider when keys exist} */
    private static Provider<?> aggregate(Key<?> collectionKey, List<Contributor> contributors) {
        if (contributors.get(0).contribution().mapKey() == null) {
            List<Provider<?>> providers =
                    contributors.stream().map(Contributor::provider).toList();
            return () -> providers.stream().map(Provider::get).collect(Collectors.toUnmodifiableSet());
        }
        Map<String, Provider<?>> byKey = new LinkedHashMap<>();
        for (Contributor contributor : contributors) {
            String mapKey = contributor.contribution().mapKey();
            if (byKey.putIfAbsent(mapKey, contributor.provider()) != null) {
                throw new DuplicateBindingException(
                        "Duplicate map key \"" + mapKey + "\" for " + collectionKey);
            }
        }
        return () -> byKey.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue()
                        .get()));
    }
```

Also in `Factory.java`: change `protected List<Key<?>> exposedKeys()` to `public List<Key<?>> exposedKeys()` (Javadoc unchanged). Mixing set and map contributors under one key cannot happen: set and map contributions use different collection keys (`Set<T>` vs `Map<String,T>`), so a group is homogeneous by construction — `NullAway` may still flag `mapKey()` in the map branch; it is non-null there by the same argument, use a local with `Objects.requireNonNull` if it complains.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q clean test -pl inject > /tmp/t3.log 2>&1; echo EXIT=$?`
Expected: EXIT=0 (existing `DefaultContextTest`, `ConditionalBeanTest`, `BeanDefinitionIntrospectionTest` must stay green too).

- [ ] **Step 5: Add the condition-interplay test, verify, commit**

Append to `MultibindingTest` (reuse the `GatedFactory` idea from `ConditionalBeanTest` but as a contributor — a `ContribFactory` subclass carrying metadata):

```java
    @Test
    void conditionFilteredContributorShrinksTheSet() {
        AnnotationValue off = AnnotationValue.of(
                be.imgn.inject.ConditionalOnProperty.class,
                Map.of("value", "flag", "havingValue", "on", "matchIfMissing", false),
                Set.of(be.imgn.inject.ConditioningAnnotation.class));
        // NoFQNInCode: import ConditionalOnProperty and ConditioningAnnotation instead of qualifying inline.
        Factory<String> gated = new ContribFactory("gated", new Contribution(SET_KEY, null)) {
            @Override
            public AnnotationMetadata annotationMetadata() {
                return AnnotationMetadata.of(off);
            }
        };
        Context context = new DefaultContext(
                List.of(intoSet("kept"), gated), List.of(), List.of(new OnPropertyCondition()), Map.of());
        @SuppressWarnings("unchecked")
        Set<CharSequence> set = (Set<CharSequence>) context.getProvider(SET_KEY).get();
        assertThat(set).containsExactly("kept");
    }
```

(`ContribFactory` must be non-final for this; declare it without `final`. Move the `AnnotationValue` construction to imports-based form — the inline FQNs above are illustrative only and MUST become imports.)

Run: `./mvnw -q clean test -pl inject > /tmp/t3.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

```bash
git add inject/src/main/java/be/imgn/inject/context/DefaultContext.java \
  inject/src/main/java/be/imgn/inject/context/Factory.java \
  inject/src/test/java/be/imgn/inject/context/MultibindingTest.java
git commit -m "feat(context): aggregate @IntoSet/@IntoMap contributions in DefaultContext"
```

---

### Task 4: Processor — collection injection sites

**Files:**
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java` (`dependency(...)`, `Dependency` record at :461-489, callers at :359 and :449)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ParameterModel.java` (`rawType` TypeName → `type` CodeBlock)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/FieldModel.java` (same)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java` (`parameter(...)`, `fieldPoint(...)`: `$T.class` → `$L`)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ProvidesFactoryGenerator.java` (`parameter(...)`: same; the hard-coded host parameter keeps `$T.class`)
- Modify: `inject/src/main/java/be/imgn/inject/context/FieldInjectionPoint.java` (`Class<?> fieldType` → `java.lang.reflect.Type fieldType`; `key()` drops the `(Type)` cast)
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/CollectionInjectionTest.java`

**Interfaces:**
- Consumes: `Types.setOf/mapOf` (Task 1) — rendered, not called.
- Produces: `ParameterModel.type()`/`FieldModel.type()` as `CodeBlock` (e.g. `String.class` or `Types.setOf(Handler.class)`); `private Dependency dependency(TypeMirror, Types, Element site)` instance method with `private record Dependency(CodeBlock type, boolean isProvider)`. Task 5's generators rely on these names.

- [ ] **Step 1: Write the failing golden tests**

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class CollectionInjectionTest {

    @Test
    void rendersSetDependencyWithTypesSetOf() {
        JavaFileObject router = JavaFileObjects.forSourceString("example.Router", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;

                public class Router {
                    @Inject
                    public Router(Set<String> handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(router);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Router$$Factory")
                .contentsAsUtf8String()
                .contains("Types.setOf(String.class)");
    }

    @Test
    void rendersMapDependencyWithTypesMapOf() {
        JavaFileObject router = JavaFileObjects.forSourceString("example.Router", """
                package example;

                import java.util.Map;
                import be.imgn.inject.Inject;

                public class Router {
                    @Inject
                    public Router(Map<String, Integer> byName) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(router);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Router$$Factory")
                .contentsAsUtf8String()
                .contains("Types.mapOf(String.class, Integer.class)");
    }

    @Test
    void providerOfSetComposes() {
        JavaFileObject lazy = JavaFileObjects.forSourceString("example.Lazy", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Lazy {
                    @Inject
                    public Lazy(Provider<Set<String>> handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(lazy);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Lazy$$Factory")
                .contentsAsUtf8String()
                .contains("Types.setOf(String.class)")
                .contains("getProviderForConstructor(0, context)");
    }

    @Test
    void rawSetIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    @SuppressWarnings("rawtypes")
                    public Bad(Set handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("raw Set");
    }

    @Test
    void nonStringMapKeyIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import java.util.Map;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    public Bad(Map<Integer, String> byNumber) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("String keys");
    }

    @Test
    void setOfProviderIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import java.util.Set;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Bad {
                    @Inject
                    public Bad(Set<Provider<String>> handlers) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Set<Provider");
    }
}
```

- [ ] **Step 2: Run to verify failures**

Run: `./mvnw clean verify > /tmp/t4.log 2>&1; echo EXIT=$?` (full reactor — processor tests need `inject` from the reactor)
Expected: EXIT=1; the six new tests fail (no `Types.setOf` in output; no errors raised for raw/bad shapes).

- [ ] **Step 3: Implement**

`ParameterModel.java` — replace `TypeName rawType` with `CodeBlock type` (Javadoc: "the dependency's type expression: a class literal, or a `Types.setOf`/`Types.mapOf` call for collections; the inner type's expression when `isProvider`"). `FieldModel.java` — same rename.

`ModelFactory.java`:

```java
    private static final ClassName TYPES = ClassName.get("be.imgn.inject", "Types");
    private static final String SET = "java.util.Set";
    private static final String MAP = "java.util.Map";
    private static final String STRING = "java.lang.String";

    private Dependency dependency(TypeMirror type, Types types, Element site) {
        Optional<TypeMirror> providerInner = providerInner(type);
        if (providerInner.isPresent()) {
            return new Dependency(typeExpression(providerInner.get(), types, site), true);
        }
        return new Dependency(typeExpression(type, types, site), false);
    }

    /** {@return the runtime type expression for a dependency: a class literal, or a Types factory call} */
    private CodeBlock typeExpression(TypeMirror type, Types types, Element site) {
        if (type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element) {
            String name = element.getQualifiedName().toString();
            if (name.equals(SET)) {
                if (declared.getTypeArguments().size() != 1) {
                    diagnostics.error("Cannot inject a raw Set; declare an element type.", site);
                } else if (providerInner(declared.getTypeArguments().get(0)).isPresent()) {
                    diagnostics.error("Set<Provider<T>> is not supported; inject Provider<Set<T>> instead.", site);
                } else {
                    return CodeBlock.of(
                            "$T.setOf($T.class)",
                            TYPES,
                            TypeName.get(types.erasure(declared.getTypeArguments().get(0))));
                }
            }
            if (name.equals(MAP)) {
                if (declared.getTypeArguments().size() != 2) {
                    diagnostics.error("Cannot inject a raw Map; declare key and value types.", site);
                } else if (!types.erasure(declared.getTypeArguments().get(0)).toString().equals(STRING)) {
                    diagnostics.error("Injected Maps use String keys.", site);
                } else {
                    return CodeBlock.of(
                            "$T.mapOf($T.class, $T.class)",
                            TYPES,
                            TypeName.get(types.erasure(declared.getTypeArguments().get(0))),
                            TypeName.get(types.erasure(declared.getTypeArguments().get(1))));
                }
            }
        }
        return CodeBlock.of("$T.class", TypeName.get(types.erasure(type)));
    }

    private record Dependency(CodeBlock type, boolean isProvider) {}
```

(Checkstyle: `SET`/`MAP`/`STRING` are string constants of dotted names — the `NoFQNInCode` suppression does NOT cover `inject-processor` main sources; if checkstyle flags them, build them as `Set.class.getName()` etc. via imports: `private static final String SET = java.util.Set.class.getName();` → NO, that's an FQN too — use `import java.util.Set;` + `Set.class.getName()`, matching the existing `PROVIDER = Provider.class.getCanonicalName()` pattern at the top of the file.)

Callers: `dependency(parameter.asType(), types)` → `dependency(parameter.asType(), types, parameter)`; in `field(...)`: `dependency(fieldElement.asType(), types, fieldElement)`. Both then pass `dependency.type()` where they passed `dependency.rawType()`.

Generators — in `BeanFactoryGenerator.parameter(...)` and `fieldPoint(...)` and `ProvidesFactoryGenerator.parameter(...)`, replace the type slot:

```java
// before:  "new $T($S, $T.class, $L, $L, $L, $L)"  ... parameter.rawType()
// after:   "new $T($S, $L, $L, $L, $L, $L)"        ... parameter.type()
```

(Same one-slot change in `fieldPoint`; the `ProvidesFactoryGenerator` host parameter is hand-built with `$T.class` and stays.)

`FieldInjectionPoint.java` — change the record component `Class<?> fieldType` to `Type fieldType` (import `java.lang.reflect.Type`), update both convenience constructors' parameter types, and in `key()` drop the now-redundant cast: `Key.of(fieldType, qualifierType)`. Update the record Javadoc line for `fieldType` ("the field's declared type (its inner type when `isProvider`)" — unchanged wording is fine, the type widens).

- [ ] **Step 4: Run to verify green**

Run: `./mvnw clean verify > /tmp/t4.log 2>&1; echo EXIT=$?`
Expected: EXIT=0. Existing golden tests (`new Parameter("clock", String.class,` etc.) still pass because `$L` renders `String.class` identically.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(processor): detect Set/Map injection sites, render Types keys"
```

---

### Task 5: Processor — markers, validation, generation

**Files:**
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/ContributionModel.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanModel.java` (add `@Nullable ContributionModel contribution`)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ProvidesModel.java` (same)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java` (read markers, validate, populate models, skip exposure)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java` (marker-on-non-`@Provides`-method check — add beside the existing `@ExposeAs` equivalent; locate with `grep -n ExposeAs GatherStage.java ModelFactory.java`)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/PlanStage.java` (in-compilation duplicate `@IntoMap` keys)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java`, `ProvidesFactoryGenerator.java` (emit overrides)
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/MultibindingGenerationTest.java`

**Interfaces:**
- Consumes: `ParameterModel.type()`/`Dependency` (Task 4), `Contribution`/`contribution()` names (Task 2).
- Produces: `record ContributionModel(TypeName elementType, @Nullable String mapKey)`; `BeanModel.contribution()`, `ProvidesModel.contribution()`. Generated factories override `exposedKeys()` → `List.of()` and `contribution()` → `Optional.of(new Contribution(...))`.

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

class MultibindingGenerationTest {

    private static final JavaFileObject HANDLER = JavaFileObjects.forSourceString("example.Handler", """
            package example;

            public interface Handler {}
            """);

    @Test
    void intoSetGeneratesContributionAndNoExposedKeys() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.AuthHandler", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;

                @IntoSet(Handler.class)
                public class AuthHandler implements Handler {
                    @Inject
                    public AuthHandler() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$AuthHandler$$Factory")
                .contentsAsUtf8String();
        source.contains("new Contribution(Key.of(Types.setOf(Handler.class)), null)");
        source.contains("return List.of();");
    }

    @Test
    void intoMapGeneratesContributionWithKey() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.AuthHandler", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "auth")
                public class AuthHandler implements Handler {
                    @Inject
                    public AuthHandler() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$AuthHandler$$Factory")
                .contentsAsUtf8String()
                .contains("new Contribution(Key.of(Types.mapOf(String.class, Handler.class)), \"auth\")");
    }

    @Test
    void markerOnProvidesMethodWorks() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    @IntoSet(Handler.class)
                    public static Handler fallback() {
                        return new Handler() {};
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, config);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$fallback$$Factory")
                .contentsAsUtf8String()
                .contains("new Contribution(Key.of(Types.setOf(Handler.class)), null)");
    }

    @Test
    void bothMarkersIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Both", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;
                import be.imgn.inject.IntoSet;

                @IntoSet(Handler.class)
                @IntoMap(value = Handler.class, key = "x")
                public class Both implements Handler {
                    @Inject
                    public Both() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("one collection marker");
    }

    @Test
    void markerWithExposeAsIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;

                @IntoSet(Handler.class)
                @ExposeAs(Handler.class)
                public class Bad implements Handler {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@ExposeAs");
    }

    @Test
    void markerWithQualifierIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;
                import be.imgn.inject.Named;

                @IntoSet(Handler.class)
                @Named("x")
                public class Bad implements Handler {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("unqualified");
    }

    @Test
    void nonAssignableElementTypeIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoSet;

                @IntoSet(Runnable.class)
                public class Bad {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assignable");
    }

    @Test
    void blankMapKeyIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = " ")
                public class Bad implements Handler {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("blank");
    }

    @Test
    void markerOnNonProvidesMethodIsAnError() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.IntoSet;

                public class Bad {
                    @IntoSet(Handler.class)
                    public Handler notProvides() {
                        return new Handler() {};
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Provides");
    }

    @Test
    void duplicateInCompilationMapKeysIsAnError() {
        JavaFileObject a = JavaFileObjects.forSourceString("example.A", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "same")
                public class A implements Handler {
                    @Inject
                    public A() {}
                }
                """);
        JavaFileObject b = JavaFileObjects.forSourceString("example.B", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.IntoMap;

                @IntoMap(value = Handler.class, key = "same")
                public class B implements Handler {
                    @Inject
                    public B() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(HANDLER, a, b);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("same");
    }
}
```

- [ ] **Step 2: Run to verify failures**

Run: `./mvnw clean verify > /tmp/t5.log 2>&1; echo EXIT=$?`
Expected: EXIT=1; every new test fails (markers silently ignored today).

- [ ] **Step 3: Implement**

`ContributionModel.java`:

```java
package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * A bean's collection contribution to generate.
 *
 * @param elementType the collection's element (or map value) type
 * @param mapKey the map key for an {@code @IntoMap} contribution, or {@code null} for {@code @IntoSet}
 */
record ContributionModel(TypeName elementType, @Nullable String mapKey) {}
```

`BeanModel`/`ProvidesModel`: append component `@Nullable ContributionModel contribution` (last position; update Javadoc: "the collection contribution, or {@code null} when the bean binds under its own keys").

`ModelFactory` — add reader + validation (import `be.imgn.inject.IntoMap`, `be.imgn.inject.IntoSet`, `javax.lang.model.type.MirroredTypeException`):

```java
    /** {@return the element's collection contribution, or {@code null}; reports marker misuse} */
    private @Nullable ContributionModel contribution(Element element, TypeMirror contributedType, Types types) {
        IntoSet intoSet = element.getAnnotation(IntoSet.class);
        IntoMap intoMap = element.getAnnotation(IntoMap.class);
        if (intoSet == null && intoMap == null) {
            return null;
        }
        if (intoSet != null && intoMap != null) {
            diagnostics.error("Use only one collection marker (@IntoSet or @IntoMap).", element);
            return null;
        }
        if (element.getAnnotation(ExposeAs.class) != null) {
            diagnostics.error("A collection contribution cannot also use @ExposeAs.", element);
            return null;
        }
        if (Annotations.qualifier(element, environment.getElementUtils(), packageOf(enclosingType(element)),
                        annotationImplRequests)
                .isPresent()) {
            diagnostics.error("Collection bindings are unqualified; remove the qualifier.", element);
            return null;
        }
        TypeMirror elementType =
                intoSet != null ? annotationClassValue(intoSet::value) : annotationClassValue(intoMap::value);
        if (!types.isAssignable(types.erasure(contributedType), types.erasure(elementType))) {
            diagnostics.error(
                    "The bean is not assignable to the collection element type "
                            + types.erasure(elementType) + ".",
                    element);
            return null;
        }
        String mapKey = intoMap != null ? intoMap.key() : null;
        if (mapKey != null && mapKey.isBlank()) {
            diagnostics.error("@IntoMap key must not be blank.", element);
            return null;
        }
        return new ContributionModel(TypeName.get(types.erasure(elementType)), mapKey);
    }

    /** {@return the TypeMirror of a Class-valued annotation member (the standard MirroredTypeException dance)} */
    private static TypeMirror annotationClassValue(java.util.function.Supplier<Class<?>> accessor) {
        try {
            accessor.get();
            throw new IllegalStateException("annotation Class member did not throw");
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }

    /** {@return the nearest enclosing type of {@code element} (itself when it is a type)} */
    private static TypeElement enclosingType(Element element) {
        Element current = element;
        while (!(current instanceof TypeElement typeElement)) {
            current = current.getEnclosingElement();
        }
        return typeElement;
    }
```

(`java.util.function.Supplier` inline is an FQN — import it. The qualifier check above calls `Annotations.qualifier` just to test presence; that also registers an impl request as a side effect — avoid that by instead scanning mirrors directly: copy the loop from `Annotations.qualifier` that finds a `@QualifyingAnnotation`-meta-annotated mirror and only test presence. Implement it as a small `private static boolean hasQualifier(Element, ...)` in `ModelFactory` — do NOT trigger impl generation for a bean that errors out.)

Wire it: in `buildModel(bean)` compute `ContributionModel contribution = contribution(bean, bean.asType(), types);` — when non-null, pass `List.of()` for `exposedTypes` and skip the `beanQualifier` (it is `null` by validation), and pass `contribution` as the new last argument. In `providesModel(...)`: `contribution(method, method.getReturnType(), types)`, same treatment. In `defaultModel(...)`: pass `null`.

`GatherStage` marker-on-non-`@Provides` check — find the existing `@ExposeAs` rule (`grep -n "ExposeAs" inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java`) and add the identical loop for `IntoSet`/`IntoMap`:

```java
// beside the @ExposeAs-on-non-@Provides validation, for each method lacking @Provides:
if (method.getAnnotation(IntoSet.class) != null || method.getAnnotation(IntoMap.class) != null) {
    diagnostics.error("@IntoSet/@IntoMap on a method requires @Provides.", method);
}
```

`PlanStage.plan(...)` — after cycle detection, duplicate in-compilation map keys:

```java
        Map<String, String> mapKeys = new HashMap<>();
        List<ContributionModel> contributions = new ArrayList<>();
        gathered.beans().values().forEach(bean -> {
            if (bean.contribution() != null) {
                contributions.add(bean.contribution());
            }
        });
        gathered.provides().forEach(provides -> {
            if (provides.contribution() != null) {
                contributions.add(provides.contribution());
            }
        });
        for (ContributionModel contribution : contributions) {
            if (contribution.mapKey() == null) {
                continue;
            }
            String slot = contribution.elementType() + "#" + contribution.mapKey();
            if (mapKeys.putIfAbsent(slot, slot) != null) {
                diagnostics.error(
                        "Duplicate @IntoMap key \"" + contribution.mapKey() + "\" for " + contribution.elementType()
                                + ".",
                        gathered.beans().keySet().iterator().next());
                return false;
            }
        }
```

(Better error element: keep a `Map<String, TypeElement>` from slot to the first contributor and report on the second one's `TypeElement` — `gathered.beans()` maps `TypeElement → BeanModel`, so iterate entries and use the entry key; `@Provides` contributions report on their host. Implement with the element you have in hand at insertion time.)

Generators — in both `BeanFactoryGenerator.generate` and `ProvidesFactoryGenerator.generate`, when `model.contribution() != null`, add the two overrides INSTEAD of the `exposedKeys` branch (and skip it):

```java
    private static final ClassName CONTRIBUTION = ClassName.get("be.imgn.inject.context", "Contribution");
    private static final ClassName TYPES = ClassName.get("be.imgn.inject", "Types");
    private static final ClassName STRING_CLASS = ClassName.get("java.lang", "String");

    private static MethodSpec emptyExposedKeys() {
        TypeName keys = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(KEY, WildcardTypeName.subtypeOf(Object.class)));
        return MethodSpec.methodBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(keys)
                .addStatement("return $T.of()", LIST)
                .build();
    }

    private static MethodSpec contributionMethod(ContributionModel contribution) {
        CodeBlock collectionKey = contribution.mapKey() == null
                ? CodeBlock.of("$T.of($T.setOf($T.class))", KEY, TYPES, contribution.elementType())
                : CodeBlock.of(
                        "$T.of($T.mapOf($T.class, $T.class))",
                        KEY, TYPES, STRING_CLASS, contribution.elementType());
        CodeBlock mapKey = contribution.mapKey() == null ? CodeBlock.of("null") : CodeBlock.of("$S", contribution.mapKey());
        return MethodSpec.methodBuilder("contribution")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(OPTIONAL, CONTRIBUTION))
                .addStatement("return $T.of(new $T($L, $L))", OPTIONAL, CONTRIBUTION, collectionKey, mapKey)
                .build();
    }
```

Note: Task 3 widened `exposedKeys()` to `public`, so the generated override is `PUBLIC`; the existing generated `exposedKeys` override in the non-contribution branch must switch from `PROTECTED` to `PUBLIC` in the same commit (one-word change in the existing `exposedKeys(model)` method builders of both generators).

- [ ] **Step 4: Run to verify green**

Run: `./mvnw clean verify > /tmp/t5.log 2>&1; echo EXIT=$?`
Expected: EXIT=0.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(processor): @IntoSet/@IntoMap contributions - validation and generation"
```

---

### Task 6: Classpath end-to-end example

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Notifier.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/EmailNotifier.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/SmsNotifier.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/PushNotifier.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Formatter.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/JsonFormatter.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/XmlFormatter.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/NotificationCenter.java`
- Modify: `inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java` (add one test)

**Interfaces:**
- Consumes: everything from Tasks 1–5, through the real processor.
- Produces: example beans other docs may reference: `Notifier`, `Formatter`, `NotificationCenter(Set<Notifier>, Map<String, Formatter>)`.

- [ ] **Step 1: Write the beans**

```java
// Notifier.java
package be.imgn.inject.examples;

/** A channel the {@link NotificationCenter} broadcasts to; implementations contribute via {@code @IntoSet}. */
public interface Notifier {}
```

```java
// EmailNotifier.java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Always-on notifier contributed to {@code Set<Notifier>}. */
@IntoSet(Notifier.class)
public final class EmailNotifier implements Notifier {
    @Inject
    public EmailNotifier() {}
}
```

```java
// SmsNotifier.java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Always-on notifier contributed to {@code Set<Notifier>}. */
@IntoSet(Notifier.class)
public final class SmsNotifier implements Notifier {
    @Inject
    public SmsNotifier() {}
}
```

```java
// PushNotifier.java
package be.imgn.inject.examples;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Contributed to {@code Set<Notifier>} only when {@code examples.push} is {@code true}. */
@IntoSet(Notifier.class)
@ConditionalOnProperty(value = "examples.push", havingValue = "true")
public final class PushNotifier implements Notifier {
    @Inject
    public PushNotifier() {}
}
```

```java
// Formatter.java
package be.imgn.inject.examples;

/** A named output format; implementations contribute via {@code @IntoMap}. */
public interface Formatter {}
```

```java
// JsonFormatter.java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoMap;

/** The {@code "json"} entry of {@code Map<String, Formatter>}. */
@IntoMap(value = Formatter.class, key = "json")
public final class JsonFormatter implements Formatter {
    @Inject
    public JsonFormatter() {}
}
```

```java
// XmlFormatter.java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoMap;

/** The {@code "xml"} entry of {@code Map<String, Formatter>}. */
@IntoMap(value = Formatter.class, key = "xml")
public final class XmlFormatter implements Formatter {
    @Inject
    public XmlFormatter() {}
}
```

```java
// NotificationCenter.java
package be.imgn.inject.examples;

import java.util.Map;
import java.util.Set;

import be.imgn.inject.Inject;

/** Consumes both collection bindings: every notifier, and formatters by name. */
public final class NotificationCenter {
    private final Set<Notifier> notifiers;
    private final Map<String, Formatter> formatters;

    /**
     * @param notifiers every contributed notifier
     * @param formatters formatters by format name
     */
    @Inject
    public NotificationCenter(Set<Notifier> notifiers, Map<String, Formatter> formatters) {
        this.notifiers = notifiers;
        this.formatters = formatters;
    }

    /** {@return every contributed notifier} */
    public Set<Notifier> notifiers() {
        return notifiers;
    }

    /** {@return formatters by format name} */
    public Map<String, Formatter> formatters() {
        return formatters;
    }
}
```

- [ ] **Step 2: Add the test to `ExamplesClasspathTest`**

```java
    @Test
    void collectsMultibindings() {
        NotificationCenter center = Injector.create().getInstance(NotificationCenter.class);
        assertThat(center.notifiers())
                .hasSize(2)
                .hasAtLeastOneElementOfType(EmailNotifier.class)
                .hasAtLeastOneElementOfType(SmsNotifier.class);
        assertThat(center.formatters()).containsOnlyKeys("json", "xml");

        System.setProperty("examples.push", "true");
        try {
            assertThat(Injector.create().getInstance(NotificationCenter.class).notifiers())
                    .hasSize(3)
                    .hasAtLeastOneElementOfType(PushNotifier.class);
        } finally {
            System.clearProperty("examples.push");
        }
    }
```

- [ ] **Step 3: Run to verify green**

Run: `./mvnw clean verify > /tmp/t6.log 2>&1; echo EXIT=$?`
Expected: EXIT=0.

- [ ] **Step 4: Commit**

```bash
git add inject-examples
git commit -m "feat(examples): Set and Map multibindings with a conditional contributor"
```

---

### Task 7: Module-path example + final sweep

**Files:**
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/Plugin.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/AlphaPlugin.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/BetaPlugin.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/PluginHost.java`
- Modify: `inject-examples-module/src/main/java/module-info.java` (extend the `provides` list)
- Modify: `inject-examples-module/src/test/java/be/imgn/inject/examples/modular/ModulePathTest.java` (add one test)

**Interfaces:**
- Consumes: Tasks 1–5 on the module path.
- Produces: nothing downstream; final proof.

- [ ] **Step 1: Write the beans**

```java
// Plugin.java
package be.imgn.inject.examples.modular;

/** A plugin contributed to {@code Set<Plugin>} on the module path. */
public interface Plugin {}
```

```java
// AlphaPlugin.java
package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** First contributed plugin. */
@IntoSet(Plugin.class)
public final class AlphaPlugin implements Plugin {
    @Inject
    public AlphaPlugin() {}
}
```

```java
// BetaPlugin.java
package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoSet;

/** Second contributed plugin. */
@IntoSet(Plugin.class)
public final class BetaPlugin implements Plugin {
    @Inject
    public BetaPlugin() {}
}
```

```java
// PluginHost.java
package be.imgn.inject.examples.modular;

import java.util.Set;

import be.imgn.inject.Inject;

/** Injects every contributed {@link Plugin}. */
public final class PluginHost {
    private final Set<Plugin> plugins;

    /** @param plugins every contributed plugin */
    @Inject
    public PluginHost(Set<Plugin> plugins) {
        this.plugins = plugins;
    }

    /** {@return every contributed plugin} */
    public Set<Plugin> plugins() {
        return plugins;
    }
}
```

`module-info.java` — extend the existing `provides` clause with the three new factories (exact generated names):

```java
    provides be.imgn.inject.context.Factory with
            be.imgn.inject.examples.modular.$Widget$$Factory,
            be.imgn.inject.examples.modular.$Service$$Factory,
            be.imgn.inject.examples.modular.$AlphaPlugin$$Factory,
            be.imgn.inject.examples.modular.$BetaPlugin$$Factory,
            be.imgn.inject.examples.modular.$PluginHost$$Factory;
```

- [ ] **Step 2: Add the test to `ModulePathTest`**

```java
    @Test
    void collectsSetContributionsOnTheModulePath() {
        PluginHost host = Injector.create().getInstance(PluginHost.class);
        assertThat(host.plugins())
                .hasSize(2)
                .hasAtLeastOneElementOfType(AlphaPlugin.class)
                .hasAtLeastOneElementOfType(BetaPlugin.class);
    }
```

- [ ] **Step 3: Full verify, real exit code**

Run: `./mvnw clean verify > /tmp/t7.log 2>&1; echo EXIT=$?`
Expected: EXIT=0, and `grep -E "Passed: 3" /tmp/t7.log` shows the Maven-plugin invoker ITs still pass.

- [ ] **Step 4: Commit**

```bash
git add inject-examples-module
git commit -m "feat(examples): Set multibinding on the module path"
```

---

## Self-Review Notes

- Spec coverage: markers ✔ (T2/T5), `Types` ✔ (T1), runtime aggregation + duplicate map key + direct-binding collision + absent-is-error ✔ (T3), site detection + shape errors + `Provider<Set>` ✔ (T4), all seven compile errors ✔ (T4 site shapes; T5 the rest), conditions interplay ✔ (T3 test + T6 example), introspection interplay ✔ (T3 `collectionKeyHasNoBeanDefinition`), e2e classpath + module path ✔ (T6/T7).
- Type consistency: `Contribution(Key<?>, @Nullable String)` (T2) used by T3 aggregation and T5 generation; `ContributionModel(TypeName, @Nullable String)` (T5) only inside the processor; `Dependency(CodeBlock type, boolean isProvider)` and `ParameterModel.type()`/`FieldModel.type()` renames (T4) used by both generators; `exposedKeys()` widened to `public` in T3, generators emit `PUBLIC` from T5 on.
- Known ripple: `FieldInjectionPoint.fieldType` widens `Class<?>` → `Type` (T4); `InjectionPointTest` and `Factory` compile unchanged since `X.class` is a `Type` and `key()` already routed through `Key.of(Type, ...)`.
