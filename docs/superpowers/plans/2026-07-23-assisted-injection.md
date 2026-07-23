# Assisted Injection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beans mixing container-resolved and caller-supplied (`@Assisted`) constructor/`@Provides` parameters, created through user-declared factory interfaces whose implementations the processor generates.

**Architecture:** Merged model — an `@Assisted` parameter on an `@Inject` constructor (or `@Provides` method) turns the recipe factory-only: no key, no generated `Factory`, reachable only through interfaces with `@AssistedFactory` methods. The processor gathers assisted recipes, resolves each factory method's link (exact return type / `value()` impl class / `value()`+`method()` provides recipe), validates a type+tag bijection, and generates one impl class per interface (holding `Context`, resolving container params by `Key`, running member injection) plus a normal `Factory<TheInterface>` registration. No runtime module changes. Spec: `docs/superpowers/specs/2026-07-23-assisted-injection-design.md`.

**Tech Stack:** Java 21, Maven wrapper, palantir JavaPoet (`com.palantir.javapoet`), google-compile-testing, JUnit + AssertJ.

## Global Constraints

- Build: `./mvnw clean verify` — NEVER `install`. Judge by Maven's own exit code (`./mvnw clean verify > /tmp/build.log 2>&1; echo EXIT=$?`), never through a pipe.
- Checkstyle `NoFQNInCode`: no dotted names in code — import; type-name string constants use the `X.class.getCanonicalName()` pattern (like `PROVIDER` in `ModelFactory`). Test text blocks in `inject-processor` are exempt (existing suppression).
- NullAway is ERROR; `@org.jspecify.annotations.Nullable` (imported) on nullable members.
- Javadoc on every public API member, present tense, current state only.
- Tests: JUnit + AssertJ fluent chaining. Processor golden tests use compile-testing (`assertThat(compilation)` — Truth, not AssertJ; `StringSubject.contains` returns void, never chain it).
- Full-reactor builds for anything touching `inject-processor` or examples.
- Class-valued annotation members are read via the `MirroredTypeException` dance (existing helper `annotationClassValue` in `ModelFactory`).

---

### Task 1: Annotations + assisted-recipe gathering & prohibitions

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/Assisted.java`
- Create: `inject/src/main/java/be/imgn/inject/AssistedFactory.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/Gathered.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/PlanStage.java`
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/AssistedRecipe.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/AssistedRecipeTest.java`

**Interfaces:**
- Consumes: existing `ModelFactory` gathering (`buildModel`, `providesModel`, `injectConstructor`, `parameters`, `field`, `pluginFields`), `Gathered` record, `Diagnostics`.
- Produces: `@Assisted(String value default "")` and `@AssistedFactory(Class value default void.class, String method default "")` annotations; `record AssistedRecipe(TypeElement owner, @Nullable ExecutableElement providesMethod, ExecutableElement executable)` where `executable` is the assisted constructor or the assisted `@Provides` method; `Gathered.assistedRecipes()` as `Map<String, AssistedRecipe>` keyed by `owner qualified name` (constructor recipes) or `owner qualified name + "#" + method name` (provides recipes). Task 2 consumes that map for link resolution; `Gathered.assistedBeanNames()` (`Set<String>`) for the direct-injection check.

- [ ] **Step 1: Write the annotations**

`Assisted.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a caller-supplied parameter of an assisted recipe: an {@code @Inject} constructor or {@code @Provides} method
 * containing one becomes reachable only through an {@link AssistedFactory} method, which supplies these parameters at
 * call time while the container resolves the rest.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Assisted {
    /** {@return the tag disambiguating same-typed assisted parameters; empty for untagged} */
    String value() default "";
}
```

`AssistedFactory.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a factory-interface method the annotation processor implements. The method's parameters supply the recipe's
 * {@link Assisted} parameters (matched by type and tag); the container resolves everything else per call.
 *
 * <p>Link resolution: with no members, the method's return type is the implementation class; with {@link #value()},
 * the implementation is {@code value()} and the return type may be any supertype of it; with {@link #value()} and
 * {@link #method()}, the recipe is the named assisted {@code @Provides} method declared by {@code value()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AssistedFactory {
    /** {@return the implementation class, or the recipe's declaring class when {@link #method()} is set} */
    Class<?> value() default void.class;

    /** {@return the name of an assisted {@code @Provides} method on {@link #value()}; empty for constructor recipes} */
    String method() default "";
}
```

- [ ] **Step 2: Write the failing processor tests**

`AssistedRecipeTest.java` — gathering-side behavior only (factory interfaces are Task 2):

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class AssistedRecipeTest {

    private static final JavaFileObject ASSISTED_BEAN = JavaFileObjects.forSourceString("example.Report", """
            package example;

            import be.imgn.inject.Assisted;
            import be.imgn.inject.Inject;

            public class Report {
                @Inject
                public Report(String db, @Assisted String id) {}
            }
            """);

    @Test
    void assistedBeanGeneratesNoOwnFactory() {
        JavaFileObject dep = JavaFileObjects.forSourceString("example.Dep", """
                package example;

                import be.imgn.inject.Inject;

                public class Dep {
                    @Inject
                    public Dep() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(ASSISTED_BEAN, dep);

        assertThat(compilation).succeeded();
        // the assisted bean must not get a factory; the ordinary bean still does
        assertThat(compilation).generatedSourceFile("example.$Dep$$Factory");
        org.junit.jupiter.api.Assertions.assertTrue(compilation.generatedSourceFiles().stream()
                .noneMatch(f -> f.getName().contains("$Report$$Factory")));
    }

    @Test
    void injectingAnAssistedBeanDirectlyIsAnError() {
        JavaFileObject consumer = JavaFileObjects.forSourceString("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;

                public class Consumer {
                    @Inject
                    public Consumer(Report report) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(ASSISTED_BEAN, consumer);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("only available through an @AssistedFactory");
    }

    @Test
    void scopedAssistedBeanIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Bad {
                    @Inject
                    public Bad(@Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void qualifiedOrExposedOrContributedAssistedBeanIsAnError() {
        JavaFileObject exposed = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs(CharSequence.class)
                public class Bad implements CharSequence {
                    @Inject
                    public Bad(@Assisted String id) {}

                    @Override public int length() { return 0; }
                    @Override public char charAt(int i) { return 'x'; }
                    @Override public CharSequence subSequence(int a, int b) { return this; }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(exposed);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void assistedProvidesMethodBindsNothing() {
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static Long legacy(String db, @Assisted int id) {
                        return (long) id;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).succeeded();
        org.junit.jupiter.api.Assertions.assertTrue(compilation.generatedSourceFiles().stream()
                .noneMatch(f -> f.getName().contains("$Config$legacy$$Factory")));
    }

    @Test
    void scopedAssistedProvidesIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Provides;
                import be.imgn.inject.Singleton;

                public class Bad {
                    @Provides
                    @Singleton
                    public static Long legacy(@Assisted int id) {
                        return (long) id;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void misplacedAssistedIsAnError() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @Inject
                    public void configure(@Assisted String id) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("misplaced");
    }
}
```

(Adjust the two `Assertions.assertTrue(...)` uses to imported static `assertTrue` — no FQNs outside text blocks.)

- [ ] **Step 3: Run to verify RED**

Run: `./mvnw clean verify > /tmp/a1.log 2>&1; echo EXIT=$?`
Expected: EXIT=1 (annotations missing → test sources fail to compile; that is the red signal).

- [ ] **Step 4: Implement**

1. Add the two annotation files (Step 1 content verbatim).
2. `AssistedRecipe.java`:

```java
package be.imgn.inject.processor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

import org.jspecify.annotations.Nullable;

/**
 * A factory-only construction recipe: an {@code @Inject} constructor or {@code @Provides} method carrying
 * {@code @Assisted} parameters.
 *
 * @param owner the class declaring the recipe (the bean itself, or the {@code @Provides} host)
 * @param providesMethod the assisted {@code @Provides} method, or {@code null} for a constructor recipe
 * @param executable the recipe executable: the constructor, or {@code providesMethod}
 */
record AssistedRecipe(TypeElement owner, @Nullable ExecutableElement providesMethod, ExecutableElement executable) {}
```

3. `ModelFactory` additions:

```java
    /** {@return whether {@code executable} has at least one @Assisted parameter} */
    static boolean isAssisted(ExecutableElement executable) {
        return executable.getParameters().stream()
                .anyMatch(parameter -> parameter.getAnnotation(Assisted.class) != null);
    }
```

(import `be.imgn.inject.Assisted`.) Plus prohibition validation, called from `GatherStage` for every gathered assisted recipe:

```java
    /** Reports every annotation that contradicts a recipe having no binding of its own. */
    void validateAssistedRecipe(Element annotated) {
        if (Annotations.scope(annotated).isPresent()) {
            diagnostics.error("An assisted recipe cannot be scoped; every factory call builds a fresh instance.",
                    annotated);
        }
        if (hasQualifier(annotated)) {
            diagnostics.error("An assisted recipe cannot be qualified; it has no binding key.", annotated);
        }
        if (annotated.getAnnotation(ExposeAs.class) != null) {
            diagnostics.error("An assisted recipe cannot use @ExposeAs; it has no binding key.", annotated);
        }
        if (annotated.getAnnotation(IntoSet.class) != null || annotated.getAnnotation(IntoMap.class) != null) {
            diagnostics.error("An assisted recipe cannot contribute to a collection; it has no binding key.",
                    annotated);
        }
    }
```

(For a constructor recipe, `annotated` is the BEAN TYPE — scope/qualifier/ExposeAs/markers sit there; for a provides
recipe it is the METHOD. `hasQualifier` exists from the injector-injection work.) And the misplaced check — extend
the `@Inject`-member walk in `buildModel` (the loop over enclosed elements): for `@Inject` METHODS, after building
the `MethodModel`, report any `@Assisted` parameter:

```java
                for (VariableElement parameter : method.getParameters()) {
                    if (parameter.getAnnotation(Assisted.class) != null) {
                        diagnostics.error(
                                "@Assisted is misplaced: only @Inject constructors and @Provides methods take"
                                        + " assisted parameters.",
                                parameter);
                    }
                }
```

4. `Gathered` gains two components (append; update the record Javadoc):

```java
        Map<String, AssistedRecipe> assistedRecipes,
        Set<String> assistedBeanNames,
```

5. `GatherStage`:
   - Where `@Inject` beans are collected: if `models.injectConstructor(bean)` is present AND `ModelFactory.isAssisted(constructor)`, do NOT call `buildModel`/register — instead `models.validateAssistedRecipe(bean)`, still run the misplaced-члены walk (call a new slim `models.validateAssistedBeanMembers(bean)` that reuses the member-validation rules: non-private fields/methods etc. — reuse `validateBean(bean)` as-is EXCEPT its no-arg-constructor demand: assisted beans have an `@Inject` constructor so `validateBean` passes unchanged; call it), and put `assistedRecipes.put(bean.getQualifiedName().toString(), new AssistedRecipe(bean, null, constructor))` + `assistedBeanNames.add(...)`.
   - Where `@Provides` methods are scanned: if `ModelFactory.isAssisted(method)`, skip `providesModel` generation; `models.validateAssistedRecipe(method)`; key `host.getQualifiedName() + "#" + method.getSimpleName()`; multiple assisted overloads with the same name on one host: put both under the same key → replace with a marker so Task 2 can report ambiguity — store instead `Map<String, List<AssistedRecipe>>`? Keep it simple and deterministic: `assistedRecipes` stays `Map<String, AssistedRecipe>`; on duplicate key insert, KEEP a sentinel by putting `null` `providesMethod`... NO. Use `Map<String, List<AssistedRecipe>>` for provides keys is overkill — instead: on collision, record the key in a `Set<String> ambiguousRecipeKeys` third component? Simplest honest structure: make the map value a `List<AssistedRecipe>` and let Task 2 error when `size() > 1` for a provides link. DECISION: `Map<String, List<AssistedRecipe>>`, constructor recipes always single-element lists.
   - Cycle edges: assisted beans are not in `beans()`, so they contribute no edges; container deps of assisted recipes are validated at runtime by the cycle guard (documented — v1).
6. `PlanStage.plan(...)`: after existing checks, direct-injection error — for every edge target whose qualified name is in `gathered.assistedBeanNames()`:

```java
        for (Map.Entry<TypeElement, List<TypeElement>> entry : gathered.edges().entrySet()) {
            for (TypeElement dependency : entry.getValue()) {
                if (gathered.assistedBeanNames().contains(dependency.getQualifiedName().toString())) {
                    diagnostics.error(
                            dependency.getSimpleName()
                                    + " has assisted parameters and is only available through an @AssistedFactory.",
                            entry.getKey());
                    return false;
                }
            }
        }
```

(`Provider<AssistedBean>` sites are absent from edges — the Provider-breaks-cycle rule — and fail at runtime as a
missing binding; acceptable per spec, note in the class comment where the check lives.)
7. Update every `new Gathered(...)` construction site for the two new components (empty for none).

- [ ] **Step 5: Run to verify GREEN, commit**

Run: `./mvnw clean verify > /tmp/a1.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

```bash
git add -A
git commit -m "feat(processor): gather assisted recipes; forbid bindings on them

@Assisted on an @Inject constructor or @Provides method makes the recipe
factory-only: no generated Factory, no key. Scope, qualifier, @ExposeAs and
collection markers on such recipes are compile errors, @Assisted anywhere
else is misplaced, and injecting an in-compilation assisted bean directly
names the factory route in its error."
```

---

### Task 2: `@AssistedFactory` link resolution & matching

**Files:**
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/AssistedFactoryModel.java`
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/AssistedMatcher.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java` (scan factory interfaces)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/Gathered.java` (carry factory models)
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/AssistedFactoryValidationTest.java`

**Interfaces:**
- Consumes: `Gathered.assistedRecipes()` (`Map<String, List<AssistedRecipe>>`), `annotationClassValue` dance, `Diagnostics`, `Elements.getTypeElement` for cross-module links.
- Produces: `record AssistedFactoryMethod(ExecutableElement method, AssistedRecipe recipe, List<Integer> assistedArgumentOrder)` where `assistedArgumentOrder.get(i)` = the factory-method parameter index feeding the recipe's i-th assisted parameter; `record AssistedFactoryModel(TypeElement factoryInterface, List<AssistedFactoryMethod> methods)`; `Gathered.assistedFactories()` (`List<AssistedFactoryModel>`). Task 3 generates from these.

- [ ] **Step 1: Write the failing validation tests**

`AssistedFactoryValidationTest` — one test per error row plus the happy paths (compilation-only; generation content is Task 3). Sources share:

```java
    private static final JavaFileObject REPORT = JavaFileObjects.forSourceString("example.Report", """
            package example;

            import be.imgn.inject.Assisted;
            import be.imgn.inject.Inject;

            public class Report {
                @Inject
                public Report(String db, @Assisted String id, @Assisted("footer") String footer) {}
            }
            """);
```

Tests (full sources in the same style as Task 1; each compiles `REPORT` plus a factory interface):

1. `exactReturnFactoryCompiles` — interface method `@AssistedFactory Report create(String id, @Assisted("footer") String footer);` → succeeded.
2. `supertypeReturnViaValueCompiles` — `Report implements Doc` (add a `Doc` interface source); `@AssistedFactory(Report.class) Doc create(String id, @Assisted("footer") String footer);` → succeeded.
3. `parameterMismatchIsAnError` — factory method missing the tagged parameter → failed, `hadErrorContaining("does not match")`.
4. `tagMismatchIsAnError` — factory has `@Assisted("header")` instead of `"footer"` → "does not match".
5. `returnNotAssignableIsAnError` — `@AssistedFactory(Report.class) String create(...)` → "not assignable".
6. `methodWithoutValueIsAnError` — `@AssistedFactory(method = "x") Report create(...)` → "requires the declaring class".
7. `valueWithoutAssistedConstructorIsAnError` — `@AssistedFactory(Dep.class)` where `Dep` has a plain `@Inject` ctor → "no assisted constructor".
8. `providesLinkCompiles` — `Config` with assisted `@Provides static Long legacy(String db, @Assisted int id)`; factory `@AssistedFactory(value = Config.class, method = "legacy") Long create(int id);` → succeeded.
9. `providesLinkUnknownMethodIsAnError` — `method = "nope"` → "no assisted".
10. `abstractNonFactoryMethodIsAnError` — interface with one `@AssistedFactory` method and one bare abstract method → "cannot be implemented".
11. `assistedFactoryOnClassMethodIsAnError` — `@AssistedFactory` on a concrete class's method → "interface method".
12. `directProvidesOfAnnotatedInterfaceStillCompiles` — DO NOT test the runtime collision here (runtime concern, existing DuplicateBindingException); skip.

- [ ] **Step 2: RED**

Run: `./mvnw clean verify > /tmp/a2.log 2>&1; echo EXIT=$?` — Expected: EXIT=1 (happy paths pass vacuously today — compilation succeeds because the annotation is inert — but every error test fails; that is the red signal).

- [ ] **Step 3: Implement**

`AssistedFactoryModel.java`:

```java
package be.imgn.inject.processor;

import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * A user factory interface to implement.
 *
 * @param factoryInterface the annotated interface
 * @param methods its resolved factory methods
 */
record AssistedFactoryModel(TypeElement factoryInterface, List<AssistedFactoryMethod> methods) {}
```

`AssistedFactoryMethod` (own file, same package):

```java
package be.imgn.inject.processor;

import java.util.List;
import javax.lang.model.element.ExecutableElement;

/**
 * One resolved {@code @AssistedFactory} method.
 *
 * @param method the interface method
 * @param recipe the linked recipe
 * @param assistedArgumentOrder for the recipe's i-th assisted parameter, the factory-method parameter index feeding it
 */
record AssistedFactoryMethod(ExecutableElement method, AssistedRecipe recipe, List<Integer> assistedArgumentOrder) {}
```

`AssistedMatcher.java` — pure resolution + matching, all errors through `Diagnostics`:

```java
package be.imgn.inject.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

import be.imgn.inject.Assisted;
import be.imgn.inject.AssistedFactory;

/** Resolves an {@code @AssistedFactory} method to its recipe and validates the assisted-parameter bijection. */
final class AssistedMatcher {

    private final Types types;
    private final Diagnostics diagnostics;

    AssistedMatcher(Types types, Diagnostics diagnostics) {
        this.types = types;
        this.diagnostics = diagnostics;
    }

    /** {@return the resolved method, or empty after reporting the failure} */
    Optional<AssistedFactoryMethod> resolve(
            ExecutableElement method, AssistedFactory annotation, Map<String, List<AssistedRecipe>> recipes) {
        TypeMirror linked = annotationClassValue(annotation);
        boolean hasValue = !(linked.toString().equals("void"));
        String recipeMethod = annotation.method();
        if (!recipeMethod.isEmpty() && !hasValue) {
            diagnostics.error("@AssistedFactory(method=...) requires the declaring class as value.", method);
            return Optional.empty();
        }
        TypeMirror implementation = hasValue ? linked : method.getReturnType();
        if (!(implementation instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement implementationElement)) {
            diagnostics.error("The @AssistedFactory implementation type is not a class.", method);
            return Optional.empty();
        }
        String key = recipeMethod.isEmpty()
                ? implementationElement.getQualifiedName().toString()
                : implementationElement.getQualifiedName() + "#" + recipeMethod;
        List<AssistedRecipe> candidates = recipes.getOrDefault(key, List.of());
        if (candidates.isEmpty()) {
            diagnostics.error(
                    recipeMethod.isEmpty()
                            ? implementationElement.getSimpleName() + " has no assisted constructor."
                            : "There is no assisted @Provides method \"" + recipeMethod + "\" on "
                                    + implementationElement.getSimpleName() + ".",
                    method);
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            diagnostics.error("The assisted @Provides method \"" + recipeMethod + "\" is ambiguous (overloaded).",
                    method);
            return Optional.empty();
        }
        AssistedRecipe recipe = candidates.get(0);
        TypeMirror produced = recipe.providesMethod() == null
                ? recipe.owner().asType()
                : recipe.executable().getReturnType();
        if (!types.isAssignable(types.erasure(produced), types.erasure(method.getReturnType()))) {
            diagnostics.error("The recipe's type " + produced + " is not assignable to the factory method's return"
                    + " type.", method);
            return Optional.empty();
        }
        return match(method, recipe);
    }

    private Optional<AssistedFactoryMethod> match(ExecutableElement method, AssistedRecipe recipe) {
        Map<String, Integer> factorySlots = new LinkedHashMap<>();
        List<? extends VariableElement> factoryParameters = method.getParameters();
        for (int index = 0; index < factoryParameters.size(); index++) {
            VariableElement parameter = factoryParameters.get(index);
            if (factorySlots.putIfAbsent(slot(parameter), index) != null) {
                diagnostics.error("Two factory parameters share type and tag; tag them with @Assisted(\"...\").",
                        parameter);
                return Optional.empty();
            }
        }
        List<Integer> order = new ArrayList<>();
        for (VariableElement parameter : recipe.executable().getParameters()) {
            if (parameter.getAnnotation(Assisted.class) == null) {
                continue;
            }
            Integer index = factorySlots.remove(slot(parameter));
            if (index == null) {
                diagnostics.error(
                        "The factory method does not match the recipe: missing assisted parameter " + slot(parameter)
                                + ".",
                        method);
                return Optional.empty();
            }
            order.add(index);
        }
        if (!factorySlots.isEmpty()) {
            diagnostics.error(
                    "The factory method does not match the recipe: extra parameter(s) "
                            + String.join(", ", factorySlots.keySet()) + ".",
                    method);
            return Optional.empty();
        }
        return Optional.of(new AssistedFactoryMethod(method, recipe, List.copyOf(order)));
    }

    private String slot(VariableElement parameter) {
        Assisted assisted = parameter.getAnnotation(Assisted.class);
        String tag = assisted == null ? "" : assisted.value();
        return types.erasure(parameter.asType()) + "#" + tag;
    }

    private static TypeMirror annotationClassValue(AssistedFactory annotation) {
        try {
            annotation.value();
            throw new IllegalStateException("annotation Class member did not throw");
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }
}
```

`GatherStage` — scan for `@AssistedFactory` methods (via `roundEnv.getElementsAnnotatedWith(AssistedFactory.class)`),
group by enclosing type, validate:
- enclosing element must be an INTERFACE, else "The @AssistedFactory annotation belongs on an interface method."
- every abstract method of that interface must carry `@AssistedFactory`, else "... has no @AssistedFactory and cannot be implemented." (default/static methods exempt: check `Modifier.ABSTRACT`).
- resolve each via `AssistedMatcher`; collect surviving `AssistedFactoryModel`s into `Gathered.assistedFactories()`.
- CROSS-MODULE recipes: when the matcher's `recipes` map misses the key AND the linked class is not in the current
  round, gather the recipe on demand from the linked `TypeElement` (its members are visible cross-module): find the
  `@Inject` constructor / named `@Provides` methods on `implementationElement` directly and build the
  `AssistedRecipe` on the fly. Implement as a fallback inside `GatherStage` before calling `resolve`, merging into
  the candidates map.

- [ ] **Step 4: GREEN, commit**

Run: `./mvnw clean verify > /tmp/a2.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

```bash
git add -A
git commit -m "feat(processor): resolve and validate @AssistedFactory methods

Method-level links: bare (return type is the implementation), value()
(implementation class, supertype returns), value()+method() (assisted
@Provides recipe). Bijective type+tag matching between factory parameters
and the recipe's assisted parameters, with the spec's full error table."
```

---

### Task 3: Generation — factory impl + registration

**Files:**
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/AssistedFactoryGenerator.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/InjectProcessor.java` (generate + register)
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GeneratedNames.java` (impl/factory names)
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/AssistedGenerationTest.java`

**Interfaces:**
- Consumes: `Gathered.assistedFactories()`, `ModelFactory` piece-builders for the recipe's container params/members (`parameters(...)`, `field(...)`, `pluginFields(...)` — reuse on the assisted bean), `RenderedQualifier`/`Qualifiers`, `ServiceRegistrar` (factory names list).
- Produces: for interface `example.ReportFactory` — `example.$ReportFactory$$Impl` (implements the interface) and `example.$ReportFactory$$Factory extends Factory<ReportFactory>` (registered via ServiceLoader). Naming via `GeneratedNames`: impl `"$" + join(enclosingChain, "$") + "$$Impl"`, factory the existing factory-name scheme.

- [ ] **Step 1: Write the failing golden tests**

`AssistedGenerationTest` (reuse Task 2's `REPORT` source):

1. `generatesImplAndFactory` — compile `REPORT` + exact-return factory interface; assert `generatedSourceFile("example.$ReportFactory$$Impl")` and `generatedSourceFile("example.$ReportFactory$$Factory")`; impl source `contains("implements ReportFactory")`, `contains("new Report(")`, `contains("context.getProvider(")` (container param resolved), and the factory source `contains("super(ReportFactory.class)")`.
2. `implPassesAssistedArgumentsInRecipeOrder` — factory method declares `(String footer, String id)` with tags swapped relative to the ctor; impl source `contains("new Report(") `and the argument expression order maps by tag not position (assert the generated call contains `", id, footer)"` given ctor `(db, @Assisted id, @Assisted("footer") footer)` and factory `create(@Assisted("footer") String footer, String id)`).
3. `memberInjectionRunsInsideCreate` — assisted bean with an `@Inject` field and an `@Inject` method; impl source contains the field assignment and method call after construction.
4. `providesRecipeInvokesHost` — static: impl `contains("Config.legacy(")`; instance recipe host: impl `contains("context.getProvider(Key.of(Config.class)")`.
5. `serviceFileListsTheGeneratedFactory` — `assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/services/be.imgn.inject.context.Factory")` contents contain `example.$ReportFactory$$Factory`.

- [ ] **Step 2: RED**

Run: `./mvnw clean verify > /tmp/a3.log 2>&1; echo EXIT=$?` — Expected: EXIT=1 (no generated files).

- [ ] **Step 3: Implement `AssistedFactoryGenerator`**

Shape (JavaPoet; constants mirror `BeanFactoryGenerator`'s):

```java
// $ReportFactory$$Impl
public final class $ReportFactory$$Impl implements ReportFactory {
    private final Context context;

    public $ReportFactory$$Impl(Context context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Report create(String id, String footer) {
        Report instance = new Report(
                context.getProvider(Key.of(String.class)).get(),   // container param (qualifier-aware)
                id,                                                 // assisted, by matched order
                footer);
        instance.configure(context.getProvider(Key.of(Clock.class)).get());   // @Inject methods
        instance.logger = Logger.getLogger(Report.class.getName());           // plugin fields
        instance.tag = context.getProvider(Key.of(String.class)).get();       // @Inject fields
        return instance;
    }
}
```

Rendering rules:
- Container parameter (recipe param without `@Assisted`): reuse `ModelFactory` dependency analysis — for each such param build the `ParameterModel` (type CodeBlock, qualifier, isProvider, pluginInitializer) via the EXISTING `parameters(...)` path filtered to non-assisted params; render `context.getProvider(Key.of(<type>, <marker|instance>)).get()` (omit `.get()` when `isProvider`; use plugin initializer verbatim when present; the qualifier slots come from `Qualifiers.markerArgument`/`instanceArgument` — but note `Key.of` overloads take ONE qualifier argument, so render `Key.of(<type>)` when unqualified, `Key.of(<type>, <expr>)` with the marker class or instance expression otherwise).
- Assisted parameter: the factory-method parameter name at the matched index (`assistedArgumentOrder`).
- Provides recipes: `Config.legacy(args...)` when static; otherwise resolve the host first: `Config host = context.getProvider(Key.of(Config.class)).get();` then `host.legacy(args...)`. No member injection for provides recipes (the recipe returns an arbitrary object; only constructor recipes get field/method injection).
- Member injection (constructor recipes only): reuse the assisted bean's gathered `FieldModel`/`MethodModel`/`PluginField` lists — build them in Task 3 by calling the same `ModelFactory` helpers used by `buildModel`, applied to the recipe owner.
- `$ReportFactory$$Factory`:

```java
public final class $ReportFactory$$Factory extends Factory<ReportFactory> {
    public $ReportFactory$$Factory() {
        super(ReportFactory.class);
    }

    @Override
    protected ReportFactory createInstance(Context context) {
        return new $ReportFactory$$Impl(context);
    }

    @Override
    public AnnotationMetadata annotationMetadata() {
        return <metadata of the interface type>;   // Annotations.metadata(List.of(interface), elements)
    }
}
```

(default `exposedKeys()` = `Key.of(ReportFactory.class)` — exactly right; conditions on the interface work through
the metadata.) `InjectProcessor.process`: after the provides loop, for each `AssistedFactoryModel` write both files
and add the factory's qualified name to `factoryNames` (ServiceRegistrar picks it up in the final round).

- [ ] **Step 4: GREEN, commit**

Run: `./mvnw clean verify > /tmp/a3.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

```bash
git add -A
git commit -m "feat(processor): generate assisted factory implementations

One impl class per @AssistedFactory interface - container parameters
resolved through the Context by key (qualifier- and Provider-aware, plugin
fields included), assisted parameters passed through in matched order,
constructor recipes get full member injection - plus a Factory<Interface>
registered through the ServiceLoader like every other binding."
```

---

### Task 4: Classpath end-to-end example

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Badge.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/BadgeFactory.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/LegacyStamp.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/StampConfig.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/StampFactory.java`
- Modify: `inject-examples/src/test/java/be/imgn/inject/examples/ExamplesClasspathTest.java`

Beans:

```java
// Badge.java — constructor recipe: container dep + two assisted (one tagged)
package be.imgn.inject.examples;

import be.imgn.inject.Assisted;
import be.imgn.inject.Inject;

/** An assisted bean: the registry comes from the container, the labels from the factory caller. */
public final class Badge {
    private final Registry registry;
    private final String owner;
    private final String role;

    /**
     * @param registry the container-resolved registry
     * @param owner the caller-supplied owner label
     * @param role the caller-supplied role label
     */
    @Inject
    public Badge(Registry registry, @Assisted String owner, @Assisted("role") String role) {
        this.registry = registry;
        this.owner = owner;
        this.role = role;
    }

    /** {@return the container-resolved registry} */
    public Registry registry() {
        return registry;
    }

    /** {@return the caller-supplied owner label} */
    public String owner() {
        return owner;
    }

    /** {@return the caller-supplied role label} */
    public String role() {
        return role;
    }
}
```

```java
// BadgeFactory.java
package be.imgn.inject.examples;

import be.imgn.inject.Assisted;
import be.imgn.inject.AssistedFactory;

/** Creates {@link Badge}s from caller-supplied labels. */
public interface BadgeFactory {
    /**
     * {@return a badge for {@code owner} with {@code role}}
     *
     * @param owner the owner label
     * @param role the role label
     */
    @AssistedFactory
    Badge create(String owner, @Assisted("role") String role);
}
```

```java
// LegacyStamp.java — a "third-party-style" type with no @Inject
package be.imgn.inject.examples;

/** A type without an injectable constructor, built through an assisted {@code @Provides} recipe. */
public final class LegacyStamp {
    private final String text;

    /** @param text the stamp text */
    public LegacyStamp(String text) {
        this.text = text;
    }

    /** {@return the stamp text} */
    public String text() {
        return text;
    }
}
```

```java
// StampConfig.java — assisted @Provides recipe (static)
package be.imgn.inject.examples;

import be.imgn.inject.Assisted;
import be.imgn.inject.Named;
import be.imgn.inject.Provides;

/** Hosts the assisted {@code @Provides} recipe for {@link LegacyStamp}. */
public class StampConfig {
    /**
     * {@return a stamp combining the container-resolved greeting with the caller's suffix}
     *
     * @param greeting the container-resolved greeting
     * @param suffix the caller-supplied suffix
     */
    @Provides
    public static LegacyStamp stamp(@Named("greeting") String greeting, @Assisted String suffix) {
        return new LegacyStamp(greeting + " " + suffix);
    }
}
```

```java
// StampFactory.java
package be.imgn.inject.examples;

import be.imgn.inject.AssistedFactory;

/** Creates {@link LegacyStamp}s through the assisted {@code @Provides} recipe. */
public interface StampFactory {
    /**
     * {@return a stamp with the caller-supplied suffix}
     *
     * @param suffix the suffix
     */
    @AssistedFactory(value = StampConfig.class, method = "stamp")
    LegacyStamp create(String suffix);
}
```

Test to add:

```java
    @Test
    void assistedFactoriesMixContainerAndCallerArguments() {
        Injector injector = Injector.create();

        BadgeFactory badges = injector.getInstance(BadgeFactory.class);
        Badge badge = badges.create("olivier", "admin");
        assertThat(badge)
                .returns("olivier", Badge::owner)
                .returns("admin", Badge::role)
                .returns(injector.getInstance(Registry.class), Badge::registry);   // singleton respected
        assertThat(badges.create("olivier", "admin")).isNotSameAs(badge);          // fresh per call

        LegacyStamp stamp = injector.getInstance(StampFactory.class).create("world");
        assertThat(stamp.text()).isEqualTo("Hello world");
    }
```

(`Registry` is `@Singleton`; the `@Named("greeting")` binding provides `"Hello"` — both exist in the module today.)

Run: `./mvnw clean verify > /tmp/a4.log 2>&1; echo EXIT=$?` — Expected: EXIT=0.

```bash
git add inject-examples
git commit -m "feat(examples): assisted injection - constructor and @Provides recipes"
```

---

### Task 5: Module-path example + final sweep

**Files:**
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/Ticket.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TicketFactory.java`
- Modify: `inject-examples-module/src/main/java/module-info.java`
- Modify: `inject-examples-module/src/test/java/be/imgn/inject/examples/modular/ModulePathTest.java`

```java
// Ticket.java
package be.imgn.inject.examples.modular;

import be.imgn.inject.Assisted;
import be.imgn.inject.Inject;

/** An assisted bean on the module path: the widget is container-resolved, the label caller-supplied. */
public final class Ticket {
    private final Widget widget;
    private final String label;

    /**
     * @param widget the container-resolved widget
     * @param label the caller-supplied label
     */
    @Inject
    public Ticket(Widget widget, @Assisted String label) {
        this.widget = widget;
        this.label = label;
    }

    /** {@return the container-resolved widget} */
    public Widget widget() {
        return widget;
    }

    /** {@return the caller-supplied label} */
    public String label() {
        return label;
    }
}
```

```java
// TicketFactory.java
package be.imgn.inject.examples.modular;

import be.imgn.inject.AssistedFactory;

/** Creates {@link Ticket}s. */
public interface TicketFactory {
    /**
     * {@return a ticket with {@code label}}
     *
     * @param label the label
     */
    @AssistedFactory
    Ticket create(String label);
}
```

`module-info.java` provides clause gains exactly one factory (the impl class is not a service):

```java
            be.imgn.inject.examples.modular.$TicketFactory$$Factory;
```

Test:

```java
    @Test
    void assistedFactoryWorksOnTheModulePath() {
        Injector injector = Injector.create();
        Ticket ticket = injector.getInstance(TicketFactory.class).create("vip");
        assertThat(ticket)
                .returns("vip", Ticket::label)
                .returns(injector.getInstance(Widget.class), Ticket::widget);   // Widget is @Singleton
    }
```

Run: `./mvnw clean verify > /tmp/a5.log 2>&1; echo EXIT=$?` — Expected: EXIT=0; `grep -E "Passed: 3" /tmp/a5.log` shows the invoker ITs pass.

```bash
git add inject-examples-module
git commit -m "feat(examples): assisted factory on the module path"
```

---

## Self-Review Notes

- Spec coverage: merged-model recipe gathering + no-key/no-Factory (T1), all recipe prohibitions + misplaced + direct-injection error (T1), link resolution incl. cross-module fallback and all error rows (T2), bijective tag matching incl. order independence (T2 + T3 golden #2), generation with container/assisted/plugin/member injection + provides hosts static & instance (T3), ServiceLoader registration + conditions-via-interface-metadata (T3), fresh-per-call + scope-respect + tag + provides recipe e2e (T4), module path (T5). Hand-written-factory escape hatch needs no task (absence of machinery). Provider<Set>-style laziness out of scope per spec.
- Type consistency: `AssistedRecipe(owner, providesMethod, executable)` produced in T1, consumed by T2 matcher and T3 generator; `AssistedFactoryMethod.assistedArgumentOrder` produced in T2, consumed in T3 argument rendering; `Gathered` gains `assistedRecipes` (`Map<String, List<AssistedRecipe>>`), `assistedBeanNames` (`Set<String>`), `assistedFactories` (`List<AssistedFactoryModel>`).
- Known risk: `GatherStage`/`Gathered` constructor-site churn — three new components ripple through every `new Gathered(...)`; T1 and T2 each touch it once, sequentially.
