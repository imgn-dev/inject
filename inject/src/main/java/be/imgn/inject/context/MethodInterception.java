package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
     * Returns the call's result, after running every interceptor around {@code joinpoint}.
     *
     * @param target the intercepted instance
     * @param arguments the call arguments, positionally; the chain takes ownership of the array
     * @param joinpoint the call to the bean's own implementation
     * @return the call's result, after running every interceptor around {@code joinpoint}
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
            throw new IllegalArgumentException(
                    method + " takes " + types.size() + " arguments but proceed() was given " + candidate.length + ".");
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
            Class<?> accepted = declared.isPrimitive() ? Objects.requireNonNull(WRAPPERS.get(declared)) : declared;
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
