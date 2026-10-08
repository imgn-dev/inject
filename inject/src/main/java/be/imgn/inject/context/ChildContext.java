package be.imgn.inject.context;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopingAnnotation;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A child {@link Context}: nearest-ancestor local bindings first (walking up through any intermediate
 * {@code ChildContext}s), then the parent. Unscoped parent beans are rebuilt at this — the deepest — context so their
 * dependencies see the full child binding stack; scoped beans and everything without a {@link BeanDefinition} anchor to
 * the parent unchanged.
 */
public final class ChildContext implements Context {

    private static final Key<?> INJECTOR_KEY = Key.of(Injector.class);

    private final Context parent;
    private final Map<Key<?>, Provider<?>> bindings;
    private final ResolutionStack stack = new ResolutionStack();
    private @Nullable Injector injector;
    private boolean closed;

    /**
     * Creates a child context over {@code parent}.
     *
     * @param parent the parent context resolutions fall back to
     * @param bindings the child's own bindings, by key
     */
    public ChildContext(Context parent, Map<Key<?>, Provider<?>> bindings) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }

    /**
     * Attaches the {@link Injector} this context resolves {@code Key.of(Injector.class)} to. Called once by the child
     * injector that owns this context, immediately after construction.
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
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
        if (INJECTOR_KEY.equals(key)) {
            return (Provider<T>) (Provider<Injector>) this::requireInjector;
        }
        Provider<?> layered = localLookup(key);
        if (layered != null) {
            return (Provider<T>) layered;
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

    /** {@return the nearest local binding for {@code key} in this context or an ancestor child, or {@code null}} */
    @Nullable
    Provider<?> localLookup(Key<?> key) {
        Provider<?> local = bindings.get(key);
        if (local != null) {
            return local;
        }
        return parent instanceof ChildContext child ? child.localLookup(key) : null;
    }

    @Override
    public Optional<BeanDefinition> getBeanDefinition(Key<?> key) {
        return parent.getBeanDefinition(key);
    }

    @Override
    public List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype) {
        return parent.getBeanDefinitionsByStereotype(stereotype);
    }

    @Override
    public Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(annotationType, "annotationType");
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
        return parent.interceptor(annotationType);
    }

    private Injector requireInjector() {
        Injector attached = injector;
        if (attached == null) {
            throw new IllegalStateException("There is no Injector attached to this Context.");
        }
        return attached;
    }

    /**
     * Marks this child context closed, rejecting further resolution. A child owns no scoped instances, so this never
     * destroys anything and never closes the parent. Idempotent.
     */
    @Override
    public void close() {
        closed = true;
    }
}
