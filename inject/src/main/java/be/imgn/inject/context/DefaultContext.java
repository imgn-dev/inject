package be.imgn.inject.context;

import be.imgn.inject.ConditionResolutionException;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.ConstructionListener;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Injector;
import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.Types;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link Context}: loads all {@code Factory} and {@code Scope} services, wraps each factory provider with a
 * cycle guard and its declared scope, checks that every live bean's dependencies are bound, and resolves keys lazily.
 * Fallback factories ({@code Factory#isDefault()}) bind only keys no other live factory claims. The registry is
 * immutable after construction; resolution is thread-safe.
 *
 * <p>A scoping annotation with no {@code Scope} service is child-local: the {@link ChildContext}s that host it cache
 * its instances, and resolving such a bean here fails with a {@link ScopeResolutionException}.
 *
 * <p>{@code new DefaultContext()} loads all registered {@code Factory}, {@code Scope}, and {@code Condition} services.
 * The injector that owns this context attaches itself via {@link #setInjector(Injector)} immediately after
 * construction, which is what lets {@code Key.of(Injector.class)} resolve.
 */
public final class DefaultContext implements Context {
    private static final Key<?> INJECTOR_KEY = Key.of(Injector.class);
    private static final Key<?> LISTENERS_KEY = Key.of(Types.setOf(ConstructionListener.class));

    private final Map<Key<?>, Provider<?>> registry;
    private final Map<Key<?>, BeanDefinition> definitions;
    private final List<BeanDefinition> allDefinitions;
    private final Map<Class<? extends Annotation>, InterceptorEntry> interceptors;
    private final Map<Class<? extends Annotation>, Scope> scopes;
    private final Map<Class<? extends Annotation>, List<HostRequirement>> hostRequirements;
    private final Map<Class<? extends Annotation>, List<HostScopeRequirement>> hostScopeRequirements;
    private final Map<Key<?>, ScopedProduct> products;
    // The child-local scopes some child of this context has hosted, to tell a missing host from a missing Scope
    // service.
    private final Set<Class<? extends Annotation>> hostedScopes = ConcurrentHashMap.newKeySet();
    private final ResolutionStack stack = new ResolutionStack();
    private final Destroyables destroyables = new Destroyables();
    private final List<ChildContext> children = new ArrayList<>();
    private @Nullable Injector injector;
    // Null until the listeners are resolved, so instances built meanwhile are not reported.
    private volatile @Nullable List<ConstructionListener> listeners;
    private volatile boolean closed;

    /** Creates a context loaded from all registered {@code Factory}, {@code Scope}, and {@code Condition} services. */
    public DefaultContext() {
        this(loadFactories(), loadScopes(), loadConditions(), systemProperties(), Map.of());
    }

    /**
     * Creates a context loaded from all registered services, with {@code overrides} shadowing discovered bindings.
     *
     * @param overrides providers that replace any discovered binding for the same key; primarily a test seam
     */
    public DefaultContext(Map<Key<?>, Provider<?>> overrides) {
        this(loadFactories(), loadScopes(), loadConditions(), systemProperties(), overrides);
    }

    DefaultContext(Iterable<Factory<?>> factories, Iterable<Scope> scopes) {
        this(factories, scopes, List.of(), name -> Optional.empty(), Map.of());
    }

    DefaultContext(Iterable<Factory<?>> factories, Iterable<Scope> scopes, Map<Key<?>, Provider<?>> overrides) {
        this(factories, scopes, List.of(), name -> Optional.empty(), overrides);
    }

    DefaultContext(
            Iterable<Factory<?>> factories,
            Iterable<Scope> scopes,
            Iterable<Condition> conditions,
            Map<String, String> properties) {
        this(factories, scopes, conditions, name -> Optional.ofNullable(properties.get(name)), Map.of());
    }

    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength"})
    DefaultContext(
            Iterable<Factory<?>> factories,
            Iterable<Scope> scopes,
            Iterable<Condition> conditions,
            ConditionContext conditionContext,
            Map<Key<?>, Provider<?>> overrides) {
        Map<Class<? extends Annotation>, Scope> scopesByAnnotation = indexScopes(scopes);
        this.scopes = scopesByAnnotation;
        Map<Class<? extends Annotation>, Condition> conditionsByAnnotation = indexConditions(conditions);

        Map<Key<?>, Provider<?>> built = new HashMap<>();
        Map<Key<?>, BeanDefinition> definitionsByKey = new HashMap<>();
        List<BeanDefinition> definitionList = new ArrayList<>();
        Map<Key<?>, List<Contributor>> contributions = new LinkedHashMap<>();
        List<PendingDefault> defaults = new ArrayList<>();
        List<ScopedProduct> products = new ArrayList<>();
        Map<Class<? extends Annotation>, InterceptorEntry> interceptorIndex = new HashMap<>();
        for (Factory<?> factory : factories) {
            if (!conditionsPass(factory, conditionsByAnnotation, conditionContext)) {
                // An unmatched condition removes the bean from the graph entirely
                continue;
            }
            Provider<?> provider = wrap(factory, scopesByAnnotation);
            products.addAll(factory.scopedProducts());
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
            Optional<Contribution> contribution = factory.contribution();
            if (contribution.isPresent()
                    && factory.scope().filter(this::isChildLocal).isPresent()) {
                throw new ScopeResolutionException(factory.type().getName() + " is @"
                        + factory.scope().get().getSimpleName() + " and contributes to "
                        + contribution.get().collectionKey() + ". A collection is resolved in the root injector,"
                        + " where no @" + factory.scope().get().getSimpleName() + " instance exists: remove the"
                        + " scope or the contribution.");
            }
            if (contribution.isPresent()) {
                contributions
                        .computeIfAbsent(contribution.get().collectionKey(), key -> new ArrayList<>())
                        .add(new Contributor(contribution.get(), provider));
                definitionList.add(factory);
                continue;
            }
            if (factory.isDefault()) {
                defaults.add(new PendingDefault(factory, provider));
                continue;
            }
            for (Key<?> key : factory.exposedKeys()) {
                if (built.putIfAbsent(key, provider) != null) {
                    throw new DuplicateBindingException("Duplicate binding for " + key);
                }
                definitionsByKey.put(key, factory);
            }
            definitionList.add(factory);
        }
        contributions.forEach((key, contributors) -> {
            if (built.putIfAbsent(key, aggregate(key, contributors)) != null) {
                throw new DuplicateBindingException("Duplicate binding for " + key);
            }
        });
        Map<Key<?>, ScopedProduct> productsByKey = new HashMap<>();
        for (ScopedProduct product : products) {
            registerProduct(product, productsByKey, built);
        }
        // Defaults join last: a default claims only keys no live binding took. Two live defaults for one key are
        // a configuration error even when a real binding wins that key.
        Set<Key<?>> defaultClaims = new HashSet<>();
        for (PendingDefault pending : defaults) {
            boolean winner = false;
            for (Key<?> key : pending.factory().exposedKeys()) {
                if (!defaultClaims.add(key)) {
                    throw new DuplicateBindingException("Duplicate default binding for " + key);
                }
                if (built.putIfAbsent(key, pending.provider()) == null) {
                    definitionsByKey.put(key, pending.factory());
                    winner = true;
                }
            }
            if (winner) {
                definitionList.add(pending.factory());
            }
        }
        // Overrides intentionally shadow discovered bindings (test seams); they win without a duplicate check. An
        // overridden key has no definition: the discovered factory no longer serves it, so a child injector must not
        // rebuild that factory in place of the override.
        built.putAll(overrides);
        definitionsByKey.keySet().removeAll(overrides.keySet());
        definitionList.removeIf(definition -> definition instanceof Factory<?> factory
                && factory.contribution().isEmpty()
                && overrides.keySet().containsAll(factory.exposedKeys()));
        // An override for an interceptor bean's own key also replaces its provider in the interceptor chain, so a
        // builder override reaches an interceptor invoked as part of a call, not just direct resolution.
        for (Map.Entry<Class<? extends Annotation>, InterceptorEntry> entry : interceptorIndex.entrySet()) {
            Provider<?> override =
                    overrides.get(Key.of(entry.getValue().factory().type()));
            if (override != null) {
                @SuppressWarnings("unchecked")
                Provider<MethodInterceptor> replaced = (Provider<MethodInterceptor>) override;
                entry.setValue(new InterceptorEntry(entry.getValue().factory(), replaced));
            }
        }
        // Built-in self-binding: resolves to the Injector attached via setInjector. Not a bean - it gets no
        // BeanDefinition and joins no stereotype query.
        built.put(INJECTOR_KEY, (Provider<Injector>) this::requireInjector);
        this.hostRequirements = Map.copyOf(requireSatisfied(definitionList, built.keySet()));
        this.hostScopeRequirements = Map.copyOf(requireNoWidening(definitionList, definitionsByKey, productsByKey));
        this.products = Map.copyOf(productsByKey);
        this.registry = Map.copyOf(built);
        this.definitions = Map.copyOf(definitionsByKey);
        this.allDefinitions = List.copyOf(definitionList);
        this.interceptors = Map.copyOf(interceptorIndex);
    }

    /**
     * Fails fast when a live bean depends on a key nothing binds, listing every such dependency at once. A bean's
     * dependencies are those of its injection points, except the ones a processor plugin supplies. A child-local bean's
     * dependency that nothing here binds must be bound by the children hosting its scope instead: it is returned, by
     * scope, for them to check when they are built.
     */
    private Map<Class<? extends Annotation>, List<HostRequirement>> requireSatisfied(
            List<BeanDefinition> beans, Set<Key<?>> bound) {
        List<String> missing = new ArrayList<>();
        Map<Class<? extends Annotation>, List<HostRequirement>> byScope = new HashMap<>();
        for (BeanDefinition bean : beans) {
            Optional<Class<? extends Annotation>> childLocal =
                    bean instanceof Factory<?> factory ? factory.scope().filter(this::isChildLocal) : Optional.empty();
            for (Dependency dependency : Dependency.of(bean)) {
                if (bound.contains(dependency.key())) {
                    continue;
                }
                if (childLocal.isPresent()) {
                    byScope.computeIfAbsent(childLocal.get(), unused -> new ArrayList<>())
                            .add(new HostRequirement(
                                    dependency.key(),
                                    "  " + bean.key() + " (@" + childLocal.get().getSimpleName() + ") depends on "
                                            + dependency.key() + " (" + dependency.site() + "), which neither this"
                                            + " injector nor its parents bind."));
                } else {
                    missing.add(describe(bean, dependency.site(), dependency.key()));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new UnsatisfiedDependencyException("Unsatisfied dependencies:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), missing));
        }
        return byScope;
    }

    /**
     * Fails fast when a bean of a scope with a {@code Scope} service depends directly, not through a {@code Provider},
     * on a child-local bean, listing every such dependency at once: such a bean is built here, where no child-local
     * instance exists. A child-local bean's direct dependency on a bean of another child-local scope needs a host of
     * that scope above its own host: it is returned, by scope, for the hosts to check when they are built.
     */
    private Map<Class<? extends Annotation>, List<HostScopeRequirement>> requireNoWidening(
            List<BeanDefinition> beans,
            Map<Key<?>, BeanDefinition> definitions,
            Map<Key<?>, ScopedProduct> productsByKey) {
        List<String> widening = new ArrayList<>();
        Map<Class<? extends Annotation>, List<HostScopeRequirement>> byScope = new HashMap<>();
        for (BeanDefinition bean : beans) {
            if (!(bean instanceof Factory<?> factory) || factory.scope().isEmpty()) {
                continue;
            }
            Class<? extends Annotation> scope = factory.scope().get();
            for (Dependency dependency : Dependency.of(bean)) {
                Optional<Class<? extends Annotation>> target = dependency.isProvider()
                        ? Optional.empty()
                        : childLocalScope(definitions.get(dependency.key()))
                                .or(() -> Optional.ofNullable(productsByKey.get(dependency.key()))
                                        .map(ScopedProduct::scope));
                if (target.isEmpty() || target.get().equals(scope)) {
                    continue;
                }
                String narrower = target.get().getSimpleName();
                if (!isChildLocal(scope)) {
                    widening.add("  " + bean.key() + " is @" + scope.getSimpleName() + " and depends directly on "
                            + dependency.key() + " (" + dependency.site() + "), which is @" + narrower + ". A @"
                            + scope.getSimpleName() + " bean is built in the root injector, where no @" + narrower
                            + " instance exists. Make it @" + narrower + " or unscoped.");
                } else {
                    byScope.computeIfAbsent(scope, unused -> new ArrayList<>())
                            .add(new HostScopeRequirement(
                                    target.get(),
                                    "  " + bean.key() + " (@" + scope.getSimpleName() + ") depends directly on "
                                            + dependency.key() + " (" + dependency.site() + "), which is @" + narrower
                                            + ", and no injector above this one hosts @" + narrower + ". Build this"
                                            + " injector below one that hosts @" + narrower + ", or make the bean @"
                                            + narrower + "."));
                }
            }
        }
        if (!widening.isEmpty()) {
            throw new ScopeResolutionException("Dependencies on narrower scopes:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), widening));
        }
        return byScope;
    }

    /**
     * Binds {@code product}'s key to a provider that fails: a product resolves only through the injector hosting it,
     * once created there.
     *
     * @throws ScopeResolutionException if the product's scope has a {@code Scope} service
     * @throws DuplicateBindingException if another binding claims its key
     */
    private void registerProduct(
            ScopedProduct product, Map<Key<?>, ScopedProduct> productsByKey, Map<Key<?>, Provider<?>> built) {
        if (!isChildLocal(product.scope())) {
            throw new ScopeResolutionException(product.key() + " is an assisted product and is @"
                    + product.scope().getSimpleName() + ". Only a child-local scope (one without a Scope service) can"
                    + " apply to an assisted product, since each factory call creates a new instance.");
        }
        ScopedProduct existing = productsByKey.putIfAbsent(product.key(), product);
        if (existing != null) {
            if (!existing.scope().equals(product.scope())) {
                throw new DuplicateBindingException("Duplicate binding for " + product.key() + ": "
                        + existing.creator() + " creates it @"
                        + existing.scope().getSimpleName() + ", and "
                        + product.creator() + " @" + product.scope().getSimpleName() + ".");
            }
            return;
        }
        Provider<?> unhosted = () -> {
            throw noHost(product.key().toString(), product.scope());
        };
        if (built.putIfAbsent(product.key(), unhosted) != null) {
            throw new DuplicateBindingException("Duplicate binding for " + product.key());
        }
    }

    /** {@return the child-local scope of {@code definition}'s bean, if it has one} */
    private Optional<Class<? extends Annotation>> childLocalScope(@Nullable BeanDefinition definition) {
        return definition instanceof Factory<?> factory ? factory.scope().filter(this::isChildLocal) : Optional.empty();
    }

    private static String describe(BeanDefinition bean, String site, Key<?> key) {
        return "  No binding for " + key + " (required by " + bean.key() + " " + site + ")";
    }

    private static Map<Class<? extends Annotation>, Scope> indexScopes(Iterable<Scope> scopes) {
        Map<Class<? extends Annotation>, Scope> byAnnotation = new HashMap<>();
        for (Scope scope : scopes) {
            Scope existing = byAnnotation.putIfAbsent(scope.annotationType(), scope);
            if (existing != null) {
                throw new ScopeResolutionException("Duplicate scope for @"
                        + scope.annotationType().getSimpleName() + ": "
                        + existing.getClass().getName() + " and "
                        + scope.getClass().getName());
            }
        }
        return byAnnotation;
    }

    private static Map<Class<? extends Annotation>, Condition> indexConditions(Iterable<Condition> conditions) {
        Map<Class<? extends Annotation>, Condition> byAnnotation = new HashMap<>();
        for (Condition condition : conditions) {
            Condition existing = byAnnotation.putIfAbsent(condition.annotationType(), condition);
            if (existing != null) {
                throw new ConditionResolutionException("Duplicate condition for @"
                        + condition.annotationType().getSimpleName() + ": "
                        + existing.getClass().getName() + " and "
                        + condition.getClass().getName());
            }
        }
        return byAnnotation;
    }

    private static boolean conditionsPass(
            Factory<?> factory,
            Map<Class<? extends Annotation>, Condition> conditions,
            ConditionContext conditionContext) {
        for (AnnotationValue annotation : factory.annotationMetadata().byStereotype(ConditioningAnnotation.class)) {
            Condition condition = conditions.get(annotation.annotationType());
            if (condition == null) {
                throw new ConditionResolutionException("No condition registered for @"
                        + annotation.annotationType().getSimpleName() + " on "
                        + factory.type().getName());
            }
            if (!condition.matches(conditionContext, annotation)) {
                return false;
            }
        }
        return true;
    }

    private static List<Factory<?>> loadFactories() {
        List<Factory<?>> factories = new ArrayList<>();
        for (Factory<?> factory : ServiceLoader.load(Factory.class)) {
            factories.add(factory);
        }
        return factories;
    }

    private static List<Scope> loadScopes() {
        List<Scope> scopes = new ArrayList<>();
        for (Scope scope : ServiceLoader.load(Scope.class)) {
            scopes.add(scope);
        }
        return scopes;
    }

    private static List<Condition> loadConditions() {
        List<Condition> conditions = new ArrayList<>();
        for (Condition condition : ServiceLoader.load(Condition.class)) {
            conditions.add(condition);
        }
        return conditions;
    }

    private static ConditionContext systemProperties() {
        return name -> Optional.ofNullable(System.getProperty(name));
    }

    private <T> Provider<T> wrap(Factory<T> factory, Map<Class<? extends Annotation>, Scope> scopes) {
        Key<T> primary = Key.of(factory.type());
        Provider<T> guarded = new CycleGuardProvider<>(primary, new FactoryProvider<>(this, factory), stack);
        Optional<Class<? extends Annotation>> scopeAnnotation = factory.scope();
        if (scopeAnnotation.isEmpty()) {
            return guarded;
        }
        Scope scope = scopes.get(scopeAnnotation.get());
        if (scope == null) {
            // Child-local: instances live in the child injectors hosting the scope, which never reach this provider.
            Class<? extends Annotation> childLocal = scopeAnnotation.get();
            return () -> {
                throw noHost(factory, childLocal);
            };
        }
        Provider<T> scoped = scope.wrap(guarded);
        if (!factory.hasPreDestroy()) {
            return scoped;
        }
        return tracking(factory, scoped);
    }

    /** {@return a provider that records the created instance once, for destruction at {@link #close}} */
    private <T> Provider<T> tracking(Factory<T> factory, Provider<T> scoped) {
        AtomicBoolean recorded = new AtomicBoolean();
        return () -> {
            T instance = scoped.get();
            if (recorded.compareAndSet(false, true)) {
                destroyables.track(factory, instance);
            }
            return instance;
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> getProvider(Key<T> key) {
        Objects.requireNonNull(key, "key");
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
        Provider<?> provider = registry.get(key);
        if (provider == null) {
            throw new UnsatisfiedDependencyException("No binding for " + key + stack.requiredBySuffix());
        }
        return (Provider<T>) provider;
    }

    @Override
    public Optional<BeanDefinition> getBeanDefinition(Key<?> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(definitions.get(key));
    }

    @Override
    public List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype) {
        Objects.requireNonNull(stereotype, "stereotype");
        return allDefinitions.stream()
                .filter(definition -> definition.annotationMetadata().hasStereotype(stereotype))
                .toList();
    }

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

    /**
     * Attaches the {@link Injector} this context resolves {@code Key.of(Injector.class)} to, then resolves the
     * {@link ConstructionListener}s. Called once by the injector that owns this context, immediately after
     * construction.
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
        Provider<?> contributed = registry.get(LISTENERS_KEY);
        // The listeners and their dependencies, built here, are not reported: no listener exists yet.
        this.listeners = contributed == null
                ? List.of()
                : ((Set<?>) contributed.get())
                        .stream().map(ConstructionListener.class::cast).toList();
    }

    @Override
    public <T> T constructed(T instance) {
        Objects.requireNonNull(instance, "instance");
        Injector attached = injector;
        if (attached != null) {
            report(instance, attached);
        }
        return instance;
    }

    /** Reports {@code instance}, which belongs to {@code owner}, to the listeners, once they are resolved. */
    void report(Object instance, Injector owner) {
        List<ConstructionListener> current = listeners;
        if (current != null) {
            for (ConstructionListener listener : current) {
                listener.constructed(instance, owner);
            }
        }
    }

    /**
     * {@return whether {@code scope} is child-local: a scoping annotation with no {@code Scope} service, whose
     * instances are cached by the child injectors hosting it}
     */
    boolean isChildLocal(Class<? extends Annotation> scope) {
        return !scopes.containsKey(scope);
    }

    /**
     * Records that a child of this context hosts {@code scope}.
     *
     * @throws IllegalArgumentException if a {@code Scope} service handles {@code scope}
     */
    void host(Class<? extends Annotation> scope) {
        Scope service = scopes.get(scope);
        if (service != null) {
            throw new IllegalArgumentException("@" + scope.getSimpleName() + " is handled by the Scope service "
                    + service.getClass().getName() + ", so it is not child-local: child injectors do not cache its"
                    + " instances. Use childBuilder() for a child injector that hosts no scope.");
        }
        hostedScopes.add(scope);
    }

    /**
     * {@return the dependencies of {@code scope}'s beans that nothing here binds, which a child hosting {@code scope}
     * must bind itself or find in its parents}
     */
    List<HostRequirement> hostRequirements(Class<? extends Annotation> scope) {
        return hostRequirements.getOrDefault(scope, List.of());
    }

    /**
     * {@return the child-local scopes {@code scope}'s beans depend on directly, which a child hosting {@code scope}
     * needs a host of above it}
     */
    List<HostScopeRequirement> hostScopeRequirements(Class<? extends Annotation> scope) {
        return hostScopeRequirements.getOrDefault(scope, List.of());
    }

    /** {@return the error for resolving {@code factory}'s child-local bean where no injector hosts its scope} */
    ScopeResolutionException noHost(Factory<?> factory, Class<? extends Annotation> scope) {
        return noHost(factory.key().toString(), scope);
    }

    /** {@return the error for resolving the child-local {@code bean} where no injector hosts its scope} */
    private ScopeResolutionException noHost(String bean, Class<? extends Annotation> scope) {
        String name = scope.getSimpleName();
        String message = "Cannot resolve " + bean + ": it is @" + name
                + ", and no injector in this chain hosts @" + name + ". Resolve it through an injector created with"
                + " scopedChildBuilder(" + name + ".class), or one of its descendants.";
        if (!hostedScopes.contains(scope)) {
            message += " No Scope service is registered for @" + name + " either; if it is not meant to live in a"
                    + " child injector, register one.";
        }
        return new ScopeResolutionException(message);
    }

    /** {@return the child-local assisted product resolvable by {@code key} once created, if any} */
    @Nullable
    ScopedProduct scopedProduct(Key<?> key) {
        return products.get(key);
    }

    /**
     * Rejects the call: the root never hosts a child-local scope.
     *
     * @throws ScopeResolutionException always
     */
    @Override
    public <T> T createScoped(
            ScopedProduct product,
            @Nullable Consumer<? super T> preDestroy,
            Function<? super Context, ? extends T> instance) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(instance, "instance");
        if (closed) {
            throw new IllegalStateException("This Injector has been closed.");
        }
        String name = product.scope().getSimpleName();
        throw new ScopeResolutionException("Cannot create " + product.key() + " with " + product.creator() + "(...): it"
                + " is @" + name + ", and no injector in this chain hosts @" + name + ". Call it through an injector"
                + " created with scopedChildBuilder(" + name + ".class), or one of its descendants.");
    }

    private Injector requireInjector() {
        Injector attached = injector;
        if (attached == null) {
            throw new IllegalStateException("There is no Injector attached to this Context.");
        }
        return attached;
    }

    /**
     * Closes the live child contexts, newest first, then destroys every tracked scoped instance (invoking each
     * factory's {@code @PreDestroy}) in reverse creation order, and rejects further resolution. Failures are collected
     * as suppressed exceptions on the first one thrown, so every child and every instance is still given a chance to
     * close or destroy. Idempotent.
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
        Destroyables.runAll(actions);
    }

    /**
     * Registers a child context created over this one, to close when this one closes.
     *
     * @throws IllegalStateException if this context is closed
     */
    void addChild(ChildContext child) {
        synchronized (children) {
            if (closed) {
                throw new IllegalStateException("This injector is closed: no child injector can be built under it.");
            }
            children.add(child);
        }
    }

    /** Forgets a child context that closed. */
    void removeChild(ChildContext child) {
        synchronized (children) {
            children.remove(child);
        }
    }

    /** {@return the aggregate provider for one collection key: a set provider, or a map provider when keys exist} */
    private static Provider<?> aggregate(Key<?> collectionKey, List<Contributor> contributors) {
        if (contributors.get(0).contribution().mapKey() == null) {
            List<Provider<?>> providers =
                    contributors.stream().map(Contributor::provider).toList();
            return () -> providers.stream().map(Provider::get).collect(Collectors.toUnmodifiableSet());
        }
        Map<String, Provider<?>> byKey = new LinkedHashMap<>();
        for (Contributor contributor : contributors) {
            String mapKey = Objects.requireNonNull(contributor.contribution().mapKey());
            if (byKey.putIfAbsent(mapKey, contributor.provider()) != null) {
                throw new DuplicateBindingException("Duplicate map key \"" + mapKey + "\" for " + collectionKey);
            }
        }
        return () -> byKey.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    /** One collection member: its contribution and its fully wrapped (scoped, cycle-guarded) provider. */
    private record Contributor(Contribution contribution, Provider<?> provider) {}

    /** One buffered default binding: its factory and its fully wrapped (scoped, cycle-guarded) provider. */
    private record PendingDefault(Factory<?> factory, Provider<?> provider) {}

    /** One indexed interceptor: the factory that declared it and its fully wrapped provider. */
    private record InterceptorEntry(Factory<?> factory, Provider<MethodInterceptor> provider) {}
}
