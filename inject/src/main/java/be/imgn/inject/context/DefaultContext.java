package be.imgn.inject.context;

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
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.ConditionResolutionException;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.UnsatisfiedDependencyException;

/**
 * Default {@link Context}: loads all {@code Factory} and {@code Scope} services, wraps each factory provider with a
 * cycle guard and its declared scope, and resolves keys lazily. Fallback factories ({@code Factory#isDefault()}) bind
 * only keys no other live factory claims. The registry is immutable after construction; resolution is thread-safe.
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
    private final ThreadLocal<Deque<Key<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);
    private @Nullable Injector injector;

    /** {@return a context loaded from all registered {@code Factory}, {@code Scope}, and {@code Condition} services} */
    public DefaultContext() {
        this(loadFactories(), loadScopes(), loadConditions(), systemProperties(), Map.of());
    }

    /**
     * {@return a context loaded from all registered services, with {@code overrides} shadowing discovered bindings}
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

    DefaultContext(
            Iterable<Factory<?>> factories,
            Iterable<Scope> scopes,
            Iterable<Condition> conditions,
            ConditionContext conditionContext,
            Map<Key<?>, Provider<?>> overrides) {
        Map<Class<? extends Annotation>, Scope> scopesByAnnotation = new HashMap<>();
        for (Scope scope : scopes) {
            scopesByAnnotation.put(scope.annotationType(), scope);
        }
        Map<Class<? extends Annotation>, Condition> conditionsByAnnotation = new HashMap<>();
        for (Condition condition : conditions) {
            conditionsByAnnotation.put(condition.annotationType(), condition);
        }

        Map<Key<?>, Provider<?>> built = new HashMap<>();
        Map<Key<?>, BeanDefinition> definitionsByKey = new HashMap<>();
        List<BeanDefinition> definitionList = new ArrayList<>();
        Map<Key<?>, List<Contributor>> contributions = new LinkedHashMap<>();
        List<PendingDefault> defaults = new ArrayList<>();
        for (Factory<?> factory : factories) {
            if (!conditionsPass(factory, conditionsByAnnotation, conditionContext)) {
                continue; // an unmatched condition removes the bean from the graph entirely
            }
            Provider<?> provider = wrap(factory, scopesByAnnotation);
            Optional<Contribution> contribution = factory.contribution();
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
        // Overrides intentionally shadow discovered bindings (test seams); they win without a duplicate check.
        built.putAll(overrides);
        // Built-in self-binding: resolves to the Injector attached via setInjector. Not a bean - it gets no
        // BeanDefinition and joins no stereotype query.
        built.put(INJECTOR_KEY, (Provider<Injector>) this::requireInjector);
        this.registry = Map.copyOf(built);
        this.definitions = Map.copyOf(definitionsByKey);
        this.allDefinitions = List.copyOf(definitionList);
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
        Provider<T> guarded = new CycleGuardProvider<>(primary, new FactoryProvider<>(this, factory), this);
        Optional<Class<? extends Annotation>> scopeAnnotation = factory.scope();
        if (scopeAnnotation.isEmpty()) {
            return guarded;
        }
        Scope scope = scopes.get(scopeAnnotation.get());
        if (scope == null) {
            throw new ScopeResolutionException(
                    "No scope registered for @" + scopeAnnotation.get().getSimpleName());
        }
        return scope.wrap(guarded);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> getProvider(Key<T> key) {
        Objects.requireNonNull(key, "key");
        Provider<?> provider = registry.get(key);
        if (provider == null) {
            throw new UnsatisfiedDependencyException("No binding for " + key + requiredBySuffix());
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

    private Injector requireInjector() {
        Injector attached = injector;
        if (attached == null) {
            throw new IllegalStateException("There is no Injector attached to this Context.");
        }
        return attached;
    }

    private String requiredBySuffix() {
        Deque<Key<?>> current = stack.get();
        if (current.isEmpty()) {
            return "";
        }
        StringJoiner path = new StringJoiner(" -> ");
        current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
        return " (required by " + path + ")";
    }

    void enter(Key<?> key) {
        Deque<Key<?>> current = stack.get();
        if (current.contains(key)) {
            StringJoiner path = new StringJoiner(" -> ");
            current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
            path.add(key.toString());
            throw new CircularDependencyException("Dependency cycle: " + path);
        }
        current.push(key);
    }

    void exit(Key<?> key) {
        stack.get().pop();
    }

    /** One collection member: its contribution and its fully wrapped (scoped, cycle-guarded) provider. */
    private record Contributor(Contribution contribution, Provider<?> provider) {}

    /** One buffered default binding: its factory and its fully wrapped (scoped, cycle-guarded) provider. */
    private record PendingDefault(Factory<?> factory, Provider<?> provider) {}

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
}
