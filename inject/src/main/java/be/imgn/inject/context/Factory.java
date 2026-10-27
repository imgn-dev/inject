package be.imgn.inject.context;

import be.imgn.inject.InjectionException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Base class for per-bean factories. Generated subclasses live in the bean's own package so they can access
 * package-private members with zero reflection.
 *
 * <p>A factory is also the {@link BeanDefinition} for its bean: the read-only introspection view exposed through the
 * container is this instance.
 *
 * @param <T> the built type
 */
public abstract class Factory<T> implements BeanDefinition {
    private final Class<T> type;

    /**
     * Creates a factory for {@code type}.
     *
     * @param type the built type
     */
    protected Factory(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Returns the built type.
     *
     * @return the built type
     */
    public final Class<T> type() {
        return type;
    }

    /** {@return the bean's primary (unqualified) key} */
    @Override
    public final Key<T> key() {
        return Key.of(type);
    }

    /**
     * {@return the framework annotations declared on the bean itself; none by default}
     *
     * <p>Generated factories override this with the annotations meta-annotated by one of the framework's root
     * annotations that appear on the bean type or its {@code @Provides} method.
     */
    @Override
    public AnnotationMetadata annotationMetadata() {
        return AnnotationMetadata.empty();
    }

    /** {@return the bean's injection points: its constructor (when it injects), then methods, then fields} */
    @Override
    public final List<InjectionPoint> injectionPoints() {
        List<InjectionPoint> points = new ArrayList<>();
        constructorInjectionPoint().ifPresent(points::add);
        points.addAll(methodInjectionPoints());
        points.addAll(fieldInjectionPoints());
        return List.copyOf(points);
    }

    /**
     * Builds and fully injects an instance, then reports it through {@link Context#constructed(Object)}.
     *
     * @param context the resolution context
     * @return the built instance
     */
    public final T build(Context context) {
        Objects.requireNonNull(context, "context");
        T instance = createInstance(context);
        injectMethods(instance, context);
        injectFields(instance, context);
        postConstruct(instance);
        return context.constructed(instance);
    }

    /**
     * Creates the instance (constructor injection).
     *
     * @param context the resolution context
     * @return the new instance
     */
    protected abstract T createInstance(Context context);

    /**
     * Injects annotated methods. Empty by default.
     *
     * @param instance the instance to inject into
     * @param context the resolution context
     */
    protected void injectMethods(T instance, Context context) {}

    /**
     * Injects annotated fields. Empty by default.
     *
     * @param instance the instance to inject into
     * @param context the resolution context
     */
    protected void injectFields(T instance, Context context) {}

    /**
     * Invoked by {@link #build} after field injection. Empty by default; a generated factory overrides it to call the
     * bean's {@code @PostConstruct} method.
     *
     * @param instance the fully injected instance
     */
    protected void postConstruct(T instance) {}

    /**
     * Invoked by the owning context when the injector closes. Empty by default; a generated factory whose bean declares
     * {@code @PreDestroy} overrides it to call that method.
     *
     * @param instance the instance to destroy
     */
    protected void preDestroy(T instance) {}

    /** {@return whether this factory's bean declares a {@code @PreDestroy} callback} */
    protected boolean hasPreDestroy() {
        return false;
    }

    /** {@return the constructor injection point, or empty when the constructor takes no injected arguments} */
    protected Optional<ConstructorInjectionPoint> constructorInjectionPoint() {
        return Optional.empty();
    }

    /** {@return the method injection points; none by default} */
    protected List<MethodInjectionPoint> methodInjectionPoints() {
        return List.of();
    }

    /** {@return the field injection points; none by default} */
    protected List<FieldInjectionPoint> fieldInjectionPoints() {
        return List.of();
    }

    /** {@return the scoping annotation for this bean, or empty for prototype scope} */
    protected Optional<Class<? extends Annotation>> scope() {
        return Optional.empty();
    }

    /** {@return the keys this factory exposes; the bean type by default} */
    protected List<Key<?>> exposedKeys() {
        return List.of(Key.of(type));
    }

    /**
     * {@return this factory's collection contribution, or empty when the bean binds under its own keys}
     *
     * <p>A contributing factory exposes no keys of its own; its bean is reachable only through the collection.
     */
    protected Optional<Contribution> contribution() {
        return Optional.empty();
    }

    /** {@return whether this factory is a fallback binding, used only when no other live binding claims its key} */
    protected boolean isDefault() {
        return false;
    }

    /**
     * {@return the products of child-local scope this factory creates; none by default}
     *
     * <p>The factory of an assisted factory interface overrides this to declare the products of its scoped recipes.
     */
    protected List<ScopedProduct> scopedProducts() {
        return List.of();
    }

    /**
     * {@return the intercepting annotation this bean handles, or empty when the bean is not an interceptor}
     *
     * <p>A generated factory overrides this for a bean annotated {@code @Intercepts}.
     */
    protected Optional<Class<? extends Annotation>> intercepts() {
        return Optional.empty();
    }

    /**
     * {@return the provider for the {@code paramIndex}-th constructor parameter}
     *
     * <p>Generated code names the dependency type twice: {@code <D>}, so that the call has an exact static type and
     * selects the right overload, and {@code type}, its erasure, which is checked against the declared parameter type
     * and against every value the provider returns.
     *
     * @param paramIndex the zero-based parameter index
     * @param type the erasure of the parameter's dependency type
     * @param context the resolution context
     * @param <D> the dependency type
     * @throws InjectionException if {@code type} is not the erasure of the declared parameter type, or, from the
     *     provider, if a resolved value is not a {@code type}
     */
    protected final <D> Provider<D> getProviderForConstructor(int paramIndex, Class<? super D> type, Context context) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(context, "context");
        ConstructorInjectionPoint point = constructorInjectionPoint()
                .orElseThrow(() -> new IllegalStateException("No constructor injection point for " + type().getName()));
        checkIndex(paramIndex, point.parameters().size(), "constructor");
        Parameter parameter = point.parameters().get(paramIndex);
        String site = "constructor parameter " + parameter.name();
        checkDeclaredType(site, parameter.type(), type);
        return checked(site, type, context.getProvider(point.<D>keyForParameter(paramIndex)));
    }

    /**
     * {@return the resolved instance for the {@code paramIndex}-th constructor parameter}
     *
     * @param paramIndex the zero-based parameter index
     * @param type the erasure of the parameter's dependency type
     * @param context the resolution context
     * @param <D> the dependency type
     * @throws InjectionException if {@code type} is not the erasure of the declared parameter type, or the resolved
     *     value is not a {@code type}
     * @see #getProviderForConstructor(int, Class, Context)
     */
    // D is chosen by the generated caller with an explicit type argument, and checked through type.
    @SuppressWarnings("TypeParameterUnusedInFormals")
    protected final <D> D getInstanceForConstructor(int paramIndex, Class<? super D> type, Context context) {
        return this.<D>getProviderForConstructor(paramIndex, type, context).get();
    }

    /**
     * {@return the provider for the {@code paramIndex}-th parameter of the method at {@code methodIndex}}
     *
     * @param methodIndex the index into {@link #methodInjectionPoints()}
     * @param paramIndex the zero-based parameter index
     * @param type the erasure of the parameter's dependency type
     * @param context the resolution context
     * @param <D> the dependency type
     * @throws InjectionException if {@code type} is not the erasure of the declared parameter type, or, from the
     *     provider, if a resolved value is not a {@code type}
     * @see #getProviderForConstructor(int, Class, Context)
     */
    protected final <D> Provider<D> getProviderForMethod(
            int methodIndex, int paramIndex, Class<? super D> type, Context context) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(context, "context");
        MethodInjectionPoint point = methodInjectionPoints().get(methodIndex);
        checkIndex(paramIndex, point.parameters().size(), "method");
        Parameter parameter = point.parameters().get(paramIndex);
        String site = "parameter " + parameter.name() + " of method " + point.methodName();
        checkDeclaredType(site, parameter.type(), type);
        return checked(site, type, context.getProvider(point.<D>keyForParameter(paramIndex)));
    }

    /**
     * {@return the resolved instance for a method parameter}
     *
     * @param methodIndex the index into {@link #methodInjectionPoints()}
     * @param paramIndex the zero-based parameter index
     * @param type the erasure of the parameter's dependency type
     * @param context the resolution context
     * @param <D> the dependency type
     * @throws InjectionException if {@code type} is not the erasure of the declared parameter type, or the resolved
     *     value is not a {@code type}
     * @see #getProviderForConstructor(int, Class, Context)
     */
    @SuppressWarnings("TypeParameterUnusedInFormals")
    protected final <D> D getInstanceForMethod(
            int methodIndex, int paramIndex, Class<? super D> type, Context context) {
        return this.<D>getProviderForMethod(methodIndex, paramIndex, type, context)
                .get();
    }

    /**
     * {@return the provider for the field at {@code fieldIndex}}
     *
     * @param fieldIndex the index into {@link #fieldInjectionPoints()}
     * @param type the erasure of the field's dependency type
     * @param context the resolution context
     * @param <D> the dependency type
     * @throws InjectionException if {@code type} is not the erasure of the declared field type, or, from the provider,
     *     if a resolved value is not a {@code type}
     * @see #getProviderForConstructor(int, Class, Context)
     */
    protected final <D> Provider<D> getProviderForField(int fieldIndex, Class<? super D> type, Context context) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(context, "context");
        FieldInjectionPoint point = fieldInjectionPoints().get(fieldIndex);
        String site = "field " + point.fieldName();
        checkDeclaredType(site, point.fieldType(), type);
        return checked(site, type, context.getProvider(point.<D>key()));
    }

    /**
     * {@return the resolved instance for a field}
     *
     * @param fieldIndex the index into {@link #fieldInjectionPoints()}
     * @param type the erasure of the field's dependency type
     * @param context the resolution context
     * @param <D> the dependency type
     * @throws InjectionException if {@code type} is not the erasure of the declared field type, or the resolved value
     *     is not a {@code type}
     * @see #getProviderForConstructor(int, Class, Context)
     */
    @SuppressWarnings("TypeParameterUnusedInFormals")
    protected final <D> D getInstanceForField(int fieldIndex, Class<? super D> type, Context context) {
        return this.<D>getProviderForField(fieldIndex, type, context).get();
    }

    private void checkDeclaredType(String site, Type declared, Class<?> type) {
        if (InjectionTypes.erasure(declared) != type) {
            throw new InjectionException(this.type.getName() + " " + site + " is declared as " + declared.getTypeName()
                    + ", but is resolved as " + type.getName() + ".");
        }
    }

    private <D> Provider<D> checked(String site, Class<? super D> type, Provider<D> provider) {
        Class<?> accepted = InjectionTypes.boxed(type);
        return () -> {
            D value = provider.get();
            // Null passes for a reference type, as it always has; only a primitive cannot take it.
            if (value == null ? type.isPrimitive() : !accepted.isInstance(value)) {
                throw new InjectionException(
                        this.type.getName() + " " + site + " expects " + type.getName() + ", but was given "
                                + (value == null ? "null" : value.getClass().getName()) + ".");
            }
            return value;
        };
    }

    private void checkIndex(int index, int size, String kind) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException(
                    "No " + kind + " dependency at index " + index + " for " + type.getName());
        }
    }
}
