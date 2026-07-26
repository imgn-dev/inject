package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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

import org.jspecify.annotations.Nullable;
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
        public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
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
        MethodInterceptor stripping = invocation ->
                invocation.proceed(((String) invocation.arguments().get(0)).strip());
        MethodInterception chain = greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, stripping)));

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
                .withMessageContaining(String.class.getName());
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
        MethodInterception chain =
                greet(List.of(OUTER, INNER)).bind(new StubContext(Map.of(Outer.class, outer, Inner.class, inner)));

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
                            "seen:call-0",
                            "seen:call-1",
                            "seen:call-2",
                            "seen:call-3",
                            "seen:call-4",
                            "seen:call-5",
                            "seen:call-6",
                            "seen:call-7");
        }
    }

    @Test
    void bindFailsWhenNoInterceptorHandlesTheAnnotation() {
        assertThatExceptionOfType(InterceptorResolutionException.class)
                .isThrownBy(() -> greet(List.of(OUTER)).bind(new StubContext(Map.of())))
                .withMessageContaining("Outer")
                .withMessageContaining(String.class.getName() + ".greet");
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

    @Test
    @SuppressWarnings("NullAway")
    void interceptedMethodOfRejectsNullDeclaringType() {
        assertThatNullPointerException()
                .isThrownBy(() -> InterceptedMethod.of(
                        (Class<?>) null, "greet", String.class, List.of(String.class), List.of(OUTER)))
                .withMessageContaining("declaringType");
    }

    @Test
    @SuppressWarnings("NullAway")
    void interceptedMethodOfRejectsNullMethodName() {
        assertThatNullPointerException()
                .isThrownBy(() -> InterceptedMethod.of(
                        String.class, (String) null, String.class, List.of(String.class), List.of(OUTER)))
                .withMessageContaining("methodName");
    }

    @Test
    @SuppressWarnings("NullAway")
    void interceptedMethodOfRejectsNullReturnType() {
        assertThatNullPointerException()
                .isThrownBy(() -> InterceptedMethod.of(
                        String.class, "greet", (Class<?>) null, List.of(String.class), List.of(OUTER)))
                .withMessageContaining("returnType");
    }

    @Test
    @SuppressWarnings("NullAway")
    void interceptedMethodOfRejectsNullParameterTypes() {
        assertThatNullPointerException()
                .isThrownBy(() -> InterceptedMethod.of(
                        String.class, "greet", String.class, (List<Class<?>>) null, List.of(OUTER)))
                .withMessageContaining("parameterTypes");
    }

    @Test
    @SuppressWarnings("NullAway")
    void interceptedMethodOfRejectsNullBindings() {
        assertThatNullPointerException()
                .isThrownBy(() -> InterceptedMethod.of(
                        String.class, "greet", String.class, List.of(String.class), (List<AnnotationValue>) null))
                .withMessageContaining("bindings");
    }

    @Test
    void interceptedMethodOfRejectsEmptyBindings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        InterceptedMethod.of(String.class, "greet", String.class, List.of(String.class), List.of()))
                .withMessageContaining("greet");
    }

    @Test
    @SuppressWarnings("NullAway")
    void interceptedMethodBindRejectsNullContext() {
        assertThatNullPointerException()
                .isThrownBy(() -> greet(List.of(OUTER)).bind(null))
                .withMessageContaining("context");
    }

    @Test
    @SuppressWarnings("NullAway")
    void methodInterceptionInvokeRejectsNullTarget() {
        MethodInterception chain =
                greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, MethodInvocation::proceed)));

        assertThatNullPointerException()
                .isThrownBy(() -> chain.invoke((Object) null, new Object[] {"world"}, arguments -> "ok"))
                .withMessageContaining("target");
    }

    @Test
    @SuppressWarnings("NullAway")
    void methodInterceptionInvokeRejectsNullArguments() {
        MethodInterception chain =
                greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, MethodInvocation::proceed)));

        assertThatNullPointerException()
                .isThrownBy(() -> chain.invoke("target", (Object[]) null, arguments -> "ok"))
                .withMessageContaining("arguments");
    }

    @Test
    @SuppressWarnings("NullAway")
    void methodInterceptionInvokeRejectsNullJoinpoint() {
        MethodInterception chain =
                greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, MethodInvocation::proceed)));

        assertThatNullPointerException()
                .isThrownBy(() -> chain.invoke("target", new Object[] {"world"}, (Joinpoint) null))
                .withMessageContaining("joinpoint");
    }

    @Test
    void anInterceptorMayCallProceedMultipleTimesForRetry() {
        List<Integer> callCount = new ArrayList<>();
        MethodInterceptor retryOnce = invocation -> {
            invocation.proceed();
            Object second = invocation.proceed();
            return second;
        };
        Joinpoint counting = arguments -> {
            callCount.add(callCount.size() + 1);
            return "result";
        };
        MethodInterception chain = greet(List.of(OUTER)).bind(new StubContext(Map.of(Outer.class, retryOnce)));

        Object result = invoke(chain, new Object[] {"world"}, counting);

        assertThat(result).isEqualTo("result");
        assertThat(callCount).containsExactly(1, 2);
    }

    /** Runs a chain, rethrowing any checked throwable as an unchecked one so tests stay readable. */
    private static @Nullable Object invoke(MethodInterception chain, Object[] arguments, Joinpoint joinpoint) {
        try {
            return chain.invoke("target", arguments, joinpoint);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
