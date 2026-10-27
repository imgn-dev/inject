package be.imgn.inject.context;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A child {@link Context}: nearest-ancestor local bindings first (walking up through any intermediate
 * {@code ChildContext}s), then the parent. Unscoped parent beans are rebuilt at this — the deepest — context so their
 * dependencies see the full child binding stack. A bean of a child-local scope (one with no {@code Scope} service) is
 * built once by the nearest context of the chain that hosts its scope, this one included, which caches it; its
 * dependencies resolve through that host. Other scoped beans and everything without a {@link BeanDefinition} anchor to
 * the parent unchanged.
 */
public final class ChildContext implements Context {

    private static final Key<?> INJECTOR_KEY = Key.of(Injector.class);

    private final Context parent;
    private final Map<Key<?>, Provider<?>> bindings;
    private final @Nullable Class<? extends Annotation> hostedScope;
    // The instances of the hosted scope, one memoizing provider per factory, so a bean's exposed keys share it.
    private final Map<Factory<?>, Provider<?>> hosted = new ConcurrentHashMap<>();
    private final ResolutionStack stack = new ResolutionStack();
    private final Destroyables destroyables = new Destroyables();
    private final List<ChildContext> children = new ArrayList<>();
    private @Nullable Injector injector;
    private volatile boolean closed;

    /**
     * Creates a child context over {@code parent}.
     *
     * @param parent the parent context resolutions fall back to
     * @param bindings the child's own bindings, by key
     */
    public ChildContext(Context parent, Map<Key<?>, Provider<?>> bindings) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
        this.hostedScope = null;
        register();
    }

    /**
     * Creates a child context over {@code parent} that hosts the child-local {@code scope}: it builds and caches the
     * instances of that scope's beans resolved through it or its descendants.
     *
     * @param parent the parent context resolutions fall back to
     * @param bindings the child's own bindings, by key
     * @param scope the child-local scope this context hosts
     * @throws IllegalArgumentException if {@code scope} is handled by a {@code Scope} service, or if the root of the
     *     chain is not a {@link DefaultContext}
     * @throws UnsatisfiedDependencyException if a bean of {@code scope} depends on a key that neither the root, nor
     *     this context, nor one of its parents binds; the message lists every such dependency
     */
    public ChildContext(Context parent, Map<Key<?>, Provider<?>> bindings, Class<? extends Annotation> scope) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
        this.hostedScope = Objects.requireNonNull(scope, "scope");
        if (!(root() instanceof DefaultContext root)) {
            throw new IllegalArgumentException("Only a child of an injector created by Injector.create() or"
                    + " Injector.builder() can host a scope.");
        }
        root.host(scope);
        List<String> missing = root.hostRequirements(scope).stream()
                .filter(requirement -> localLookup(requirement.key()) == null)
                .map(HostRequirement::description)
                .toList();
        if (!missing.isEmpty()) {
            throw new UnsatisfiedDependencyException("Cannot build the @" + scope.getSimpleName() + " injector:"
                    + System.lineSeparator() + String.join(System.lineSeparator(), missing) + System.lineSeparator()
                    + "Bind each on this injector's builder, or on one of its parents.");
        }
        register();
    }

    /** Registers this context with its parent, which closes it when it closes. */
    private void register() {
        if (parent instanceof ChildContext child) {
            child.addChild(this);
        } else if (parent instanceof DefaultContext root) {
            root.addChild(this);
        }
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
        requireOpen(key);
        Provider<?> provider;
        if (INJECTOR_KEY.equals(key)) {
            provider = (Provider<Injector>) this::requireInjector;
        } else {
            Provider<?> layered = localLookup(key);
            Provider<?> built = layered != null ? layered : buildingProvider(key);
            provider = built != null ? built : parent.getProvider(key);
        }
        // A provider obtained through this context fails once it closes, wherever its instances come from.
        return () -> {
            requireOpen(key);
            return (T) provider.get();
        };
    }

    private void requireOpen(Key<?> key) {
        if (closed) {
            throw new IllegalStateException(
                    "This " + (hostedScope == null ? "" : "@" + hostedScope.getSimpleName() + " ")
                            + "injector is closed: " + key + " can no longer be resolved through it.");
        }
    }

    /**
     * {@return the provider building the bean bound to {@code key} through this chain, or {@code null} when the parent
     * provides it unchanged}
     */
    private @Nullable Provider<?> buildingProvider(Key<?> key) {
        Optional<BeanDefinition> definition = parent.getBeanDefinition(key);
        if (definition.isEmpty() || !(definition.get() instanceof Factory<?> factory)) {
            return null;
        }
        Optional<Class<? extends Annotation>> scope = factory.scope();
        if (scope.isEmpty()) {
            // Unscoped parent bean: rebuild here so its dependencies resolve through this child.
            return new CycleGuardProvider<>(key, () -> factory.build(this), stack);
        }
        ChildContext host = host(scope.get());
        return host != null ? host.hosted(factory) : null;
    }

    /** {@return the nearest context of the chain, this one included, that hosts {@code scope}, or {@code null}} */
    private @Nullable ChildContext host(Class<? extends Annotation> scope) {
        if (scope.equals(hostedScope)) {
            return this;
        }
        return parent instanceof ChildContext child ? child.host(scope) : null;
    }

    /**
     * {@return the provider of this host's instance of {@code factory}'s bean, built through this context once and
     * destroyed when it closes}
     */
    private Provider<?> hosted(Factory<?> factory) {
        return hosted.computeIfAbsent(factory, unused -> new SingletonProvider<>(hostedBuilder(factory)));
    }

    private <T> Provider<T> hostedBuilder(Factory<T> factory) {
        return new CycleGuardProvider<>(
                factory.key(),
                () -> {
                    T instance = factory.build(this);
                    if (factory.hasPreDestroy()) {
                        destroyables.track(factory, instance);
                    }
                    return instance;
                },
                stack);
    }

    /** {@return the context at the top of the chain: the first ancestor that is not a {@code ChildContext}} */
    private Context root() {
        return parent instanceof ChildContext child ? child.root() : parent;
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
     * Closes this child context: rejects further resolution through it, including through the providers it returned,
     * closes its live child contexts, newest first, then destroys the instances of its hosted scope (invoking each
     * factory's {@code @PreDestroy}) in reverse creation order. Failures are collected as suppressed exceptions on the
     * first one thrown, so every child and every instance is still given a chance to close or destroy. Never closes the
     * parent. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Runnable> actions = new ArrayList<>();
        synchronized (children) {
            children.reversed().forEach(child -> actions.add(child::close));
        }
        actions.addAll(destroyables.drain());
        try {
            Destroyables.runAll(actions);
        } finally {
            if (parent instanceof ChildContext child) {
                child.removeChild(this);
            } else if (parent instanceof DefaultContext root) {
                root.removeChild(this);
            }
        }
    }

    private void addChild(ChildContext child) {
        synchronized (children) {
            children.add(child);
        }
    }

    private void removeChild(ChildContext child) {
        synchronized (children) {
            children.remove(child);
        }
    }
}
