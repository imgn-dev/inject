# Eager Singletons Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking. Work test-first: every step that adds
> behavior starts with a failing test.

**Goal:** A `@Singleton` bean or `@Provides` method marked `@Eager` is built when the root injector is created, after
the construction listeners are resolved, so the listeners see it.

**Architecture:**
- **Annotation and SPI.** `@Eager` is a new annotation in `be.imgn.inject`. `Factory` gains
  `protected boolean isEager()`, `false` by default, which generated factories override.
- **Registry.** `DefaultContext` records the provider of each live eager factory while it builds the registry, then
  calls `get()` on each in `setInjector`, right after resolving the listeners.
- **Creation failure.** `setInjector` closes the context when resolving a listener or building an eager singleton
  throws.
- **Processor.** It gathers `@Eager` classes as beans, validates every placement, and emits the override.

Spec: `docs/superpowers/specs/2026-10-31-eager-singletons-design.md`.

**Tech stack:** Java 21, the Maven wrapper, gaia-processing (`CodeBlock`, `Method`, `ClassName`),
gaia-processing-testing, JUnit 6 and AssertJ. No new `ServiceLoader` service, so the module path is unaffected.

## Global constraints

- **Build.** Use `./mvnw clean verify`, never `install` while iterating. Judge the result by Maven's exit code
  (`> "$TMPDIR/x.log" 2>&1; echo EXIT=$?`), never through a pipe.
  - `-pl inject` is fine for runtime-only iteration.
  - Run the full reactor before every commit, and for anything touching `inject-processor` or the examples.
- **Quality gates.** Never weaken a check: no new `@SuppressWarnings`, no exclusions. If `GatherStage.gather` would
  outgrow a limit, extract a helper instead.
- **Code rules.**
  - Checkstyle `NoFQNInCode`: always import.
  - NullAway runs as an error.
  - Javadoc on every public API member. The project uses no `@since` tags, so add none.
  - Use AssertJ fluent chaining.
- **Packages.** `be.imgn.inject` holds the annotations and `Injector`; `be.imgn.inject.context` holds `Factory`,
  `DefaultContext` and `ChildContext`. `DefaultContext`'s `(factories, scopes, …)` constructors are package-private,
  so runtime tests using them live in `be.imgn.inject.context`.

---

### Task 1: `@Eager` and `Factory.isEager()`

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/Eager.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/Factory.java`

**Produces:** `@Eager` (`RUNTIME`, `@Target({TYPE, METHOD})`) and `protected boolean Factory.isEager()`. Tasks 2 and
3 rely on both.

- [ ] **Step 1:** Create `Eager`:

```java
/**
 * Marks a {@link Singleton} bean, or a {@link Provides} method returning a singleton, to be built when the root
 * injector is created rather than on first resolution.
 *
 * <p>Eager singletons are built after the {@link ConstructionListener}s are resolved, so the listeners see them and
 * their dependencies. Their dependencies are built first; beyond that their order is unspecified. A bean whose
 * condition fails, a {@link Default} binding that lost its key, and a bean every key of which a builder override
 * shadows are not built. A failure fails the injector's creation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Eager {}
```

- [ ] **Step 2:** Add to `Factory`, after `isDefault()`:

```java
/** {@return whether the root injector builds this factory's singleton when it is created; false by default} */
protected boolean isEager() {
    return false;
}
```

- [ ] **Step 3:** Run `./mvnw clean verify -pl inject`, which should pass. Commit: `feat: add the @Eager annotation`.

### Task 2: Build eager singletons in `DefaultContext`

**Files:**
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java`
- Modify: `inject/src/main/java/be/imgn/inject/Injector.java`
- Test: `inject/src/test/java/be/imgn/inject/context/EagerSingletonTest.java`

**Test fakes.** Follow `ConstructionListenerTest`: a `PartFactory`-style fake factory with a constructor flag for
eagerness that overrides `isEager()`, and a recording `ConstructionListener` factory contributing to
`Set<ConstructionListener>`.

- [ ] **Step 1:** Write the failing tests:
  - `anEagerSingletonIsBuiltAndReportedWhenTheInjectorIsAttached`: after `setInjector`, the eager factory has built
    once and the listener got the instance. A later `getProvider(key).get()` returns the same instance.
  - `aSingletonThatIsNotEagerIsNotBuilt`.
  - `theDependenciesOfAnEagerSingletonAreReported`: the eager factory resolves another singleton in
    `createInstance`, and the listener sees the dependency first.
  - `anEagerBeanWhoseConditionFailsIsNotBuilt`: use the 5-argument constructor with an `OnPropertyCondition`, as in
    the existing condition tests.
  - `aDefaultThatLostItsKeyIsNotBuilt`.
  - `anEagerBeanEveryKeyOfWhichIsOverriddenIsNotBuilt`: pass the overrides map.
  - `anEagerContributorIsBuilt`: a factory with a `contribution()`.
  - `aChildBuildsNothingNew`: create a `ChildContext` over the root and check the build count is unchanged.
- [ ] **Step 2:** Run `./mvnw clean verify -pl inject`. The new tests should fail.
- [ ] **Step 3:** In `DefaultContext`:
  - Add `private record EagerBean(Factory<?> factory, Provider<?> provider) {}` next to the existing `Claim`
    and `PendingDefault` records.
  - Add a field `private final List<Provider<?>> eager;`.
  - In the constructor loop, right after `Provider<?> provider = wrap(factory, scopesByAnnotation);`:
    `if (factory.isEager()) eagerBeans.add(new EagerBean(factory, provider));`. It runs before the contribution and
    default branches, so both are covered.
  - After the overrides are applied and `definitionList` is final:

    ```java
    // A bean is built eagerly only while it still serves the graph: a default that lost its key and a bean every key
    // of which an override shadows are not in the definition list.
    Set<BeanDefinition> live = Set.copyOf(definitionList);
    this.eager = eagerBeans.stream()
            .filter(bean -> live.contains(bean.factory()))
            .<Provider<?>>map(EagerBean::provider)
            .toList();
    ```

  - In `setInjector`, after `this.listeners = …`: `eager.forEach(Provider::get);`. Update its Javadoc: it resolves
    the listeners, then builds the eager singletons.
  - Update the class Javadoc with one sentence on eager singletons.
- [ ] **Step 4:** Run `./mvnw clean verify -pl inject`. The tests should pass.
- [ ] **Step 5:** Write the failing tests, in `EagerSingletonTest`:
  - `aFailingEagerSingletonFailsTheAttachmentAndDestroysWhatWasBuilt`: an eager factory depends on a singleton whose
    factory records `preDestroy`, then throws. `setInjector` throws that exception, the dependency is destroyed, and
    the context is closed.
  - `aFailingListenerFailsTheAttachmentAndDestroysWhatWasBuilt`: the same, for a listener.

  Give the dependency its own type: the cycle guard keys on the factory's type, so two `Part` factories depending on
  each other would look like a cycle.
- [ ] **Step 6:** Checkstyle forbids catching `RuntimeException` (`IllegalCatch`), so don't catch it in `Injector`.
  In `setInjector`, move listener resolution and eager construction into a private `start()`, and run it through
  `Destroyables.runAll`, which already collects failures:

```java
AtomicBoolean started = new AtomicBoolean();
// A failure to close is added to the failure to start, which is thrown.
Destroyables.runAll(List.of(
        () -> {
            start();
            started.set(true);
        },
        () -> {
            if (!started.get()) {
                close();
            }
        }));
```

  Add to the `@throws` of `Injector.create()` and `Builder.build()`: "any exception thrown building a construction
  listener or an eager singleton, once the singletons already built are destroyed".
- [ ] **Step 7:** Run `./mvnw clean verify`, which should pass. Commit: `feat: build eager singletons at creation`.

### Task 3: Processor support

**Files:**
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/InjectStep.java`, adding `Eager.class` to
  `annotations()`.
- Modify: `GatherStage.java`, `ModelFactory.java`, `BeanModel.java`, `ProvidesModel.java`,
  `BeanFactoryGenerator.java` and `ProvidesFactoryGenerator.java`.
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/EagerProcessorTest.java`

**Error messages.** These are exact, so the tests can pin them:

| Placement | Message |
|---|---|
| Method without `@Provides` | `@Eager on a method requires @Provides.` |
| Interface, abstract class, enum, annotation type | `@Eager belongs on a concrete class or a @Provides method.` |
| Bean not `@Singleton` | `@Eager requires @Singleton: example.Foo is unscoped.` / `… is @WindowScoped.` |
| `@Provides` not `@Singleton` | `@Eager requires @Singleton on @Provides name().` |
| Assisted product | `@Eager cannot mark an assisted product: only its @AssistedFactory builds it.` |
| Assisted `@Provides` recipe | `@Eager cannot mark an assisted recipe.` |
| No usable constructor | `example.Foo is @Eager but has no @Inject constructor and no accessible no-argument constructor.` |

- [ ] **Step 1:** Write the failing tests, in the style of `DefaultBindingProcessorTest`:
  - `generatesIsEagerForAnEagerSingleton`: `@Eager @Singleton` with an `@Inject` constructor, so the factory contains
    `protected boolean isEager()`.
  - `anEagerClassNeedsNoInjectMember`: `@Eager @Singleton` with only an implicit constructor; it succeeds and gets a
    factory with `isEager()`.
  - `anInheritedSingletonCountsForEager`: the class extends an abstract `@Singleton` base.
  - `generatesIsEagerForAnEagerProvidesMethod`.
  - `aSingletonThatIsNotEagerGetsNoIsEager`.
  - One test per row of the error table.
  - `eagerAndDefaultCombine`: a `@Provides @Default @Eager @Singleton` method compiles, and its factory has both
    overrides.
- [ ] **Step 2:** Run `./mvnw clean verify -pl inject-processor -am`. The new tests should fail.
- [ ] **Step 3:** In `GatherStage.gather`, after the `InterceptingAnnotation` loop, call a new
  `gatherEagerBeans(round, beanTypes)`:

```java
/** Adds the round's {@code @Eager} classes to the beans, and reports {@code @Eager} where it cannot apply. */
private void gatherEagerBeans(AnnotatedElements round, Map<TypeElement, Set<Element>> beanTypes) {
    for (Element element : round.annotatedWith(Eager.class.getCanonicalName())) {
        if (element.getKind() == ElementKind.METHOD) {
            if (!MoreElements.isAnnotatedWith(element, Provides.class)) {
                diagnostics.on(element).error("@Eager on a method requires @Provides.");
            }
        } else if ((element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.RECORD)
                && !element.getModifiers().contains(Modifier.ABSTRACT)) {
            addBean(beanTypes, MoreElements.asType(element), element);
        } else {
            diagnostics.on(element).error("@Eager belongs on a concrete class or a @Provides method.");
        }
    }
}
```

- [ ] **Step 4:** In the bean loop's assisted branch, before `continue`: if the bean is `@Eager`, report the
  assisted-product error. In `gatherProvides`' assisted branch, do the same with the assisted-recipe error.
- [ ] **Step 5:** In `ModelFactory`:
  - In `validateBean`, choose the no-constructor message by whether the bean is `@Eager`.
  - In `buildModel`, compute `boolean eager = MoreElements.isAnnotatedWith(bean, Eager.class)`. When it's true and
    `scope` isn't `be.imgn.inject.Singleton`, report the bean error with `@<simple name>` or `unscoped`.
  - Pass `eager` to the new last-but-one `BeanModel` component, `boolean eager`, documented in its `@param`.
  - `defaultModel` passes `false`: an `@Eager` class of the round is always gathered, so it never gets a default
    model.
  - In `providesModel`, compute the same flag from the method and check
    `Annotations.scope(method)` against `Singleton`. Pass it as the new `ProvidesModel` component `boolean isEager`,
    next to `isDefault`.
- [ ] **Step 6:** In both generators, emit the override when the flag is set. `ProvidesFactoryGenerator` already has
  `isDefaultMethod()`; add `isEagerMethod()` beside it, and the same method in `BeanFactoryGenerator`, after the
  `intercepts` override.
- [ ] **Step 7:** Run `./mvnw clean verify`, which should pass. Commit: `feat: generate eager factories`.

### Task 4: End-to-end test

**Files:**
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/EagerEndToEndTest.java`

- [ ] **Step 1:** Following `JdkExposureTest.twoBeansWithAJdkSuperclassClaimNoJdkKey`, compile these to a
  `@TempDir`:
  - an `@Eager @Singleton` bean that increments a static counter in its constructor;
  - a recording `@Singleton @IntoSet(ConstructionListener.class)` listener;
  - an `@Eager @Singleton @Provides` method.

  Load the classes through a `URLClassLoader` set as the context class loader. Then check that `Injector.create()`
  builds both eager singletons before anything resolves them, and that the listener saw both.
- [ ] **Step 2:** Add the end-to-end failure tests, each in its own compilation. Each uses a dependency, so the build order
  is deterministic:
  - `aFailingEagerSingletonDestroysWhatWasBuilt`: an `@Eager @Singleton` bean depends on a `@Singleton` with a
    `@PreDestroy` that records into a static list, then throws from its own constructor. `Injector.create()` throws
    that exception, and the dependency was destroyed.
  - `aFailingListenerDestroysWhatWasBuilt`: the same with a construction listener in place of the eager bean.
- [ ] **Step 3:** Run `./mvnw clean verify`, which should pass. Commit: `test: cover eager singletons end to end`.

### Task 5: Documentation

**Files:**
- Create: `docs/src/main/markdown/eager-singletons.md`. Use the "The problem / How it solves it / Limitations" layout of the
  other feature docs. Limitations: `@Singleton` only, order unspecified beyond dependencies, built on the creating
  thread, no switch to turn it off, and a listener's eager dependency isn't reported.
- Modify: `docs/src/main/markdown/index.md` (link), `docs/src/main/markdown/construction-listeners.md` (eager singletons are reported;
  link), and `CHANGELOG.md` (an entry under `## Unreleased`, with its hash added in a follow-up
  `docs: update the changelog` commit).
- Modify: the main design spec's annotation table, if it lists the annotations in `be.imgn.inject`.

- [ ] **Step 1:** Write the docs. Run `./mvnw clean verify` for Javadoc, which should pass.
- [ ] **Step 2:** Commit: `docs: document eager singletons`.

### Finish

- [ ] Squash the branch into one commit, `feat: add eager singletons`, which includes the spec and this plan. Then
  ask Olivier before merging. After the merge, add the changelog hash in `docs: update the changelog`.

## Self-review notes

- **Spec coverage:**
  - The when, reporting, order, which-beans, child and failure semantics are covered by Task 2.
  - Every compile error is covered by Task 3's table.
  - The `isEager()` SPI is Task 1, and the docs are Task 5.
  - Swing's use is documentation only; nothing in inject is specific to it.
- **Risk:** `Set.copyOf(definitionList)` relies on factory identity, since `Factory` doesn't override `equals`.
  That's what the registry already assumes elsewhere.
- **Risk:** closing the context on a failed `setInjector` changes today's behavior for a failing listener.
  This is intended (the spec's Failure section), and covered by its own end-to-end test (Task 4).
