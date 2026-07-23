package be.imgn.inject.context;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

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

    /** @param type the built type */
    protected Factory(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** {@return the built type} */
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
     * Builds and fully injects an instance.
     *
     * @param context the resolution context
     * @return the built instance
     */
    public final T build(Context context) {
        Objects.requireNonNull(context, "context");
        T instance = createInstance(context);
        injectMethods(instance, context);
        injectFields(instance, context);
        return instance;
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
     * {@return the provider for the {@code paramIndex}-th constructor parameter}
     *
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForConstructor(int paramIndex, Context context) {
        Objects.requireNonNull(context, "context");
        ConstructorInjectionPoint point = constructorInjectionPoint()
                .orElseThrow(() -> new IllegalStateException("No constructor injection point for " + type().getName()));
        checkIndex(paramIndex, point.parameters().size(), "constructor");
        return providerFor(point.<D>keyForParameter(paramIndex), context);
    }

    /**
     * {@return the resolved instance for the {@code paramIndex}-th constructor parameter}
     *
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> D getInstanceForConstructor(int paramIndex, Context context) {
        return this.<D>getProviderForConstructor(paramIndex, context).get();
    }

    /**
     * {@return the provider for the {@code paramIndex}-th parameter of the method at {@code methodIndex}}
     *
     * @param methodIndex the index into {@link #methodInjectionPoints()}
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForMethod(int methodIndex, int paramIndex, Context context) {
        Objects.requireNonNull(context, "context");
        MethodInjectionPoint point = methodInjectionPoints().get(methodIndex);
        checkIndex(paramIndex, point.parameters().size(), "method");
        return providerFor(point.<D>keyForParameter(paramIndex), context);
    }

    /**
     * {@return the resolved instance for a method parameter}
     *
     * @param methodIndex the index into {@link #methodInjectionPoints()}
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> D getInstanceForMethod(int methodIndex, int paramIndex, Context context) {
        return this.<D>getProviderForMethod(methodIndex, paramIndex, context).get();
    }

    /**
     * {@return the provider for the field at {@code fieldIndex}}
     *
     * @param fieldIndex the index into {@link #fieldInjectionPoints()}
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForField(int fieldIndex, Context context) {
        Objects.requireNonNull(context, "context");
        FieldInjectionPoint point = fieldInjectionPoints().get(fieldIndex);
        return providerFor(point.<D>key(), context);
    }

    /**
     * {@return the resolved instance for a field}
     *
     * @param fieldIndex the index into {@link #fieldInjectionPoints()}
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> D getInstanceForField(int fieldIndex, Context context) {
        return this.<D>getProviderForField(fieldIndex, context).get();
    }

    @SuppressWarnings("unchecked")
    private <D> Provider<D> providerFor(Key<D> key, Context context) {
        return (Provider<D>) context.getProvider(key);
    }

    private void checkIndex(int index, int size, String kind) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException(
                    "No " + kind + " dependency at index " + index + " for " + type.getName());
        }
    }
}
