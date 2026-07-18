package be.imgn.inject.context;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

/**
 * Base class for per-bean factories. Generated subclasses live in the bean's own package so they can access
 * package-private members with zero reflection.
 *
 * @param <T> the built type
 */
public abstract class Factory<T> {
    private final Class<T> type;

    /** @param type the built type */
    protected Factory(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** {@return the built type} */
    public final Class<T> type() {
        return type;
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

    /** {@return the constructor injection point; no-arg by default} */
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return ConstructorInjectionPoint.none();
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
     * {@return the provider for the {@code paramIndex}-th constructor parameter}
     *
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForConstructor(int paramIndex, Context context) {
        return providerFor(constructorInjectionPoint().keys(), paramIndex, context, "constructor");
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
        return providerFor(methodInjectionPoints().get(methodIndex).keys(), paramIndex, context, "method");
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
        return providerFor(fieldInjectionPoints().get(fieldIndex).keys(), 0, context, "field");
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
    private <D> Provider<D> providerFor(List<Key<?>> keys, int index, Context context, String kind) {
        Objects.requireNonNull(context, "context");
        if (index < 0 || index >= keys.size()) {
            throw new IllegalArgumentException(
                    "No " + kind + " dependency at index " + index + " for " + type.getName());
        }
        return (Provider<D>) context.getProvider(keys.get(index));
    }
}
