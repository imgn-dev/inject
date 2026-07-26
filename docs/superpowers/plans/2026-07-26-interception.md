# Method Interception Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A method annotated with an `@InterceptingAnnotation`-marked annotation runs inside a chain of `MethodInterceptor` beans, wired by a compile-time generated subclass — no reflection anywhere.

**Architecture:** The processor emits `$Bean$$Intercepted extends Bean`, overriding each intercepted method to dispatch through a `MethodInterception` chain whose innermost hop calls `super`. The bean's existing generated factory constructs the proxy instead of the bean, so exposure, scoping, member injection, and lifecycle are untouched. Interceptors are ordinary beans marked `@Intercepts(TheAnnotation.class)`; `DefaultContext` indexes them by annotation type, so no new `ServiceLoader` service and no new module-path `provides` directive.

**Tech Stack:** Java 21, JavaPoet (`com.palantir.javapoet`), JUnit 6, AssertJ, `google-compile-testing` + Truth for processor tests, Maven wrapper.

**Design spec:** `docs/superpowers/specs/2026-07-26-interception-design.md`

## Global Constraints

- Build command is **`./mvnw clean verify`** — never `install`, never a globally-installed `mvn`.
- Check the build's **exit code directly**; never pipe `verify` through `tail`/`head`, which masks Maven's status.
- Java 21. `be.imgn.inject` is NullAway-annotated: every public/protected parameter gets `Objects.requireNonNull(arg, "name")`, nullable types get `org.jspecify.annotations.Nullable`.
- Javadoc with `doclint` runs at `package`: every public and protected type, method, and parameter needs Javadoc.
- Spotless (Palantir Java Format) runs at `validate` and rewrites sources; run `./mvnw clean verify` before `git add` so committed files are already formatted.
- Checkstyle forbids fully-qualified names in code — import instead.
- Tests use AssertJ **fluent chaining**: one `assertThat(subject)` with chained assertions, not repeated `assertThat` calls on the same object.
- Generated types live in the target's package; names are `$` + enclosing-class chain joined by `$` + `$$<Kind>`. Interception adds the `$$Intercepted` kind.
- Docs are part of the change: a feature is not done until `docs/features/` describes the current state.

## File Structure

**New — runtime (`inject/src/main/java/be/imgn/inject/`)**
- `InterceptingAnnotation.java` — meta-annotation marking an annotation as an interception trigger.
- `Intercepts.java` — marks a bean as the interceptor for one intercepting annotation.
- `InterceptionException.java` — an undeclared throwable escaped a chain, or an interceptor returned `null` for a primitive.
- `InterceptorResolutionException.java` — no interceptor bean for an annotation in use.
- `context/MethodInterceptor.java` — the SPI users implement.
- `context/MethodInvocation.java` — what an interceptor sees.
- `context/Joinpoint.java` — the innermost hop (`super.method(args)`).
- `context/InterceptedMethod.java` — immutable call-site descriptor; one static per intercepted method.
- `context/MethodInterception.java` — context-bound chain; one per proxy instance per method.

**Modified — runtime**
- `context/Context.java` — `interceptor(Class)` default method.
- `context/Factory.java` — `intercepts()` override point.
- `context/DefaultContext.java` — interceptor index, duplicate detection.
- `context/ChildContext.java` — delegates `interceptor` to the parent.

**New — processor (`inject-processor/src/main/java/be/imgn/inject/processor/`)**
- `InterceptionModel.java` — per-bean proxy model.
- `InterceptedMethodModel.java` — per-method model.
- `ProxyParameter.java` — a name/type pair for proxy signatures.
- `InterceptedProxyGenerator.java` — emits `$Bean$$Intercepted`.

**Modified — processor**
- `Annotations.java` — capture `@InterceptingAnnotation` as a stereotype; expose `annotationValue` to the new generator.
- `ModelFactory.java` — build the interception model, validate placement, exclude `MethodInterceptor` from default exposure, read `@Intercepts`.
- `BeanModel.java` — carry `interception` and `interceptsAnnotation`.
- `GatherStage.java` — treat intercepted and `@Intercepts` types as beans.
- `BeanFactoryGenerator.java` — construct the proxy; emit the `intercepts()` override.
- `GeneratedNames.java` — `interceptedName`.
- `InjectProcessor.java` — write the proxy file.

**New — examples and docs**
- `inject-examples/.../Timed.java`, `Audited.java`, `CallLog.java`, `TimingInterceptor.java`, `AuditInterceptor.java`, `Checkout.java`, and `InterceptionExampleTest.java`.
- `inject-examples-module/.../Traced.java`, `TracingInterceptor.java`, `TracedService.java` + `module-info.java` `provides` entries.
- `docs/features/interception.md`, plus edits to `docs/features/README.md` and `docs/features/type-exposure.md`.

---

### Task 1: Runtime SPI and chain engine

Builds everything an interceptor chain needs, testable with hand-written interceptors and a stub `Context`. No processor work, no `DefaultContext` work.

**Files:**
- Create: `inject/src/main/java/be/imgn/inject/InterceptingAnnotation.java`
- Create: `inject/src/main/java/be/imgn/inject/Intercepts.java`
- Create: `inject/src/main/java/be/imgn/inject/InterceptionException.java`
- Create: `inject/src/main/java/be/imgn/inject/InterceptorResolutionException.java`
- Create: `inject/src/main/java/be/imgn/inject/context/MethodInterceptor.java`
- Create: `inject/src/main/java/be/imgn/inject/context/MethodInvocation.java`
- Create: `inject/src/main/java/be/imgn/inject/context/Joinpoint.java`
- Create: `inject/src/main/java/be/imgn/inject/context/InterceptedMethod.java`
- Create: `inject/src/main/java/be/imgn/inject/context/MethodInterception.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/Context.java`
- Test: `inject/src/test/java/be/imgn/inject/context/MethodInterceptionTest.java`

**Interfaces:**
- Consumes: existing `be.imgn.inject.Provider`, `be.imgn.inject.context.AnnotationValue`, `be.imgn.inject.context.Context`, `be.imgn.inject.InjectionException`.
- Produces:
  - `InterceptedMethod.of(Class<?> declaringType, String methodName, Class<?> returnType, List<Class<?>> parameterTypes, List<AnnotationValue> bindings)` → `InterceptedMethod`
  - `InterceptedMethod.bind(Context)` → `MethodInterception`
  - `MethodInterception.invoke(Object target, @Nullable Object[] arguments, Joinpoint joinpoint)` → `@Nullable Object`, `throws Throwable`
  - `Joinpoint.invoke(@Nullable Object[] arguments)` → `@Nullable Object`, `throws Throwable`
  - `MethodInterceptor.invoke(MethodInvocation)` → `@Nullable Object`, `throws Throwable`
  - `Context.interceptor(Class<? extends Annotation>)` → `Provider<MethodInterceptor>`
  - `InterceptionException(String)`, `InterceptionException(String, Throwable)`, `InterceptorResolutionException(String)`

- [ ] **Step 1: Write the failing test**

Create `inject/src/test/java/be/imgn/inject/context/MethodInterceptionTest.java`:

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;

class MethodInterceptionTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Outer {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Inner {}

    private static final AnnotationValue OUTER = AnnotationValue.of(Outer.class, Map.of("value", "o"), Set.of());
    private static final AnnotationValue INNER = AnnotationValue.of(Inner.class, Map.of("value", "i"), Set.of());

    /** A context that resolves interceptors from a fixed map and nothing else. */
    record StubContext(Map<Class<? extends Annotation>, MethodInterceptor> interceptors) implements Context {
        @Override
        public <T> Provider<T> getProvider(Key<T> key) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType) {
            MethodInterceptor interceptor = interceptors.get(annotationType);
            if (interceptor == null) {
                throw new InterceptorResolutionException("No interceptor bean for @" + annotationType.getSimpleName());
            }
            return () -> interceptor;
        }
    }

    /** Records entry and exit around the rest of the chain. */
    record Recorder(String name, List<String> log) implements MethodInterceptor {
        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            log.add(name + ":enter:" + invocation.annotation().annotationType().getSimpleName());
            Object result = invocation.proceed();
            log.add(name + ":exit");
            return result;
        }
    }

    private static InterceptedMethod greet(List<AnnotationValue> bindings) {
        return InterceptedMethod.of(String.class, "greet", String.class, List.of(String.class), bindings);
    }

    @Test
    void runsInterceptorsInBindingOrderAroundTheJoinpoint() {
        List<String> log = new ArrayList<>();
        MethodInterception chain = greet(List.of(OUTER, INNER))
                .bind(new StubContext(Map.of(
                        Outer.class, new Recorder("outer", log),
                        Inner.class, new Recorder("inner", log))));

        Object result = invoke(chain, new Object[] {"world"}, arguments -> "hi " + arguments[0]);

        assertThat(result).isEqualTo("hi world");
        assertThat(log).containsExactly("outer:enter:Outer", "inner:enter:Inner", "inner:exit", "outer:exit");
    }

    @Test
    void proceedWithArgumentsSubstitutesThemForTheRestOfTheChain() {
        MethodInterceptor stripping = invocation -> invocation.proceed(((String) invocation.arguments().get(0)).strip());
        MethodInterception chain =
                greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, stripping)));

        assertThat(invoke(chain, new Object[] {"  padded  "}, arguments -> "[" + arguments[0] + "]"))
                .isEqualTo("[padded]");
    }

    @Test
    void invocationExposesImmutableCallSiteMetadata() {
        MethodInterceptor probe = invocation -> {
            assertThat(invocation)
                    .returns("greet", MethodInvocation::methodName)
                    .returns(String.class, MethodInvocation::declaringType)
                    .returns(String.class, MethodInvocation::returnType)
                    .returns(List.of(String.class), MethodInvocation::parameterTypes)
                    .returns("target", MethodInvocation::target);
            assertThat(invocation.arguments()).containsExactly("world").isUnmodifiable();
            return invocation.proceed();
        };
        MethodInterception chain = greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, probe)));

        assertThat(invoke(chain, new Object[] {"world"}, arguments -> "ok")).isEqualTo("ok");
    }

    @Test
    void proceedRejectsWrongArity() {
        MethodInterceptor bad = invocation -> invocation.proceed("a", "b");
        MethodInterception chain = greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, bad)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> invoke(chain, new Object[] {"world"}, arguments -> "ok"))
                .withMessageContaining("greet")
                .withMessageContaining("1")
                .withMessageContaining("2");
    }

    @Test
    void proceedRejectsWrongArgumentType() {
        MethodInterceptor bad = invocation -> invocation.proceed(42);
        MethodInterception chain = greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, bad)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> invoke(chain, new Object[] {"world"}, arguments -> "ok"))
                .withMessageContaining("argument 0")
                .withMessageContaining("java.lang.String");
    }

    @Test
    void proceedRejectsNullForAPrimitiveParameter() {
        InterceptedMethod count =
                InterceptedMethod.of(String.class, "count", int.class, List.of(int.class), List.of(OUTER));
        MethodInterceptor bad = invocation -> invocation.proceed(new Object[] {null});
        MethodInterception chain = count.bind(new StubContext(Map.of(Outer.class, bad)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> invoke(chain, new Object[] {1}, arguments -> 1))
                .withMessageContaining("argument 0")
                .withMessageContaining("int");
    }

    @Test
    void annotationReportsTheRunningInterceptorEvenAfterProceed() {
        List<String> seen = new ArrayList<>();
        MethodInterceptor outer = invocation -> {
            Object result = invocation.proceed();
            seen.add("after:" + invocation.annotation().annotationType().getSimpleName());
            return result;
        };
        MethodInterceptor inner = invocation -> {
            seen.add("during:" + invocation.annotation().annotationType().getSimpleName());
            return invocation.proceed();
        };
        MethodInterception chain = greet(List.of(OUTER, INNER))
                .bind(new StubContext(Map.of(Outer.class, outer, Inner.class, inner)));

        invoke(chain, new Object[] {"world"}, arguments -> "ok");

        assertThat(seen).containsExactly("during:Inner", "after:Outer");
    }

    @Test
    void oneChainServesConcurrentCallsIndependently() throws Exception {
        MethodInterceptor passthrough = MethodInvocation::proceed;
        MethodInterception chain = greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, passthrough)));
        List<Callable<Object>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String argument = "call-" + index;
            calls.add(() -> invoke(chain, new Object[] {argument}, arguments -> "seen:" + arguments[0]));
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<Object>> futures = executor.invokeAll(calls);
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            assertThat(results)
                    .containsExactly(
                            "seen:call-0", "seen:call-1", "seen:call-2", "seen:call-3",
                            "seen:call-4", "seen:call-5", "seen:call-6", "seen:call-7");
        }
    }

    @Test
    void bindFailsWhenNoInterceptorHandlesTheAnnotation() {
        assertThatExceptionOfType(InterceptorResolutionException.class)
                .isThrownBy(() -> greet(List.of(OUTER)).bind(new StubContext(Map.of())))
                .withMessageContaining("Outer");
    }

    @Test
    void aContextWithoutInterceptorSupportRejectsTheLookup() {
        Context minimal = new Context() {
            @Override
            public <T> Provider<T> getProvider(Key<T> key) {
                throw new UnsupportedOperationException("not used");
            }
        };

        assertThatExceptionOfType(InterceptorResolutionException.class)
                .isThrownBy(() -> minimal.interceptor(Outer.class))
                .withMessageContaining("Outer");
    }

    /** Runs a chain, rethrowing any checked throwable as an unchecked one so tests stay readable. */
    private static Object invoke(MethodInterception chain, Object[] arguments, Joinpoint joinpoint) {
        try {
            return chain.invoke("target", arguments, joinpoint);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -pl inject test -Dtest=MethodInterceptionTest
```

Expected: FAIL — compilation errors, `package be.imgn.inject.context does not exist`-style for `MethodInterception`, `InterceptedMethod`, `Joinpoint`, `MethodInterceptor`, `MethodInvocation`, and `cannot find symbol: class InterceptorResolutionException`.

- [ ] **Step 3: Write the annotations and exceptions**

`inject/src/main/java/be/imgn/inject/InterceptingAnnotation.java`:

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Meta-annotation marking an annotation as an interception trigger. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface InterceptingAnnotation {}
```

`inject/src/main/java/be/imgn/inject/Intercepts.java`:

```java
package be.imgn.inject;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean as the {@link be.imgn.inject.context.MethodInterceptor} handling one intercepting annotation. The bean
 * is an ordinary bean in every other respect: it is injected, scoped, and conditioned like any other.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Intercepts {
    /** {@return the intercepting annotation this interceptor handles} */
    Class<? extends Annotation> value();
}
```

`inject/src/main/java/be/imgn/inject/InterceptionException.java`:

```java
package be.imgn.inject;

/** Thrown when an interceptor throws something the intercepted method does not declare, or returns an illegal value. */
public class InterceptionException extends InjectionException {
    /** @param message the detail message */
    public InterceptionException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause the throwable the interceptor raised
     */
    public InterceptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`inject/src/main/java/be/imgn/inject/InterceptorResolutionException.java`:

```java
package be.imgn.inject;

/** Thrown when an intercepting annotation is in use but no bean is annotated {@link Intercepts} for it. */
public class InterceptorResolutionException extends InjectionException {
    /** @param message the detail message */
    public InterceptorResolutionException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write the SPI interfaces**

`inject/src/main/java/be/imgn/inject/context/MethodInterceptor.java`:

```java
package be.imgn.inject.context;

import org.jspecify.annotations.Nullable;

/**
 * Runs around an intercepted method call. Implementations are ordinary beans annotated
 * {@link be.imgn.inject.Intercepts}, so they are injected, scoped, and conditioned like any other bean.
 */
public interface MethodInterceptor {
    /**
     * {@return the call's result, which may be the value {@link MethodInvocation#proceed()} returned or one of this
     * interceptor's own}
     *
     * @param invocation the call being intercepted
     * @throws Throwable anything the rest of the chain or this interceptor raises
     */
    @Nullable Object invoke(MethodInvocation invocation) throws Throwable;
}
```

`inject/src/main/java/be/imgn/inject/context/MethodInvocation.java`:

```java
package be.imgn.inject.context;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One intercepted call, as seen by one interceptor. Every member is a compile-time constant baked into the generated
 * proxy: reading this needs no reflection.
 */
public interface MethodInvocation {

    /** {@return the intercepted instance} */
    Object target();

    /** {@return the intercepted method's name} */
    String methodName();

    /** {@return the type declaring the intercepted method} */
    Class<?> declaringType();

    /** {@return the intercepted method's return type; {@code void.class} for a void method} */
    Class<?> returnType();

    /** {@return the intercepted method's parameter types, in order} */
    List<Class<?>> parameterTypes();

    /** {@return the call arguments, in order; unmodifiable} */
    List<@Nullable Object> arguments();

    /** {@return the annotation that pulled this interceptor into the chain, with its compile-time member values} */
    AnnotationValue annotation();

    /**
     * {@return the result of the rest of the chain, with the current arguments}
     *
     * @throws Throwable anything the rest of the chain raises
     */
    @Nullable Object proceed() throws Throwable;

    /**
     * {@return the result of the rest of the chain, with {@code arguments} replacing the current ones}
     *
     * @param arguments the substituted arguments, positionally
     * @throws IllegalArgumentException if the count or a type does not match the intercepted method
     * @throws Throwable anything the rest of the chain raises
     */
    @Nullable Object proceed(@Nullable Object... arguments) throws Throwable;
}
```

`inject/src/main/java/be/imgn/inject/context/Joinpoint.java`:

```java
package be.imgn.inject.context;

import org.jspecify.annotations.Nullable;

/** The innermost hop of an interceptor chain: the call to the bean's own implementation. */
@FunctionalInterface
public interface Joinpoint {
    /**
     * {@return the intercepted method's own result}
     *
     * @param arguments the arguments to call it with, positionally
     * @throws Throwable anything the method raises
     */
    @Nullable Object invoke(@Nullable Object[] arguments) throws Throwable;
}
```

- [ ] **Step 5: Write the chain engine**

`inject/src/main/java/be/imgn/inject/context/InterceptedMethod.java`:

```java
package be.imgn.inject.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import be.imgn.inject.Provider;

/**
 * An intercepted method's immutable call-site description: what the method is, and which annotations bind interceptors
 * to it, outermost first. Generated proxies hold one static instance per intercepted method and {@link #bind} it once
 * per proxy instance.
 */
public final class InterceptedMethod {

    private final Class<?> declaringType;
    private final String methodName;
    private final Class<?> returnType;
    private final List<Class<?>> parameterTypes;
    private final List<AnnotationValue> bindings;

    private InterceptedMethod(
            Class<?> declaringType,
            String methodName,
            Class<?> returnType,
            List<Class<?>> parameterTypes,
            List<AnnotationValue> bindings) {
        this.declaringType = declaringType;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.bindings = bindings;
    }

    /**
     * {@return a call-site description}
     *
     * @param declaringType the type declaring the intercepted method
     * @param methodName the method name
     * @param returnType the return type; {@code void.class} for a void method
     * @param parameterTypes the parameter types, in order
     * @param bindings the intercepting annotations, outermost first; must not be empty
     */
    public static InterceptedMethod of(
            Class<?> declaringType,
            String methodName,
            Class<?> returnType,
            List<Class<?>> parameterTypes,
            List<AnnotationValue> bindings) {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(returnType, "returnType");
        List<Class<?>> parameters = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
        List<AnnotationValue> copied = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("An intercepted method needs at least one binding: " + methodName);
        }
        return new InterceptedMethod(declaringType, methodName, returnType, parameters, copied);
    }

    /** {@return the type declaring the intercepted method} */
    public Class<?> declaringType() {
        return declaringType;
    }

    /** {@return the method name} */
    public String methodName() {
        return methodName;
    }

    /** {@return the return type} */
    public Class<?> returnType() {
        return returnType;
    }

    /** {@return the parameter types, in order} */
    public List<Class<?>> parameterTypes() {
        return parameterTypes;
    }

    /** {@return the intercepting annotations, outermost first} */
    public List<AnnotationValue> bindings() {
        return bindings;
    }

    /**
     * {@return this call site bound to {@code context}: one interceptor provider per binding, in order}
     *
     * @param context the resolution context
     * @throws be.imgn.inject.InterceptorResolutionException if a binding has no interceptor bean
     */
    public MethodInterception bind(Context context) {
        Objects.requireNonNull(context, "context");
        List<Provider<MethodInterceptor>> providers = new ArrayList<>(bindings.size());
        for (AnnotationValue binding : bindings) {
            providers.add(context.interceptor(binding.annotationType()));
        }
        return new MethodInterception(this, List.copyOf(providers));
    }

    @Override
    public String toString() {
        return declaringType.getName() + "." + methodName;
    }
}
```

`inject/src/main/java/be/imgn/inject/context/MethodInterception.java`:

```java
package be.imgn.inject.context;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Provider;

/**
 * A context-bound interceptor chain for one intercepted method: the call site plus one provider per binding, in order.
 * Created by {@link InterceptedMethod#bind(Context)} and held by the generated proxy.
 *
 * <p>All per-call state lives in a short-lived invocation object created per chain position, so concurrent calls and an
 * interceptor that calls {@code proceed()} more than once are both correct.
 */
public final class MethodInterception {

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class);

    private final InterceptedMethod method;
    private final List<Provider<MethodInterceptor>> interceptors;

    MethodInterception(InterceptedMethod method, List<Provider<MethodInterceptor>> interceptors) {
        this.method = method;
        this.interceptors = interceptors;
    }

    /**
     * {@return the call's result, after running every interceptor around {@code joinpoint}}
     *
     * @param target the intercepted instance
     * @param arguments the call arguments, positionally; the chain takes ownership of the array
     * @param joinpoint the call to the bean's own implementation
     * @throws Throwable anything an interceptor or the method itself raises
     */
    public @Nullable Object invoke(Object target, @Nullable Object[] arguments, Joinpoint joinpoint) throws Throwable {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(joinpoint, "joinpoint");
        return dispatch(0, target, arguments, joinpoint);
    }

    private @Nullable Object dispatch(int position, Object target, @Nullable Object[] arguments, Joinpoint joinpoint)
            throws Throwable {
        if (position == interceptors.size()) {
            return joinpoint.invoke(arguments);
        }
        return interceptors.get(position).get().invoke(new Invocation(position, target, arguments, joinpoint));
    }

    /** {@return {@code candidate} if it matches the intercepted method's parameter list} */
    private @Nullable Object[] validated(@Nullable Object[] candidate) {
        List<Class<?>> types = method.parameterTypes();
        if (candidate.length != types.size()) {
            throw new IllegalArgumentException(method + " takes " + types.size() + " arguments but proceed() was given "
                    + candidate.length + ".");
        }
        for (int index = 0; index < candidate.length; index++) {
            Class<?> declared = types.get(index);
            Object value = candidate[index];
            if (value == null) {
                if (declared.isPrimitive()) {
                    throw new IllegalArgumentException(
                            method + " argument " + index + " is " + declared.getName() + " and cannot be null.");
                }
                continue;
            }
            Class<?> accepted = declared.isPrimitive() ? WRAPPERS.get(declared) : declared;
            if (!accepted.isInstance(value)) {
                throw new IllegalArgumentException(method + " argument " + index + " expects " + declared.getName()
                        + " but got " + value.getClass().getName() + ".");
            }
        }
        return candidate;
    }

    /** One chain position, presented to the interceptor at that position. Immutable, so re-entry is safe. */
    private final class Invocation implements MethodInvocation {

        private final int position;
        private final Object target;
        private final @Nullable Object[] arguments;
        private final Joinpoint joinpoint;

        Invocation(int position, Object target, @Nullable Object[] arguments, Joinpoint joinpoint) {
            this.position = position;
            this.target = target;
            this.arguments = arguments;
            this.joinpoint = joinpoint;
        }

        @Override
        public Object target() {
            return target;
        }

        @Override
        public String methodName() {
            return method.methodName();
        }

        @Override
        public Class<?> declaringType() {
            return method.declaringType();
        }

        @Override
        public Class<?> returnType() {
            return method.returnType();
        }

        @Override
        public List<Class<?>> parameterTypes() {
            return method.parameterTypes();
        }

        @Override
        public List<@Nullable Object> arguments() {
            return Collections.unmodifiableList(Arrays.asList(arguments));
        }

        @Override
        public AnnotationValue annotation() {
            return method.bindings().get(position);
        }

        @Override
        public @Nullable Object proceed() throws Throwable {
            return dispatch(position + 1, target, arguments, joinpoint);
        }

        @Override
        public @Nullable Object proceed(@Nullable Object... arguments) throws Throwable {
            Objects.requireNonNull(arguments, "arguments");
            return dispatch(position + 1, target, validated(arguments), joinpoint);
        }
    }
}
```

- [ ] **Step 6: Add the `Context` lookup**

In `inject/src/main/java/be/imgn/inject/context/Context.java`, add the import `be.imgn.inject.InterceptorResolutionException` and this method after `getBeanDefinitionsByStereotype`:

```java
    /**
     * {@return the provider for the interceptor handling {@code annotationType}}
     *
     * <p>The default rejects every lookup, so minimal {@code Context} implementations need not supply an index.
     *
     * @param annotationType the intercepting annotation
     * @throws InterceptorResolutionException if no bean is annotated {@code @Intercepts} for it
     */
    default Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(annotationType, "annotationType");
        throw new InterceptorResolutionException("No interceptor bean for @" + annotationType.getSimpleName()
                + "; annotate a bean with @Intercepts(" + annotationType.getSimpleName() + ".class).");
    }
```

Add `import java.util.Objects;` to the same file.

- [ ] **Step 7: Run the test to verify it passes**

```bash
./mvnw -pl inject test -Dtest=MethodInterceptionTest
```

Expected: PASS — 10 tests.

- [ ] **Step 8: Run the full build**

```bash
./mvnw clean verify
echo "exit=$?"
```

Expected: `BUILD SUCCESS` and `exit=0`. Do not pipe this command into `tail`; read the exit code.

- [ ] **Step 9: Commit**

```bash
git add inject/src/main/java/be/imgn/inject inject/src/test/java/be/imgn/inject/context/MethodInterceptionTest.java
git commit -m "feat(inject): interception SPI and chain engine"
```

---

### Task 2: Interceptor index in the context

Makes interceptor beans discoverable at runtime. Still no processor work — tests hand-write factories, exactly as `LifecycleCloseTest` does.

**Files:**
- Modify: `inject/src/main/java/be/imgn/inject/context/Factory.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/DefaultContext.java`
- Modify: `inject/src/main/java/be/imgn/inject/context/ChildContext.java`
- Test: `inject/src/test/java/be/imgn/inject/context/InterceptorIndexTest.java`

**Interfaces:**
- Consumes: `Context.interceptor(Class)`, `MethodInterceptor`, `InterceptorResolutionException` from Task 1.
- Produces:
  - `Factory.intercepts()` → `Optional<Class<? extends Annotation>>`, `protected`, defaults to empty
  - `DefaultContext.interceptor(Class)` → the wrapped provider of the `@Intercepts` bean
  - `ChildContext.interceptor(Class)` → delegates to the parent

- [ ] **Step 1: Write the failing test**

Create `inject/src/test/java/be/imgn/inject/context/InterceptorIndexTest.java`:

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Singleton;

class InterceptorIndexTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Timed {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Audited {}

    /** A no-op interceptor that reports which bean instance served the call. */
    record Noop(String name) implements MethodInterceptor {
        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }

    /** Factory for an interceptor bean; scoped when {@code singleton}, prototype otherwise. */
    static final class NoopFactory extends Factory<Noop> {
        private final Class<? extends Annotation> handled;
        private final String name;
        private final boolean singleton;

        NoopFactory(Class<? extends Annotation> handled, String name, boolean singleton) {
            super(Noop.class);
            this.handled = handled;
            this.name = name;
            this.singleton = singleton;
        }

        @Override
        protected Noop createInstance(Context context) {
            return new Noop(name);
        }

        @Override
        protected Optional<Class<? extends Annotation>> intercepts() {
            return Optional.of(handled);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return singleton ? Optional.of(Singleton.class) : Optional.empty();
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(Noop.class, be.imgn.inject.Names.named(name)));
        }
    }

    @Test
    void indexesInterceptorBeansByAnnotationType() {
        DefaultContext context = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", true), new NoopFactory(Audited.class, "audit", true)),
                List.of(new SingletonScope()));

        assertThat(context.interceptor(Timed.class).get()).isEqualTo(new Noop("timing"));
        assertThat(context.interceptor(Audited.class).get()).isEqualTo(new Noop("audit"));
    }

    @Test
    void interceptorProviderRespectsTheInterceptorsOwnScope() {
        DefaultContext singletons =
                new DefaultContext(List.of(new NoopFactory(Timed.class, "timing", true)), List.of(new SingletonScope()));
        DefaultContext prototypes = new DefaultContext(
                List.of(new NoopFactory(Timed.class, "timing", false)), List.of(new SingletonScope()));

        assertThat(singletons.interceptor(Timed.class).get())
                .isSameAs(singletons.interceptor(Timed.class).get());
        assertThat(prototypes.interceptor(Timed.class).get())
                .isNotSameAs(prototypes.interceptor(Timed.class).get());
    }

    @Test
    void twoInterceptorsForOneAnnotationAreADuplicateBinding() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> new DefaultContext(
                        List.of(new NoopFactory(Timed.class, "first", true), new NoopFactory(Timed.class, "second", true)),
                        List.of(new SingletonScope())))
                .withMessageContaining("Timed");
    }

    @Test
    void anUnhandledAnnotationIsRejectedWithAnActionableMessage() {
        DefaultContext context = new DefaultContext(List.of(), List.of());

        assertThatExceptionOfType(InterceptorResolutionException.class)
                .isThrownBy(() -> context.interceptor(Timed.class))
                .withMessageContaining("Timed")
                .withMessageContaining("@Intercepts");
    }

    @Test
    void aChildDelegatesInterceptorLookupToItsParent() {
        DefaultContext parent =
                new DefaultContext(List.of(new NoopFactory(Timed.class, "timing", true)), List.of(new SingletonScope()));
        ChildContext child = new ChildContext(parent, Map.of());

        assertThat(child.interceptor(Timed.class).get()).isEqualTo(new Noop("timing"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -pl inject test -Dtest=InterceptorIndexTest
```

Expected: FAIL — `method intercepts() ... does not override or implement a method from a supertype` and `cannot find symbol: method interceptor(...)` on `DefaultContext`/`ChildContext`.

- [ ] **Step 3: Add the `Factory` override point**

In `inject/src/main/java/be/imgn/inject/context/Factory.java`, add after `isDefault()`:

```java
    /**
     * {@return the intercepting annotation this bean handles, or empty when the bean is not an interceptor}
     *
     * <p>A generated factory overrides this for a bean annotated {@code @Intercepts}.
     */
    protected Optional<Class<? extends Annotation>> intercepts() {
        return Optional.empty();
    }
```

- [ ] **Step 4: Build the index in `DefaultContext`**

In `inject/src/main/java/be/imgn/inject/context/DefaultContext.java`:

Add the import `be.imgn.inject.InterceptorResolutionException`, and a field beside the other final fields:

```java
    private final Map<Class<? extends Annotation>, InterceptorEntry> interceptors;
```

Inside the main constructor, declare the accumulator next to `built`:

```java
        Map<Class<? extends Annotation>, InterceptorEntry> interceptorIndex = new HashMap<>();
```

In the `for (Factory<?> factory : factories)` loop, immediately after `Provider<?> provider = wrap(factory, scopesByAnnotation);`, add:

```java
            Optional<Class<? extends Annotation>> intercepts = factory.intercepts();
            if (intercepts.isPresent()) {
                @SuppressWarnings("unchecked")
                InterceptorEntry entry = new InterceptorEntry(factory, (Provider<MethodInterceptor>) provider);
                InterceptorEntry existing = interceptorIndex.putIfAbsent(intercepts.get(), entry);
                if (existing != null) {
                    throw new DuplicateBindingException("Duplicate interceptor for @"
                            + intercepts.get().getSimpleName() + ": "
                            + existing.factory().type().getName() + " and "
                            + factory.type().getName());
                }
            }
```

Assign the field beside the other `Map.copyOf` assignments at the end of the constructor:

```java
        this.interceptors = Map.copyOf(interceptorIndex);
```

Add the lookup beside `getBeanDefinition`:

```java
    @Override
    public Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(annotationType, "annotationType");
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
        InterceptorEntry entry = interceptors.get(annotationType);
        if (entry == null) {
            throw new InterceptorResolutionException("No interceptor bean for @" + annotationType.getSimpleName()
                    + "; annotate a bean with @Intercepts(" + annotationType.getSimpleName() + ".class).");
        }
        return entry.provider();
    }
```

Add the record beside the other private records at the bottom of the class:

```java
    /** One indexed interceptor: the factory that declared it and its fully wrapped provider. */
    private record InterceptorEntry(Factory<?> factory, Provider<MethodInterceptor> provider) {}
```

- [ ] **Step 5: Delegate from `ChildContext`**

In `inject/src/main/java/be/imgn/inject/context/ChildContext.java`, add after `getBeanDefinitionsByStereotype`:

```java
    @Override
    public Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType) {
        return parent.interceptor(annotationType);
    }
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./mvnw -pl inject test -Dtest=InterceptorIndexTest
```

Expected: PASS — 5 tests.

- [ ] **Step 7: Run the full build**

```bash
./mvnw clean verify
echo "exit=$?"
```

Expected: `BUILD SUCCESS`, `exit=0`.

- [ ] **Step 8: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/context inject/src/test/java/be/imgn/inject/context/InterceptorIndexTest.java
git commit -m "feat(inject): index interceptor beans by annotation type"
```

---

### Task 3: Recognise interceptor beans in the processor

Teaches the processor the two new annotations: an intercepting annotation becomes a captured stereotype, and an `@Intercepts` bean gets a factory that reports `intercepts()` and does not claim the `MethodInterceptor` key.

**Files:**
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/Annotations.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanModel.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/InterceptorBeanTest.java`

**Interfaces:**
- Consumes: `@InterceptingAnnotation`, `@Intercepts`, `MethodInterceptor` (Task 1); `Factory.intercepts()` (Task 2).
- Produces:
  - `BeanModel.interceptsAnnotation()` → `@Nullable ClassName` (new last component)
  - `Annotations.annotationValue(TypeElement, AnnotationMirror, Elements)` → `CodeBlock`, package-visible for Task 5
  - generated factories override `protected Optional<Class<? extends Annotation>> intercepts()`

- [ ] **Step 1: Write the failing test**

Create `inject-processor/src/test/java/be/imgn/inject/processor/InterceptorBeanTest.java`:

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InterceptorBeanTest {

    private static final JavaFileObject TIMED = JavaFileObjects.forSourceString("example.Timed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Timed {
                String value() default "";
            }
            """);

    @Test
    void interceptorBeanFactoryReportsTheHandledAnnotation() {
        JavaFileObject interceptor = JavaFileObjects.forSourceString("example.TimingInterceptor", """
                package example;

                import be.imgn.inject.Intercepts;
                import be.imgn.inject.Singleton;
                import be.imgn.inject.context.MethodInterceptor;
                import be.imgn.inject.context.MethodInvocation;

                @Singleton
                @Intercepts(Timed.class)
                public final class TimingInterceptor implements MethodInterceptor {
                    @Override
                    public Object invoke(MethodInvocation invocation) throws Throwable {
                        return invocation.proceed();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, interceptor);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$TimingInterceptor$$Factory")
                .contentsAsUtf8String();
        source.contains("protected Optional<Class<? extends Annotation>> intercepts()");
        source.contains("return Optional.of(Timed.class)");
    }

    @Test
    void interceptorBeanDoesNotClaimTheMethodInterceptorKey() {
        JavaFileObject interceptor = JavaFileObjects.forSourceString("example.TimingInterceptor", """
                package example;

                import be.imgn.inject.Intercepts;
                import be.imgn.inject.context.MethodInterceptor;
                import be.imgn.inject.context.MethodInvocation;

                @Intercepts(Timed.class)
                public final class TimingInterceptor implements MethodInterceptor {
                    @Override
                    public Object invoke(MethodInvocation invocation) throws Throwable {
                        return invocation.proceed();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, interceptor);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$TimingInterceptor$$Factory")
                .contentsAsUtf8String()
                .doesNotContain("MethodInterceptor.class");
    }

    @Test
    void interceptsOnANonInterceptorIsRejected() {
        JavaFileObject notAnInterceptor = JavaFileObjects.forSourceString("example.Bogus", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Intercepts;

                @Intercepts(Timed.class)
                public final class Bogus {
                    @Inject
                    public Bogus() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, notAnInterceptor);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("does not implement MethodInterceptor");
    }

    @Test
    void anInterceptingAnnotationIsCapturedAsAStereotype() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                @Timed("service")
                public class Service {
                    @Inject
                    public Service() {}

                    public void run() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contentsAsUtf8String();
        source.contains("AnnotationValue.of(Timed.class");
        source.contains("InterceptingAnnotation.class");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -pl inject-processor test -Dtest=InterceptorBeanTest
```

Expected: FAIL — `intercepts()` missing from the generated factory, `hadErrorContaining("does not implement MethodInterceptor")` unmatched, and no `InterceptingAnnotation.class` in the metadata.

- [ ] **Step 3: Capture the new stereotype**

In `inject-processor/src/main/java/be/imgn/inject/processor/Annotations.java`:

Add the constant beside the other meta-annotation constants:

```java
    private static final ClassName INTERCEPTING_ANNOTATION = ClassName.get("be.imgn.inject", "InterceptingAnnotation");
```

Add the import `be.imgn.inject.InterceptingAnnotation`, extend `isFrameworkRelated`:

```java
    private static boolean isFrameworkRelated(TypeElement annotationType) {
        return annotationType.getAnnotation(QualifyingAnnotation.class) != null
                || annotationType.getAnnotation(ScopingAnnotation.class) != null
                || annotationType.getAnnotation(InjectingAnnotation.class) != null
                || annotationType.getAnnotation(ConditioningAnnotation.class) != null
                || annotationType.getAnnotation(InterceptingAnnotation.class) != null;
    }
```

and extend `stereotypes` with a matching branch before the `return`:

```java
        if (annotationType.getAnnotation(InterceptingAnnotation.class) != null) {
            present.add(CodeBlock.of("$T.class", INTERCEPTING_ANNOTATION));
        }
```

Finally widen `annotationValue` from `private static` to package-visible so Task 5's generator can reuse it:

```java
    /** {@return an {@code AnnotationValue.of(...)} expression describing the annotation} */
    static CodeBlock annotationValue(TypeElement annotationType, AnnotationMirror mirror, Elements utils) {
```

- [ ] **Step 4: Read `@Intercepts` and exclude `MethodInterceptor` from exposure**

In `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java`:

Add `MethodInterceptor` to the exposure exclusions (import `be.imgn.inject.context.MethodInterceptor`):

```java
            Factory.class.getName(),
            MethodInterceptor.class.getName());
```

Add this method (import `be.imgn.inject.Intercepts`, `javax.lang.model.element.AnnotationMirror`, `javax.lang.model.element.ExecutableElement`, `javax.lang.model.type.DeclaredType`, `java.util.Map` as needed — several are already imported):

```java
    /**
     * {@return the annotation named by {@code @Intercepts} on {@code bean}, or {@code null} when it carries none}
     *
     * <p>Read through the mirror: {@code Intercepts.value()} is a {@code Class}, so reading it reflectively during
     * annotation processing throws {@code MirroredTypeException}.
     */
    @Nullable ClassName interceptsAnnotation(TypeElement bean) {
        for (AnnotationMirror mirror : bean.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
            if (!annotationType.getQualifiedName().contentEquals(Intercepts.class.getCanonicalName())) {
                continue;
            }
            if (!implementsMethodInterceptor(bean)) {
                diagnostics.error(
                        bean.getQualifiedName() + " is annotated @Intercepts but does not implement MethodInterceptor.",
                        bean);
                return null;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends javax.lang.model.element.AnnotationValue> entry :
                    mirror.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")
                        && entry.getValue().getValue() instanceof DeclaredType declared
                        && declared.asElement() instanceof TypeElement handled) {
                    return ClassName.get(handled);
                }
            }
        }
        return null;
    }

    /** {@return whether {@code bean} implements {@code MethodInterceptor}} */
    private boolean implementsMethodInterceptor(TypeElement bean) {
        TypeElement declared = environment.getElementUtils().getTypeElement(MethodInterceptor.class.getCanonicalName());
        return declared != null
                && environment.getTypeUtils().isAssignable(
                        environment.getTypeUtils().erasure(bean.asType()),
                        environment.getTypeUtils().erasure(declared.asType()));
    }
```

Checkstyle forbids fully-qualified names in code, so import `javax.lang.model.element.AnnotationValue` under an alias is impossible in Java — instead extract the loop body into a helper that takes `Map<? extends ExecutableElement, ?>`:

```java
    private static @Nullable ClassName interceptsValue(AnnotationMirror mirror) {
        for (ExecutableElement member : mirror.getElementValues().keySet()) {
            if (!member.getSimpleName().contentEquals("value")) {
                continue;
            }
            Object value = mirror.getElementValues().get(member).getValue();
            if (value instanceof DeclaredType declared && declared.asElement() instanceof TypeElement handled) {
                return ClassName.get(handled);
            }
        }
        return null;
    }
```

and call `return interceptsValue(mirror);` from `interceptsAnnotation` in place of the inline loop. `be.imgn.inject.context.AnnotationValue` is already imported in this file for other reasons; the `javax.lang.model` one never appears.

- [ ] **Step 5: Carry the annotation on the model and generate the override**

In `inject-processor/src/main/java/be/imgn/inject/processor/BeanModel.java`, append a component and its Javadoc line:

```java
 * @param interceptsAnnotation the annotation this bean intercepts (from {@code @Intercepts}), or {@code null}
 */
record BeanModel(
        ...
        @Nullable String preDestroy,
        @Nullable ClassName interceptsAnnotation) {}
```

In `ModelFactory.buildModel`, pass `interceptsAnnotation(bean)` as the new last argument; in `ModelFactory.defaultModel`, pass `interceptsAnnotation(type)`.

In `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java`, add to `generate` after the `preDestroy` block:

```java
        if (model.interceptsAnnotation() != null) {
            type.addMethod(intercepts(model.interceptsAnnotation()));
        }
```

and the emitter beside `scope`:

```java
    private static MethodSpec intercepts(ClassName annotation) {
        TypeName returnType = ParameterizedTypeName.get(
                OPTIONAL, ParameterizedTypeName.get(CLASS, WildcardTypeName.subtypeOf(ANNOTATION)));
        return MethodSpec.methodBuilder("intercepts")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.class)", OPTIONAL, annotation)
                .build();
    }
```

- [ ] **Step 6: Gather `@Intercepts` beans that have no `@Inject` member**

In `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java`, add the import `be.imgn.inject.Intercepts` and this loop right after the `@Inject` loop that seeds `beanTypes`:

```java
        for (Element element : roundEnv.getElementsAnnotatedWith(Intercepts.class)) {
            if (element instanceof TypeElement interceptor) {
                beanTypes.add(interceptor);
            }
        }
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
./mvnw -pl inject-processor test -Dtest=InterceptorBeanTest
```

Expected: PASS — 4 tests.

- [ ] **Step 8: Run the full build**

```bash
./mvnw clean verify
echo "exit=$?"
```

Expected: `BUILD SUCCESS`, `exit=0`. Existing processor tests must still pass — `BeanModel` gained a component, so every construction site had to be updated.

- [ ] **Step 9: Commit**

```bash
git add inject-processor/src/main/java/be/imgn/inject/processor inject-processor/src/test/java/be/imgn/inject/processor/InterceptorBeanTest.java
git commit -m "feat(processor): recognise intercepting annotations and @Intercepts beans"
```

---

### Task 4: Gather intercepted methods and reject bad placement

Builds the per-bean interception model and every compile-time error. Nothing is generated yet: the deliverable is that legal placement is understood and illegal placement fails the build with an actionable message.

**Files:**
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/ProxyParameter.java`
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/InterceptedMethodModel.java`
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/InterceptionModel.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GeneratedNames.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanModel.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/GatherStage.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/InterceptionValidationTest.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/GeneratedNamesTest.java` (add one case)

**Interfaces:**
- Consumes: `Annotations.metadata`, `Annotations.annotationValue`, `ModelFactory.interceptsAnnotation` (Task 3).
- Produces:
  - `record ProxyParameter(String name, TypeName type)`
  - `record InterceptedMethodModel(String name, String fieldName, TypeName returnType, List<ProxyParameter> parameters, List<TypeName> thrownTypes, boolean declaresThrowable, boolean varargs, boolean uncheckedCast, List<TypeVariableName> typeVariables, List<CodeBlock> bindings)`
  - `record InterceptionModel(String proxySimpleName, List<ProxyParameter> constructorParameters, List<InterceptedMethodModel> methods)`
  - `BeanModel.interception()` → `@Nullable InterceptionModel`
  - `GeneratedNames.interceptedName(List<String>)` / `interceptedName(String)`

- [ ] **Step 1: Write the failing tests**

Create `inject-processor/src/test/java/be/imgn/inject/processor/InterceptionValidationTest.java`:

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InterceptionValidationTest {

    private static final JavaFileObject TIMED = JavaFileObjects.forSourceString("example.Timed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Timed {
                String value() default "";
            }
            """);

    private static Compilation compile(String name, String source) {
        return javac()
                .withProcessors(new InjectProcessor())
                .compile(TIMED, JavaFileObjects.forSourceString(name, source));
    }

    @Test
    void finalClassCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public final class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Remove final");
    }

    @Test
    void recordCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public record Bean(String name) {
                    @Inject
                    public Bean {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("record");
    }

    @Test
    void enumCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                @Timed
                public enum Bean {
                    ONE;

                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("enum");
    }

    @Test
    void sealedClassCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public sealed class Bean permits Bean.Only {
                    @Inject
                    public Bean() {}

                    @Timed
                    public void run() {}

                    public static final class Only extends Bean {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("sealed");
    }

    @Test
    void genericClassCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean<T> {
                    @Inject
                    public Bean() {}

                    @Timed
                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("generic class");
    }

    @Test
    void finalMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public final void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("final");
    }

    @Test
    void staticMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public static void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("static");
    }

    @Test
    void privateMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    private void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("private");
    }

    @Test
    void frameworkCallbackMethodsCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    @PostConstruct
                    public void start() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("framework callbacks");
    }

    @Test
    void providesMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Timed
                    @Provides
                    public static String value() {
                        return "v";
                    }
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Provides");
    }

    @Test
    void assistedRecipeCannotBeIntercepted() {
        Compilation compilation = compile("example.Ticket", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                @Timed
                public class Ticket {
                    @Inject
                    public Ticket(@Assisted String label) {}

                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void anInterceptorCannotItselfBeIntercepted() {
        Compilation compilation = compile("example.Recursive", """
                package example;

                import be.imgn.inject.Intercepts;
                import be.imgn.inject.context.MethodInterceptor;
                import be.imgn.inject.context.MethodInvocation;

                @Timed
                @Intercepts(Timed.class)
                public class Recursive implements MethodInterceptor {
                    @Override
                    public Object invoke(MethodInvocation invocation) throws Throwable {
                        return invocation.proceed();
                    }
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot itself be intercepted");
    }

    @Test
    void classLevelAnnotationWithNoEligibleMethodIsRejected() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Bean {
                    @Inject
                    public Bean() {}

                    public final String name() {
                        return "bean";
                    }
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("no interceptable method");
    }

    @Test
    void classLevelAnnotationSkipsIneligibleMethodsWhenOneIsEligible() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Bean {
                    @Inject
                    public Bean() {}

                    public final String name() {
                        return "bean";
                    }

                    public void run() {}
                }
                """);

        assertThat(compilation).succeeded();
    }
}
```

Add one case to `inject-processor/src/test/java/be/imgn/inject/processor/GeneratedNamesTest.java`:

```java
    @Test
    void buildsInterceptedProxyNames() {
        assertThat(GeneratedNames.interceptedName("Bean")).isEqualTo("$Bean$$Intercepted");
        assertThat(GeneratedNames.interceptedName(List.of("Outer", "Inner"))).isEqualTo("$Outer$Inner$$Intercepted");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./mvnw -pl inject-processor test -Dtest='InterceptionValidationTest+GeneratedNamesTest'
```

Expected: FAIL — every validation test reports `succeeded()` where `failed()` was expected, and `GeneratedNamesTest` fails to compile (`cannot find symbol: method interceptedName`).

- [ ] **Step 3: Add the naming rule**

In `inject-processor/src/main/java/be/imgn/inject/processor/GeneratedNames.java`:

```java
    /**
     * {@return the intercepted-proxy name for a bean whose enclosing-class chain (outermost first) is
     * {@code enclosingChain}}
     *
     * @param enclosingChain the simple names from the outermost enclosing class down to the bean
     */
    static String interceptedName(List<String> enclosingChain) {
        return "$" + String.join("$", enclosingChain) + "$$Intercepted";
    }

    /**
     * {@return the intercepted-proxy name for a top-level bean}
     *
     * @param simpleName the bean's simple name
     */
    static String interceptedName(String simpleName) {
        return interceptedName(List.of(simpleName));
    }
```

- [ ] **Step 4: Add the model records**

`inject-processor/src/main/java/be/imgn/inject/processor/ProxyParameter.java`:

```java
package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

/**
 * One parameter of a generated proxy signature.
 *
 * @param name the parameter name
 * @param type the declared type, verbatim from the source
 */
record ProxyParameter(String name, TypeName type) {}
```

`inject-processor/src/main/java/be/imgn/inject/processor/InterceptedMethodModel.java`:

```java
package be.imgn.inject.processor;

import java.util.List;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;

/**
 * One intercepted method to override in the generated proxy.
 *
 * @param name the method name
 * @param fieldName the proxy's chain field name, unique across overloads ({@code name + "$" + index})
 * @param returnType the declared return type; {@code TypeName.VOID} for a void method
 * @param parameters the declared parameters, in order
 * @param thrownTypes the declared checked exception types, most general first, unchecked ones removed
 * @param declaresThrowable whether the method declares {@code Throwable}, so nothing needs wrapping
 * @param varargs whether the last parameter is a varargs parameter
 * @param uncheckedCast whether casting the chain's result needs {@code @SuppressWarnings("unchecked")}
 * @param typeVariables the method's own type variables, in order
 * @param bindings {@code AnnotationValue.of(...)} expressions for the intercepting annotations, outermost first
 */
record InterceptedMethodModel(
        String name,
        String fieldName,
        TypeName returnType,
        List<ProxyParameter> parameters,
        List<TypeName> thrownTypes,
        boolean declaresThrowable,
        boolean varargs,
        boolean uncheckedCast,
        List<TypeVariableName> typeVariables,
        List<CodeBlock> bindings) {}
```

`inject-processor/src/main/java/be/imgn/inject/processor/InterceptionModel.java`:

```java
package be.imgn.inject.processor;

import java.util.List;

/**
 * The data needed to generate one bean's intercepted proxy.
 *
 * @param proxySimpleName the generated proxy's simple name
 * @param constructorParameters the bean's {@code @Inject} constructor parameters, mirrored on the proxy constructor
 * @param methods the intercepted methods, in declaration order
 */
record InterceptionModel(
        String proxySimpleName, List<ProxyParameter> constructorParameters, List<InterceptedMethodModel> methods) {}
```

In `BeanModel.java` append the component with its Javadoc line:

```java
 * @param interception the intercepted-proxy model, or {@code null} when the bean has no intercepted method
 */
record BeanModel(
        ...
        @Nullable ClassName interceptsAnnotation,
        @Nullable InterceptionModel interception) {}
```

- [ ] **Step 5: Build the interception model and validate placement**

In `inject-processor/src/main/java/be/imgn/inject/processor/ModelFactory.java` add this block (imports needed: `be.imgn.inject.InterceptingAnnotation`, `be.imgn.inject.PostConstruct`, `be.imgn.inject.PreDestroy`, `com.palantir.javapoet.TypeVariableName`, `javax.lang.model.element.NestingKind` is not needed, `javax.lang.model.type.TypeMirror`, `javax.lang.model.type.TypeVariable` is not needed):

```java
    /**
     * {@return {@code bean}'s interception model, or {@code null} when nothing is intercepted}
     *
     * <p>Reports every placement a generated subclass cannot honour. A class-level annotation sweeps declared methods
     * that are not {@code private}, {@code static}, or {@code final}, skipping {@code Object} methods and framework
     * callbacks; a method-level annotation applies to that method alone and replaces a class-level declaration of the
     * same annotation type in place, so the class-level position (outermost) is preserved.
     *
     * @param bean the bean type
     * @param assisted whether the bean is an assisted recipe
     */
    @Nullable InterceptionModel interception(TypeElement bean, boolean assisted) {
        Elements elements = environment.getElementUtils();
        List<AnnotationMirror> classLevel = interceptingAnnotations(bean);
        List<ExecutableElement> annotatedMethods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && !interceptingAnnotations(enclosed).isEmpty()) {
                annotatedMethods.add((ExecutableElement) enclosed);
            }
        }
        if (classLevel.isEmpty() && annotatedMethods.isEmpty()) {
            return null;
        }
        if (assisted) {
            diagnostics.error(
                    "An assisted recipe cannot be intercepted: assisted instances are built per call, outside the"
                            + " registry.",
                    bean);
            return null;
        }
        if (interceptsAnnotation(bean) != null) {
            diagnostics.error(bean.getQualifiedName() + " is an interceptor and cannot itself be intercepted.", bean);
            return null;
        }
        if (!validateInterceptableShape(bean)) {
            return null;
        }
        List<InterceptedMethodModel> methods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            List<AnnotationMirror> own = interceptingAnnotations(method);
            boolean eligible = isInterceptable(method);
            if (!own.isEmpty() && !eligible) {
                diagnostics.error(ineligibleReason(method), method);
                return null;
            }
            if (!eligible || (own.isEmpty() && classLevel.isEmpty())) {
                continue;
            }
            Map<String, AnnotationMirror> merged = new LinkedHashMap<>();
            for (AnnotationMirror mirror : classLevel) {
                merged.put(mirror.getAnnotationType().toString(), mirror);
            }
            for (AnnotationMirror mirror : own) {
                merged.put(mirror.getAnnotationType().toString(), mirror);
            }
            methods.add(interceptedMethod(method, methods.size(), List.copyOf(merged.values()), elements));
        }
        if (methods.isEmpty()) {
            diagnostics.error(
                    bean.getQualifiedName() + " carries an intercepting annotation but declares no interceptable"
                            + " method.",
                    bean);
            return null;
        }
        return new InterceptionModel(
                GeneratedNames.interceptedName(enclosingChain(bean)),
                injectConstructor(bean)
                        .map(ModelFactory::proxyParameters)
                        .orElse(List.of()),
                List.copyOf(methods));
    }

    /** {@return the intercepting annotations declared on {@code element}, in source order} */
    private static List<AnnotationMirror> interceptingAnnotations(Element element) {
        List<AnnotationMirror> result = new ArrayList<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getAnnotation(InterceptingAnnotation.class) != null) {
                result.add(mirror);
            }
        }
        return result;
    }

    /** {@return {@code true} if {@code bean} can be subclassed by a generated proxy; reports why not otherwise} */
    private boolean validateInterceptableShape(TypeElement bean) {
        if (bean.getKind() == ElementKind.RECORD) {
            diagnostics.error(
                    "A record cannot be intercepted: records are implicitly final and their accessors are part of"
                            + " their identity. Use a class.",
                    bean);
            return false;
        }
        if (bean.getKind() == ElementKind.ENUM) {
            diagnostics.error(
                    "An enum cannot be intercepted: enum constants are created by the JVM, not by a factory.", bean);
            return false;
        }
        if (bean.getModifiers().contains(Modifier.SEALED)) {
            diagnostics.error(
                    bean.getQualifiedName() + " cannot be intercepted: a sealed class permits only its listed"
                            + " subclasses. Use a non-sealed class.",
                    bean);
            return false;
        }
        if (bean.getModifiers().contains(Modifier.FINAL)) {
            diagnostics.error(
                    bean.getQualifiedName() + " cannot be intercepted: the generated proxy must subclass it. Remove"
                            + " final.",
                    bean);
            return false;
        }
        if (!bean.getTypeParameters().isEmpty()) {
            diagnostics.error(
                    bean.getQualifiedName() + " cannot be intercepted: a generic class is bound under its raw type.",
                    bean);
            return false;
        }
        return true;
    }

    /** {@return whether a class-level sweep may override {@code method}} */
    private static boolean isInterceptable(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        return !modifiers.contains(Modifier.PRIVATE)
                && !modifiers.contains(Modifier.STATIC)
                && !modifiers.contains(Modifier.FINAL)
                && method.getAnnotation(Inject.class) == null
                && method.getAnnotation(PostConstruct.class) == null
                && method.getAnnotation(PreDestroy.class) == null
                && method.getAnnotation(Provides.class) == null
                && !isObjectMethod(method);
    }

    /** {@return why {@code method} cannot be intercepted} */
    private static String ineligibleReason(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        String name = method.getSimpleName().toString();
        if (method.getAnnotation(Provides.class) != null) {
            return "@Provides " + name + "() cannot be intercepted: the container does not construct its result.";
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            return name + "() cannot be intercepted: it is private.";
        }
        if (modifiers.contains(Modifier.STATIC)) {
            return name + "() cannot be intercepted: it is static.";
        }
        if (modifiers.contains(Modifier.FINAL)) {
            return name + "() cannot be intercepted: it is final.";
        }
        return name + "() cannot be intercepted: framework callbacks are invoked by the container.";
    }

    /** {@return whether {@code method} overrides one of {@code Object}'s methods} */
    private static boolean isObjectMethod(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        int arity = method.getParameters().size();
        return (name.equals("toString") && arity == 0)
                || (name.equals("hashCode") && arity == 0)
                || (name.equals("equals") && arity == 1);
    }

    /** {@return {@code executable}'s parameters as proxy signature parameters} */
    private static List<ProxyParameter> proxyParameters(ExecutableElement executable) {
        List<ProxyParameter> result = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            result.add(new ProxyParameter(
                    parameter.getSimpleName().toString(), TypeName.get(parameter.asType())));
        }
        return List.copyOf(result);
    }

    /** {@return the model for one intercepted method} */
    private InterceptedMethodModel interceptedMethod(
            ExecutableElement method, int index, List<AnnotationMirror> bindings, Elements elements) {
        List<CodeBlock> rendered = new ArrayList<>();
        for (AnnotationMirror mirror : bindings) {
            rendered.add(Annotations.annotationValue(
                    (TypeElement) mirror.getAnnotationType().asElement(), mirror, elements));
        }
        TypeName returnType = TypeName.get(method.getReturnType());
        List<TypeVariableName> typeVariables = method.getTypeParameters().stream()
                .map(TypeVariableName::get)
                .toList();
        return new InterceptedMethodModel(
                method.getSimpleName().toString(),
                method.getSimpleName() + "$" + index,
                returnType,
                proxyParameters(method),
                thrownTypes(method),
                declaresThrowable(method),
                method.isVarArgs(),
                returnType instanceof ParameterizedTypeName || returnType instanceof TypeVariableName,
                typeVariables,
                List.copyOf(rendered));
    }

    /** {@return whether {@code method} declares {@code Throwable} itself} */
    private boolean declaresThrowable(ExecutableElement method) {
        for (TypeMirror thrown : method.getThrownTypes()) {
            if (thrown.toString().equals(Throwable.class.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return {@code method}'s declared checked exception types, most general first, with unchecked types and any
     * subtype of an already-kept type removed}
     *
     * <p>Both filters exist to keep the generated catch clauses reachable: unchecked types are already covered by the
     * proxy's {@code catch (RuntimeException | Error)}, and a subtype after its supertype is a compile error.
     */
    private List<TypeName> thrownTypes(ExecutableElement method) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        TypeMirror runtimeException =
                elements.getTypeElement(RuntimeException.class.getCanonicalName()).asType();
        TypeMirror error = elements.getTypeElement(Error.class.getCanonicalName()).asType();
        TypeMirror throwable = elements.getTypeElement(Throwable.class.getCanonicalName()).asType();
        List<TypeMirror> kept = new ArrayList<>();
        List<TypeMirror> candidates = new ArrayList<>(method.getThrownTypes());
        candidates.sort((left, right) -> types.isSubtype(left, right) ? 1 : types.isSubtype(right, left) ? -1 : 0);
        for (TypeMirror thrown : candidates) {
            if (types.isSubtype(thrown, runtimeException)
                    || types.isSubtype(thrown, error)
                    || types.isSameType(thrown, throwable)) {
                continue;
            }
            boolean covered = kept.stream().anyMatch(existing -> types.isSubtype(thrown, existing));
            if (!covered) {
                kept.add(thrown);
            }
        }
        return kept.stream().map(TypeName::get).toList();
    }
```

Wire it into `buildModel`: compute `InterceptionModel interception = interception(bean, false);` before the `return new BeanModel(...)` and pass it as the new last argument. In `defaultModel`, pass `interception(type, false)` as the last argument — a dependency-only bean can still carry a class-level annotation.

- [ ] **Step 6: Validate the placements the bean model never sees**

Two placements never reach `buildModel`: an assisted recipe (skipped in `GatherStage`) and a `@Provides` method.

In `ModelFactory.validateAssistedRecipe`, add:

```java
        if (!interceptingAnnotations(annotated).isEmpty()) {
            diagnostics.error(
                    "An assisted recipe cannot be intercepted: assisted instances are built per call, outside the"
                            + " registry.",
                    annotated);
        }
```

In `ModelFactory.providesModel`, right after the framework-return-type check, add:

```java
        if (!interceptingAnnotations(method).isEmpty()) {
            diagnostics.error(
                    "@Provides " + name + "() cannot be intercepted: the container does not construct its result.",
                    method);
        }
```

Move the `String name = method.getSimpleName().toString();` line above this block if it is currently declared after it.

- [ ] **Step 7: Gather beans that are intercepted but have no `@Inject` member**

In `GatherStage.gather`, extend the `annotations` loop that already handles `@InjectingAnnotation` so intercepting annotations also seed bean types (add the import `be.imgn.inject.InterceptingAnnotation`):

```java
        for (TypeElement annotation : annotations) {
            if (annotation.getAnnotation(InterceptingAnnotation.class) == null) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof TypeElement bean) {
                    beanTypes.add(bean);
                } else if (element.getEnclosingElement() instanceof TypeElement bean) {
                    beanTypes.add(bean);
                }
            }
        }
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
./mvnw -pl inject-processor test -Dtest='InterceptionValidationTest+GeneratedNamesTest'
```

Expected: PASS — 14 validation tests plus the existing naming tests.

- [ ] **Step 9: Run the full build**

```bash
./mvnw clean verify
echo "exit=$?"
```

Expected: `BUILD SUCCESS`, `exit=0`.

- [ ] **Step 10: Commit**

```bash
git add inject-processor/src/main/java/be/imgn/inject/processor inject-processor/src/test/java/be/imgn/inject/processor
git commit -m "feat(processor): model intercepted methods and reject unsupported placement"
```

---

### Task 5: Generate the intercepted proxy

The payoff task: emit `$Bean$$Intercepted`, point the factory at it, and prove the whole thing runs end to end through a real `Injector`.

**Files:**
- Create: `inject-processor/src/main/java/be/imgn/inject/processor/InterceptedProxyGenerator.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java`
- Modify: `inject-processor/src/main/java/be/imgn/inject/processor/InjectProcessor.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/InterceptedProxyGenerationTest.java`
- Test: `inject-processor/src/test/java/be/imgn/inject/processor/InterceptionEndToEndTest.java`

**Interfaces:**
- Consumes: `InterceptionModel`, `InterceptedMethodModel`, `ProxyParameter` (Task 4); `InterceptedMethod`, `MethodInterception`, `Joinpoint`, `InterceptionException` (Task 1); `Context.interceptor` index (Task 2).
- Produces: `InterceptedProxyGenerator.generate(BeanModel)` → `JavaFile`; generated factories construct `new $Bean$$Intercepted(context, …)`.

- [ ] **Step 1: Write the failing generation test**

Create `inject-processor/src/test/java/be/imgn/inject/processor/InterceptedProxyGenerationTest.java`:

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InterceptedProxyGenerationTest {

    private static final JavaFileObject TIMED = JavaFileObjects.forSourceString("example.Timed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Timed {
                String value() default "";
            }
            """);

    private static final JavaFileObject AUDITED = JavaFileObjects.forSourceString("example.Audited", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Audited {}
            """);

    @Test
    void generatesAProxyThatChainsToSuper() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Orders", """
                package example;

                import be.imgn.inject.Inject;

                public class Orders {
                    @Inject
                    public Orders(String region) {}

                    @Timed("place")
                    public String place(String cart) {
                        return cart;
                    }
                }
                """);
        JavaFileObject config = JavaFileObjects.forSourceString("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Provides
                    public static String region() {
                        return "eu";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean, config);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Orders$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("final class $Orders$$Intercepted extends Orders");
        proxy.contains("InterceptedMethod.of(Orders.class, \"place\"");
        proxy.contains("AnnotationValue.of(Timed.class");
        proxy.contains("super.place((String) arguments[0])");
        proxy.contains("$Orders$$Intercepted(Context context, String region)");
        proxy.contains("super(region)");
        proxy.contains("bind(context)");
        proxy.contains("catch (RuntimeException | Error");
        proxy.contains("InterceptionException");

        assertThat(compilation)
                .generatedSourceFile("example.$Orders$$Factory")
                .contentsAsUtf8String()
                .contains("new $Orders$$Intercepted(context, getInstanceForConstructor(0, context))");
    }

    @Test
    void classLevelAnnotationWrapsMethodLevelOnesInSourceOrder() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Orders", """
                package example;

                import be.imgn.inject.Inject;

                @Audited
                public class Orders {
                    @Inject
                    public Orders() {}

                    @Timed
                    public void place() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, AUDITED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Orders$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("List.of(AnnotationValue.of(Audited.class");
        proxy.contains("AnnotationValue.of(Timed.class");
        assertThat(proxy).isNotNull();
    }

    @Test
    void handlesVoidPrimitiveVarargsAndCheckedExceptions() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Mixed", """
                package example;

                import be.imgn.inject.Inject;
                import java.io.IOException;

                @Timed
                public class Mixed {
                    @Inject
                    public Mixed() {}

                    public void nothing() {}

                    public int count(int base) {
                        return base;
                    }

                    public String join(String... parts) {
                        return String.join("", parts);
                    }

                    public String read() throws IOException {
                        return "read";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject proxy = assertThat(compilation)
                .generatedSourceFile("example.$Mixed$$Intercepted")
                .contentsAsUtf8String();
        proxy.contains("super.nothing()");
        proxy.contains("return (Integer)");
        proxy.contains("the primitive return type int");
        proxy.contains("String join(String... parts)");
        proxy.contains("catch (IOException");
    }
}
```

- [ ] **Step 2: Write the failing end-to-end test**

Create `inject-processor/src/test/java/be/imgn/inject/processor/InterceptionEndToEndTest.java`. It mirrors `EndToEndTest`'s classloader approach; copy that file's `writeClassOutput` helper verbatim into this class:

```java
package be.imgn.inject.processor;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import be.imgn.inject.Injector;

class InterceptionEndToEndTest {

    private static final JavaFileObject TIMED = JavaFileObjects.forSourceString("example.Timed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Timed {
                String value() default "";
            }
            """);

    private static final JavaFileObject LOG = JavaFileObjects.forSourceString("example.CallLog", """
            package example;

            import be.imgn.inject.Inject;
            import be.imgn.inject.Singleton;
            import java.util.ArrayList;
            import java.util.List;

            @Singleton
            public class CallLog {
                private final List<String> entries = new ArrayList<>();

                @Inject
                public CallLog() {}

                public void add(String entry) {
                    entries.add(entry);
                }

                public List<String> entries() {
                    return entries;
                }
            }
            """);

    private static final JavaFileObject INTERCEPTOR = JavaFileObjects.forSourceString("example.TimingInterceptor", """
            package example;

            import be.imgn.inject.Inject;
            import be.imgn.inject.Intercepts;
            import be.imgn.inject.Singleton;
            import be.imgn.inject.context.MethodInterceptor;
            import be.imgn.inject.context.MethodInvocation;

            @Singleton
            @Intercepts(Timed.class)
            public class TimingInterceptor implements MethodInterceptor {
                private final CallLog log;

                @Inject
                public TimingInterceptor(CallLog log) {
                    this.log = log;
                }

                @Override
                public Object invoke(MethodInvocation invocation) throws Throwable {
                    log.add("enter:" + invocation.methodName() + ":"
                            + invocation.annotation().stringValue("value").orElse(""));
                    Object result = invocation.proceed();
                    log.add("exit:" + invocation.methodName());
                    return result;
                }
            }
            """);

    @Test
    void interceptsThroughARealInjector(@TempDir Path outputDir) throws Exception {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Orders", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Orders {
                    @Inject
                    public Orders() {}

                    @Timed("place")
                    public String place(String cart) {
                        return "placed:" + cart + ":" + self();
                    }

                    @Timed
                    public String self() {
                        return "self";
                    }
                }
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(TIMED, LOG, INTERCEPTOR, bean);
        CompilationSubject.assertThat(compilation).succeeded();
        writeClassOutput(compilation, outputDir);

        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {outputDir.toUri().toURL()}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            try (Injector injector = Injector.create()) {
                Class<?> ordersType = loader.loadClass("example.Orders");
                Object orders = injector.getInstance(ordersType);
                Method place = ordersType.getMethod("place", String.class);

                Object result = place.invoke(orders, "cart-1");

                assertThat(result).isEqualTo("placed:cart-1:self");
                Class<?> logType = loader.loadClass("example.CallLog");
                Object log = injector.getInstance(logType);
                Object entries = logType.getMethod("entries").invoke(log);
                assertThat((List<?>) entries)
                        .containsExactly("enter:place:place", "enter:self:", "exit:self", "exit:place");
            }
        }
    }

    /** Copies every class file the compilation produced into {@code outputDir}, preserving package directories. */
    private static void writeClassOutput(Compilation compilation, Path outputDir) throws Exception {
        for (JavaFileObject generated : compilation.generatedFiles()) {
            if (generated.getKind() != JavaFileObject.Kind.CLASS
                    && !generated.getName().contains("META-INF")) {
                continue;
            }
            String name = generated.getName().replaceFirst("^/?CLASS_OUTPUT/", "");
            Path target = outputDir.resolve(name);
            Files.createDirectories(target.getParent());
            try (InputStream input = generated.openInputStream()) {
                Files.copy(input, target);
            }
        }
    }
}
```

Note: the `self()` call inside `place` proves self-invocation is intercepted — the log shows the inner pair nested inside the outer pair.

If `EndToEndTest.writeClassOutput` differs from the sketch above, copy that method's body exactly rather than this one; it is the version already proven to work against this compile-testing version.

- [ ] **Step 3: Run both tests to verify they fail**

```bash
./mvnw -pl inject-processor test -Dtest='InterceptedProxyGenerationTest+InterceptionEndToEndTest'
```

Expected: FAIL — `generatedSourceFile("example.$Orders$$Intercepted")` is missing, since nothing emits the proxy yet.

- [ ] **Step 4: Write the proxy generator**

Create `inject-processor/src/main/java/be/imgn/inject/processor/InterceptedProxyGenerator.java`:

```java
package be.imgn.inject.processor;

import java.util.List;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/**
 * Emits the {@code $Bean$$Intercepted} subclass: one static call-site descriptor and one chain field per intercepted
 * method, plus an override that dispatches through the chain to {@code super}.
 */
final class InterceptedProxyGenerator {

    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName INTERCEPTED_METHOD = ClassName.get("be.imgn.inject.context", "InterceptedMethod");
    private static final ClassName METHOD_INTERCEPTION = ClassName.get("be.imgn.inject.context", "MethodInterception");
    private static final ClassName JOINPOINT = ClassName.get("be.imgn.inject.context", "Joinpoint");
    private static final ClassName INTERCEPTION_EXCEPTION = ClassName.get("be.imgn.inject", "InterceptionException");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OBJECT = ClassName.get("java.lang", "Object");
    private static final ClassName THROWABLE = ClassName.get("java.lang", "Throwable");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    private static final ClassName ERROR = ClassName.get("java.lang", "Error");

    private InterceptedProxyGenerator() {}

    /**
     * {@return the proxy source file for {@code model}}
     *
     * @param model the bean model; its {@code interception()} must not be {@code null}
     */
    static JavaFile generate(BeanModel model) {
        InterceptionModel interception = model.interception();
        if (interception == null) {
            throw new IllegalArgumentException("No interception model for " + model.beanType());
        }
        TypeSpec.Builder type = TypeSpec.classBuilder(interception.proxySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(model.beanType());
        for (InterceptedMethodModel method : interception.methods()) {
            type.addField(descriptor(model, method));
            type.addField(chainField(method));
            type.addField(joinpointField(method));
            type.addMethod(override(model, method));
        }
        type.addMethod(constructor(model, interception));
        return JavaFile.builder(model.packageName(), type.build()).build();
    }

    /** {@return the {@code private static final InterceptedMethod} describing one call site} */
    private static FieldSpec descriptor(BeanModel model, InterceptedMethodModel method) {
        CodeBlock parameterTypes = method.parameters().stream()
                .map(parameter -> CodeBlock.of("$T.class", rawType(parameter.type())))
                .collect(CodeBlock.joining(", "));
        CodeBlock bindings = method.bindings().stream().collect(CodeBlock.joining(", "));
        return FieldSpec.builder(
                        INTERCEPTED_METHOD,
                        descriptorName(method),
                        Modifier.PRIVATE,
                        Modifier.STATIC,
                        Modifier.FINAL)
                .initializer(
                        "$T.of($T.class, $S, $L, $T.of($L), $T.of($L))",
                        INTERCEPTED_METHOD,
                        model.beanType(),
                        method.name(),
                        returnTypeLiteral(method.returnType()),
                        LIST,
                        parameterTypes,
                        LIST,
                        bindings)
                .build();
    }

    private static FieldSpec chainField(InterceptedMethodModel method) {
        return FieldSpec.builder(METHOD_INTERCEPTION, method.fieldName(), Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /** {@return the hoisted joinpoint lambda: one allocation per proxy instead of one per call} */
    private static FieldSpec joinpointField(InterceptedMethodModel method) {
        CodeBlock arguments = IntStream.range(0, method.parameters().size())
                .mapToObj(index -> CodeBlock.of(
                        "($T) arguments[$L]", method.parameters().get(index).type(), index))
                .collect(CodeBlock.joining(", "));
        CodeBlock body = method.returnType().equals(TypeName.VOID)
                ? CodeBlock.of("arguments -> {\n$>super.$L($L);\nreturn null;\n$<}", method.name(), arguments)
                : CodeBlock.of("arguments -> super.$L($L)", method.name(), arguments);
        return FieldSpec.builder(JOINPOINT, joinpointName(method), Modifier.PRIVATE, Modifier.FINAL)
                .initializer(body)
                .build();
    }

    /** {@return the proxy constructor: {@code Context} first, then the bean's own constructor parameters} */
    private static MethodSpec constructor(BeanModel model, InterceptionModel interception) {
        MethodSpec.Builder builder =
                MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(CONTEXT, "context");
        for (ProxyParameter parameter : interception.constructorParameters()) {
            builder.addParameter(parameter.type(), parameter.name());
        }
        CodeBlock superArguments = interception.constructorParameters().stream()
                .map(parameter -> CodeBlock.of("$L", parameter.name()))
                .collect(CodeBlock.joining(", "));
        builder.addStatement("super($L)", superArguments);
        for (InterceptedMethodModel method : interception.methods()) {
            builder.addStatement("this.$L = $L.bind(context)", method.fieldName(), descriptorName(method));
        }
        return builder.build();
    }

    /** {@return the override that runs the chain, falling through to {@code super} before the chain is assigned} */
    private static MethodSpec override(BeanModel model, InterceptedMethodModel method) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(method.name())
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(method.returnType())
                .varargs(method.varargs())
                .addTypeVariables(method.typeVariables());
        for (ProxyParameter parameter : method.parameters()) {
            builder.addParameter(parameter.type(), parameter.name());
        }
        for (TypeName thrown : method.thrownTypes()) {
            builder.addException(thrown);
        }
        if (method.declaresThrowable()) {
            builder.addException(THROWABLE);
        }
        if (method.uncheckedCast()) {
            builder.addAnnotation(java.lang.SuppressWarnings.class);
        }
        CodeBlock arguments = method.parameters().stream()
                .map(parameter -> CodeBlock.of("$L", parameter.name()))
                .collect(CodeBlock.joining(", "));
        builder.addStatement("$T $$interception = this.$L", METHOD_INTERCEPTION, method.fieldName());
        builder.beginControlFlow("if ($$interception == null)");
        if (method.returnType().equals(TypeName.VOID)) {
            builder.addStatement("super.$L($L)", method.name(), arguments);
            builder.addStatement("return");
        } else {
            builder.addStatement("return super.$L($L)", method.name(), arguments);
        }
        builder.endControlFlow();
        builder.beginControlFlow("try");
        CodeBlock call = CodeBlock.of(
                "$$interception.invoke(this, new $T[] {$L}, this.$L)", OBJECT, arguments, joinpointName(method));
        if (method.returnType().equals(TypeName.VOID)) {
            builder.addStatement("$L", call);
        } else if (method.returnType().isPrimitive()) {
            builder.addStatement("$T $$result = $L", OBJECT, call);
            builder.beginControlFlow("if ($$result == null)");
            builder.addStatement(
                    "throw new $T($S)",
                    INTERCEPTION_EXCEPTION,
                    "An interceptor returned null for the primitive return type " + method.returnType() + " of "
                            + model.beanType().simpleName() + "." + method.name());
            builder.endControlFlow();
            builder.addStatement("return ($T) $$result", method.returnType().box());
        } else {
            builder.addStatement("return ($T) $L", method.returnType(), call);
        }
        builder.nextControlFlow("catch ($T | $T $$e)", RUNTIME_EXCEPTION, ERROR);
        builder.addStatement("throw $$e");
        for (TypeName thrown : method.thrownTypes()) {
            builder.nextControlFlow("catch ($T $$e)", thrown);
            builder.addStatement("throw $$e");
        }
        builder.nextControlFlow("catch ($T $$t)", THROWABLE);
        if (method.declaresThrowable()) {
            builder.addStatement("throw $$t");
        } else {
            builder.addStatement(
                    "throw new $T($S, $$t)",
                    INTERCEPTION_EXCEPTION,
                    "An interceptor threw an undeclared exception from " + model.beanType().simpleName() + "."
                            + method.name());
        }
        builder.endControlFlow();
        return builder.build();
    }

    private static String descriptorName(InterceptedMethodModel method) {
        return method.fieldName().replace('$', '_').toUpperCase(java.util.Locale.ROOT);
    }

    private static String joinpointName(InterceptedMethodModel method) {
        return method.fieldName() + "$joinpoint";
    }

    /** {@return a class-literal-safe type name: erased and unboxed-preserving} */
    private static TypeName rawType(TypeName type) {
        return type instanceof com.palantir.javapoet.ParameterizedTypeName parameterized ? parameterized.rawType() : type;
    }

    /** {@return the class literal expression for a return type, using {@code void.class} for void} */
    private static CodeBlock returnTypeLiteral(TypeName returnType) {
        return returnType.equals(TypeName.VOID)
                ? CodeBlock.of("void.class")
                : CodeBlock.of("$T.class", rawType(returnType));
    }
}
```

Checkstyle forbids fully-qualified names: replace `java.lang.SuppressWarnings.class` with an `AnnotationSpec` built from an imported `ClassName`, `java.util.Locale.ROOT` with an imported `Locale.ROOT`, and `com.palantir.javapoet.ParameterizedTypeName` with an import. Concretely, add these imports and use them:

```java
import java.util.Locale;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ParameterizedTypeName;
```

and emit the suppression as:

```java
        if (method.uncheckedCast()) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }
```

- [ ] **Step 5: Point the factory at the proxy and write the file**

In `inject-processor/src/main/java/be/imgn/inject/processor/BeanFactoryGenerator.java`, replace the body of `createInstance` with:

```java
    private static MethodSpec createInstance(BeanModel model) {
        CodeBlock arguments = IntStream.range(0, model.constructorParameters().size())
                .mapToObj(index -> argument(model.constructorParameters().get(index), "Constructor", index))
                .collect(CodeBlock.joining(", "));
        InterceptionModel interception = model.interception();
        CodeBlock construction = interception == null
                ? CodeBlock.of("new $T($L)", model.beanType(), arguments)
                : CodeBlock.of(
                        "new $L(context$L)",
                        interception.proxySimpleName(),
                        arguments.isEmpty() ? CodeBlock.of("") : CodeBlock.of(", $L", arguments));
        return MethodSpec.methodBuilder("createInstance")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(model.beanType())
                .addParameter(CONTEXT, "context")
                .addStatement("return $L", construction)
                .build();
    }
```

The proxy is in the same package as the factory, so referring to it by simple name needs no import.

In `inject-processor/src/main/java/be/imgn/inject/processor/InjectProcessor.java`, extend the bean loop in `process`:

```java
        for (BeanModel model : gathered.beans().values()) {
            if (model.interception() != null) {
                writeFactory(InterceptedProxyGenerator.generate(model));
            }
            writeFactory(BeanFactoryGenerator.generate(model));
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
```

- [ ] **Step 6: Run both tests to verify they pass**

```bash
./mvnw -pl inject-processor test -Dtest='InterceptedProxyGenerationTest+InterceptionEndToEndTest'
```

Expected: PASS — 3 generation tests and 1 end-to-end test. The end-to-end log assertion proves interception order *and* self-invocation.

- [ ] **Step 7: Run the full build**

```bash
./mvnw clean verify
echo "exit=$?"
```

Expected: `BUILD SUCCESS`, `exit=0`.

- [ ] **Step 8: Commit**

```bash
git add inject-processor/src/main/java/be/imgn/inject/processor inject-processor/src/test/java/be/imgn/inject/processor
git commit -m "feat(processor): generate intercepted proxies"
```

---

### Task 6: Examples and documentation

Proves the feature on both the classpath and the module path — the module-path example exists specifically to show that an interceptor needs no new `provides` directive beyond the factory one — and brings the docs to the current state.

**Files:**
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Timed.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Audited.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/CallLog.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/TimingInterceptor.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/AuditInterceptor.java`
- Create: `inject-examples/src/main/java/be/imgn/inject/examples/Checkout.java`
- Create: `inject-examples/src/test/java/be/imgn/inject/examples/InterceptionExampleTest.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/Traced.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TracingInterceptor.java`
- Create: `inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TracedService.java`
- Modify: `inject-examples-module/src/main/java/module-info.java`
- Create: `docs/features/interception.md`
- Modify: `docs/features/README.md`
- Modify: `docs/features/type-exposure.md`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: no new API — this task is the proof and the documentation.

- [ ] **Step 1: Write the failing example test**

Create `inject-examples/src/test/java/be/imgn/inject/examples/InterceptionExampleTest.java`:

```java
package be.imgn.inject.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Injector;

class InterceptionExampleTest {

    @Test
    void interceptorsRunOutermostFirstAroundTheBean() {
        try (Injector injector = Injector.create()) {
            Checkout checkout = injector.getInstance(Checkout.class);
            CallLog log = injector.getInstance(CallLog.class);

            String receipt = checkout.place("cart-1");

            assertThat(receipt).isEqualTo("placed:cart-1");
            assertThat(log.entries())
                    .containsExactly("audit:enter:place", "timed:enter:place", "timed:exit:place", "audit:exit:place");
        }
    }

    @Test
    void anInterceptorCanSubstituteArguments() {
        try (Injector injector = Injector.create()) {
            Checkout checkout = injector.getInstance(Checkout.class);

            assertThat(checkout.place("  cart-2  ")).isEqualTo("placed:cart-2");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -pl inject-examples test -Dtest=InterceptionExampleTest
```

Expected: FAIL — `cannot find symbol: class Checkout`.

- [ ] **Step 3: Write the classpath example**

`Timed.java`:

```java
package be.imgn.inject.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.InterceptingAnnotation;

/** Records entry and exit around a call, tagged with {@link #value()}. */
@InterceptingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Timed {
    /** {@return the tag recorded with the call; defaults to the method name} */
    String value() default "";
}
```

`Audited.java`:

```java
package be.imgn.inject.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.InterceptingAnnotation;

/** Records an audit entry around every call to the annotated bean. */
@InterceptingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Audited {}
```

`CallLog.java`:

```java
package be.imgn.inject.examples;

import java.util.ArrayList;
import java.util.List;

import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;

/** Shared sink both interceptors write to, showing that an interceptor is an injected bean like any other. */
@Singleton
public class CallLog {

    private final List<String> entries = new ArrayList<>();

    /** Creates an empty log. */
    @Inject
    public CallLog() {}

    /**
     * Appends one entry.
     *
     * @param entry the entry to record
     */
    public void add(String entry) {
        entries.add(entry);
    }

    /** {@return the entries recorded so far, in order} */
    public List<String> entries() {
        return List.copyOf(entries);
    }
}
```

`TimingInterceptor.java`:

```java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Intercepts;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.context.MethodInvocation;

/** Handles {@link Timed}: logs entry and exit, and strips a leading/trailing-blank first argument. */
@Singleton
@Intercepts(Timed.class)
public class TimingInterceptor implements MethodInterceptor {

    private final CallLog log;

    /** @param log the shared call log */
    @Inject
    public TimingInterceptor(CallLog log) {
        this.log = log;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String tag = invocation.annotation().stringValue("value").orElse(invocation.methodName());
        log.add("timed:enter:" + tag);
        try {
            if (invocation.arguments().size() == 1 && invocation.arguments().get(0) instanceof String argument) {
                return invocation.proceed(argument.strip());
            }
            return invocation.proceed();
        } finally {
            log.add("timed:exit:" + tag);
        }
    }
}
```

`AuditInterceptor.java`:

```java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Intercepts;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.context.MethodInvocation;

/** Handles {@link Audited}: records the call at the outermost position of the chain. */
@Singleton
@Intercepts(Audited.class)
public class AuditInterceptor implements MethodInterceptor {

    private final CallLog log;

    /** @param log the shared call log */
    @Inject
    public AuditInterceptor(CallLog log) {
        this.log = log;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        log.add("audit:enter:" + invocation.methodName());
        try {
            return invocation.proceed();
        } finally {
            log.add("audit:exit:" + invocation.methodName());
        }
    }
}
```

`Checkout.java`:

```java
package be.imgn.inject.examples;

import be.imgn.inject.Inject;

/** Intercepted bean: {@code @Audited} on the class wraps {@code @Timed} on the method. */
@Audited
public class Checkout {

    /** Creates a checkout. */
    @Inject
    public Checkout() {}

    /**
     * {@return a receipt for {@code cart}}
     *
     * @param cart the cart identifier
     */
    @Timed("place")
    public String place(String cart) {
        return "placed:" + cart;
    }
}
```

- [ ] **Step 4: Run the example test to verify it passes**

```bash
./mvnw -pl inject-examples test -Dtest=InterceptionExampleTest
```

Expected: PASS — 2 tests. The two log entries per interceptor confirm the documented order: class-level `@Audited` outside method-level `@Timed`.

- [ ] **Step 5: Add the module-path example**

`inject-examples-module/src/main/java/be/imgn/inject/examples/modular/Traced.java`:

```java
package be.imgn.inject.examples.modular;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import be.imgn.inject.InterceptingAnnotation;

/** Marks a call to be prefixed by the tracing interceptor. */
@InterceptingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Traced {}
```

`inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TracingInterceptor.java`:

```java
package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import be.imgn.inject.Intercepts;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.context.MethodInvocation;

/** Handles {@link Traced} by prefixing the result. An interceptor needs no {@code provides} directive of its own. */
@Singleton
@Intercepts(Traced.class)
public class TracingInterceptor implements MethodInterceptor {

    /** Creates the interceptor. */
    @Inject
    public TracingInterceptor() {}

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        return "traced:" + invocation.proceed();
    }
}
```

`inject-examples-module/src/main/java/be/imgn/inject/examples/modular/TracedService.java`:

```java
package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;

/** Module-path bean whose call is intercepted. */
public class TracedService {

    /** Creates the service. */
    @Inject
    public TracedService() {}

    /** {@return the service's own answer, before interception} */
    @Traced
    public String answer() {
        return "answer";
    }
}
```

Add the two new factories to `inject-examples-module/src/main/java/module-info.java`'s `provides` list:

```java
            be.imgn.inject.examples.modular.$TelemetryConfig$offTelemetry$$Factory,
            be.imgn.inject.examples.modular.$TracingInterceptor$$Factory,
            be.imgn.inject.examples.modular.$TracedService$$Factory;
```

Note what is *not* added: no `provides` for an interceptor service type, because there is none — the interceptor is reached through its factory.

Extend whichever test class exercises the module-path example (the module's own test source set) with:

```java
    @Test
    void interceptsOnTheModulePath() {
        try (Injector injector = Injector.create()) {
            assertThat(injector.getInstance(TracedService.class).answer()).isEqualTo("traced:answer");
        }
    }
```

If the module has no test source set, verify through the existing module-path run and assert the same value there.

- [ ] **Step 6: Write the feature documentation**

Create `docs/features/interception.md`:

```markdown
# Method Interception

## The problem

Timing, auditing, retries, and transactions are the same few lines wrapped
around many methods. Written by hand they are copied into every method that
needs them; written with a reflective proxy they cost the zero-reflection
guarantee.

## How it solves it

An annotation meta-annotated `@InterceptingAnnotation` marks calls to route
through interceptors. The processor emits a subclass of the bean that
overrides each intercepted method and dispatches through a chain to `super`:

```java
@InterceptingAnnotation
@Retention(RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Timed {
    String value() default "";
}
```

The interceptor is an ordinary bean marked `@Intercepts`, so it is injected,
scoped, and conditioned like any other:

```java
@Singleton
@Intercepts(Timed.class)
public final class TimingInterceptor implements MethodInterceptor {

    private final Metrics metrics;

    @Inject
    public TimingInterceptor(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            metrics.record(invocation.annotation().stringValue("value").orElse(invocation.methodName()),
                    System.nanoTime() - start);
        }
    }
}
```

The bean itself changes only by being subclassable:

```java
public class OrderService {

    @Inject
    public OrderService(Repo repo) { ... }

    @Timed("place")
    public Order place(Cart cart) { ... }
}
```

Because the generated proxy *is* the instance the injector hands out, member
injection, `@PostConstruct`, scoping, and destruction are unaffected, and a
call from one bean method to another is intercepted too.

### Placement and order

A class-level annotation sweeps every declared method that is not `private`,
`static`, or `final`, skipping `Object` methods and `@Inject` /
`@PostConstruct` / `@PreDestroy` methods. A method-level annotation applies to
that method alone.

Interceptors run in source order, first listed outermost, and class-level
annotations wrap method-level ones:

```java
@Retry
public class OrderService {

    @Secured
    @Timed
    public Order place(Cart cart) { ... }
}
```

runs `Retry → Secured → Timed → place`. Declaring the same annotation at both
levels replaces the class-level one in place, so a method can retune
`@Timed("checkout")` without inheriting `@Timed("")` as well.

### Reading and substituting arguments

`MethodInvocation` exposes the call site as compile-time constants — no
`java.lang.reflect.Method` anywhere — and `proceed(Object...)` substitutes
arguments for the rest of the chain, validating count and types:

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
    String raw = (String) invocation.arguments().get(0);
    return invocation.proceed(raw.strip());
}
```

## Limitations

- The bean must be subclassable. A `final` class, a `record`, an `enum`, a
  `sealed` class, and a generic class are each a compile error naming the
  reason; so is a `final`, `static`, or `private` method carrying the
  annotation.
- Only method calls are intercepted: no constructor or field interception, and
  an interceptor cannot add methods a bean does not declare.
- Calls made from the bean's own constructor are not intercepted — the chain
  is assigned after `super(...)` returns, so a constructor-time call falls
  through to the bean's own implementation.
- Inherited methods are not swept; a class-level annotation covers declared
  methods only, consistent with
  [member injection](injection.md) and [lifecycle callbacks](lifecycle-callbacks.md).
- [`@Provides`](provides-methods.md) products and
  [assisted](assisted-injection.md) beans cannot be intercepted: the container
  does not construct them.
- [Builder overrides](builder-overrides.md) bypass interception entirely — an
  override binds a raw instance, and nothing proxies it.
- There is no ordering attribute: the bean's author fixes the order by writing
  the annotations in the order they should run.
- An intercepting annotation with no `@Intercepts` bean fails at the first
  intercepted call with `InterceptorResolutionException`, not at compile time —
  the interceptor may legitimately live in another artifact.
```

- [ ] **Step 7: Update the documentation index and the exposure page**

In `docs/features/README.md`, add under "Declaring beans", after the lifecycle-callbacks entry:

```markdown
- [Method interception](interception.md) — `@InterceptingAnnotation` triggers
  and `@Intercepts` beans running around method calls, wired by a generated
  subclass.
```

In `docs/features/type-exposure.md`, extend the excluded-contracts sentence so it states the current list:

```markdown
Universal contracts are excluded from that default so they never become
accidental binding keys: `Object`, `Serializable`, `Cloneable`, `Comparable`,
`Runnable`, `AutoCloseable`, `Closeable`, `Iterable`, `EventListener`,
`MethodInterceptor`, and the framework's own types.
```

- [ ] **Step 8: Run the full build**

```bash
./mvnw clean verify
echo "exit=$?"
```

Expected: `BUILD SUCCESS`, `exit=0`, with the module-path example run passing.

- [ ] **Step 9: Commit**

```bash
git add inject-examples inject-examples-module docs/features
git commit -m "feat(examples): interception examples and feature documentation"
```

---

## Self-Review Notes

Checked against `docs/superpowers/specs/2026-07-26-interception-design.md`:

- Every spec section maps to a task: SPI and exceptions → Task 1; `Factory.intercepts`/index/duplicates/child delegation → Task 2; stereotype capture, `MethodInterceptor` exposure exclusion, `intercepts()` generation → Task 3; placement, order, and the whole compile-time error table → Task 4; generated code shape including `void`, primitives, varargs, checked exceptions, and the constructor-time null guard → Task 5; examples and docs → Task 6.
- **Spec addendum found while planning:** a *generic* bean class cannot be intercepted, because the framework binds generic beans under their raw type and a mirrored type-parameter proxy would contradict that. Task 4 implements and tests the rule; the spec's compile-time error table needs the same row.
- Type consistency: `InterceptedMethod.of` / `bind`, `MethodInterception.invoke(Object, Object[], Joinpoint)`, `Joinpoint.invoke(Object[])`, `Context.interceptor(Class)`, `Factory.intercepts()`, `BeanModel.interceptsAnnotation()`, `BeanModel.interception()`, and `GeneratedNames.interceptedName` are spelled identically everywhere they appear.
