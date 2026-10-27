package be.imgn.inject.context;

import be.imgn.inject.ConditionResolutionException;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Injector;
import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    private final Map<Key<?>, Provider<?>> registry;
    private final Map<Key<?>, BeanDefinition> definitions;
    private final List<BeanDefinition> allDefinitions;
    private final Map<Class<? extends Annotation>, InterceptorEntry> interceptors;
    private final Map<Class<? extends Annotation>, Scope> scopes;
    // The child-local scopes some child of this context has hosted, to tell a missing host from a missing Scope
    // service.
    private final Set<Class<? extends Annotation>> hostedScopes = ConcurrentHashMap.newKeySet();
    private final ResolutionStack stack = new ResolutionStack();
    private final Deque<TrackedInstance<?>> destroyables = new ArrayDeque<>();
    private @Nullable Injector injector;
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
        Map<Class<? extends Annotation>, InterceptorEntry> interceptorIndex = new HashMap<>();
        for (Factory<?> factory : factories) {
            if (!conditionsPass(factory, conditionsByAnnotation, conditionContext)) {
                // An unmatched condition removes the bean from the graph entirely
                continue;
            }
            Provider<?> provider = wrap(factory, scopesByAnnotation);
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
        requireSatisfied(definitionList, built.keySet());
        this.registry = Map.copyOf(built);
        this.definitions = Map.copyOf(definitionsByKey);
        this.allDefinitions = List.copyOf(definitionList);
        this.interceptors = Map.copyOf(interceptorIndex);
    }

    /**
     * Fails fast when a live bean depends on a key nothing binds, listing every such dependency at once. A bean's
     * dependencies are those of its injection points, except the ones a processor plugin supplies.
     */
    private static void requireSatisfied(List<BeanDefinition> beans, Set<Key<?>> bound) {
        List<String> missing = new ArrayList<>();
        for (BeanDefinition bean : beans) {
            for (InjectionPoint point : bean.injectionPoints()) {
                switch (point) {
                    case ConstructorInjectionPoint constructor ->
                        constructor
                                .parameters()
                                .forEach(parameter -> checkBound(
                                        bean, "constructor parameter " + parameter.name(), parameter, bound, missing));
                    case MethodInjectionPoint method ->
                        method.parameters()
                                .forEach(parameter -> checkBound(
                                        bean,
                                        "parameter " + parameter.name() + " of method " + method.methodName(),
                                        parameter,
                                        bound,
                                        missing));
                    case FieldInjectionPoint field -> {
                        if (!field.annotationMetadata().hasStereotype(InjectingAnnotation.class)
                                && !bound.contains(field.key())) {
                            missing.add(describe(bean, "field " + field.fieldName(), field.key()));
                        }
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new UnsatisfiedDependencyException("Unsatisfied dependencies:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), missing));
        }
    }

    private static void checkBound(
            BeanDefinition bean, String site, Parameter parameter, Set<Key<?>> bound, List<String> missing) {
        if (!parameter.annotationMetadata().hasStereotype(InjectingAnnotation.class)
                && !bound.contains(parameter.key())) {
            missing.add(describe(bean, site, parameter.key()));
        }
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
                synchronized (destroyables) {
                    destroyables.push(new TrackedInstance<>(factory, instance));
                }
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
     * Attaches the {@link Injector} this context resolves {@code Key.of(Injector.class)} to. Called once by the
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

    /** {@return the error for resolving {@code factory}'s child-local bean where no injector hosts its scope} */
    ScopeResolutionException noHost(Factory<?> factory, Class<? extends Annotation> scope) {
        String name = scope.getSimpleName();
        String message = "Cannot resolve " + factory.type().getName() + ": it is @" + name
                + ", and no injector in this chain hosts @" + name + ". Resolve it through an injector created with"
                + " scopedChildBuilder(" + name + ".class), or one of its descendants.";
        if (!hostedScopes.contains(scope)) {
            message += " No Scope service is registered for @" + name + " either; if it is not meant to live in a"
                    + " child injector, register one.";
        }
        return new ScopeResolutionException(message);
    }

    private Injector requireInjector() {
        Injector attached = injector;
        if (attached == null) {
            throw new IllegalStateException("There is no Injector attached to this Context.");
        }
        return attached;
    }

    /**
     * Destroys every tracked scoped instance (invoking each factory's {@code @PreDestroy}) in reverse creation order,
     * then rejects further resolution. Failures are collected as suppressed exceptions on the first one thrown, so
     * every instance is still given a chance to destroy. Idempotent.
     */
    // One failing @PreDestroy must not keep the remaining instances from being destroyed.
    @Override
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        synchronized (destroyables) {
            while (!destroyables.isEmpty()) {
                // LIFO: last created destroyed first
                TrackedInstance<?> tracked = destroyables.pop();
                try {
                    tracked.destroy();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
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

    /** A created scoped instance paired with the factory that can destroy it. */
    private record TrackedInstance<T>(Factory<T> factory, T instance) {
        void destroy() {
            factory.preDestroy(instance);
        }
    }

    /** One indexed interceptor: the factory that declared it and its fully wrapped provider. */
    private record InterceptorEntry(Factory<?> factory, Provider<MethodInterceptor> provider) {}
}
