package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.Singleton;
import be.imgn.inject.Types;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MultibindingTest {
    private static final Key<?> SET_KEY = Key.of(Types.setOf(CharSequence.class));
    private static final Key<?> MAP_KEY = Key.of(Types.mapOf(String.class, CharSequence.class));
    private static final Key<?> OBJECT_SET_KEY = Key.of(Types.setOf(Object.class));

    private static ContribFactory intoSet(String value) {
        return new ContribFactory(value, new Contribution(SET_KEY, null));
    }

    private static ContribFactory intoMap(String value, String key) {
        return new ContribFactory(value, new Contribution(MAP_KEY, key));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesSetContributions() {
        Context context = new DefaultContext(List.of(intoSet("a"), intoSet("b")), List.of());
        Set<CharSequence> set = (Set<CharSequence>) context.getProvider(SET_KEY).get();
        assertThat(set).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(set::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesMapContributions() {
        Context context = new DefaultContext(List.of(intoMap("a", "first"), intoMap("b", "second")), List.of());
        Map<String, CharSequence> map =
                (Map<String, CharSequence>) context.getProvider(MAP_KEY).get();
        assertThat(map).containsOnlyKeys("first", "second").containsEntry("first", "a");
    }

    @Test
    void contributorsExposeNoIndividualKeys() {
        Context context = new DefaultContext(List.of(intoSet("a")), List.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)).get());
    }

    @Test
    void absentCollectionIsAMissingBinding() {
        Context context = new DefaultContext(List.of(), List.of());
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(SET_KEY).get());
    }

    @Test
    void duplicateMapKeyFailsAtBuild() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> new DefaultContext(List.of(intoMap("a", "same"), intoMap("b", "same")), List.of()))
                .withMessageContaining("same");
    }

    @Test
    void directBindingOfTheCollectionTypeCollides() {
        // Mirrors what the generator emits for a @Provides Set<...> method with no @ExposeAs, qualifier, or
        // contribution: exposedKeys() overridden to the single parameterized collection key.
        Factory<Object> direct = new Factory<>(Object.class) {
            @Override
            protected Object createInstance(Context context) {
                return Set.of();
            }

            @Override
            protected List<Key<?>> exposedKeys() {
                return List.of(SET_KEY);
            }
        };
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> new DefaultContext(List.of(direct, intoSet("a")), List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prototypeContributorsAreFreshPerGet() {
        Context context = new DefaultContext(
                List.of(new ObjectContribFactory(new Contribution(OBJECT_SET_KEY, null), Optional.empty())), List.of());
        Provider<?> provider = context.getProvider(OBJECT_SET_KEY);
        Set<Object> first = (Set<Object>) provider.get();
        Set<Object> second = (Set<Object>) provider.get();
        assertThat(first.iterator().next()).isNotSameAs(second.iterator().next());
    }

    @Test
    @SuppressWarnings("unchecked")
    void singletonContributorsAreSharedAcrossGets() {
        Context context = new DefaultContext(
                List.of(new ObjectContribFactory(new Contribution(OBJECT_SET_KEY, null), Optional.of(Singleton.class))),
                List.of(new SingletonScope()));
        Provider<?> provider = context.getProvider(OBJECT_SET_KEY);
        Set<Object> first = (Set<Object>) provider.get();
        Set<Object> second = (Set<Object>) provider.get();
        assertThat(first.iterator().next()).isSameAs(second.iterator().next());
    }

    @Test
    void collectionKeyHasNoBeanDefinition() {
        Context context = new DefaultContext(List.of(intoSet("a")), List.of());
        assertThat(context.getBeanDefinition(SET_KEY)).isEmpty();
    }

    @Test
    void conditionFilteredContributorShrinksTheSet() {
        AnnotationValue off = AnnotationValue.of(
                ConditionalOnProperty.class,
                Map.of("value", "flag", "havingValue", "on", "matchIfMissing", false),
                Set.of(ConditioningAnnotation.class));
        Factory<String> gated = new ContribFactory("gated", new Contribution(SET_KEY, null)) {
            @Override
            public AnnotationMetadata annotationMetadata() {
                return AnnotationMetadata.of(off);
            }
        };
        Context context = new DefaultContext(
                List.of(intoSet("kept"), gated), List.of(), List.of(new OnPropertyCondition()), Map.of());
        @SuppressWarnings("unchecked")
        Set<CharSequence> set = (Set<CharSequence>) context.getProvider(SET_KEY).get();
        assertThat(set).containsExactly("kept");
    }

    /** A contributing factory: exposes nothing, joins the CharSequence collection. */
    static class ContribFactory extends Factory<String> {
        private final String value;
        private final Contribution contribution;

        ContribFactory(String value, Contribution contribution) {
            super(String.class);
            this.value = value;
            this.contribution = contribution;
        }

        @Override
        protected String createInstance(Context context) {
            return value;
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of();
        }

        @Override
        protected Optional<Contribution> contribution() {
            return Optional.of(contribution);
        }
    }

    /**
     * A contributing factory producing a fresh {@link Object} per {@code createInstance} call, with a configurable
     * scope.
     */
    static class ObjectContribFactory extends Factory<Object> {
        private final Contribution contribution;
        private final Optional<Class<? extends Annotation>> scope;

        ObjectContribFactory(Contribution contribution, Optional<Class<? extends Annotation>> scope) {
            super(Object.class);
            this.contribution = contribution;
            this.scope = scope;
        }

        @Override
        protected Object createInstance(Context context) {
            return new Object();
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of();
        }

        @Override
        protected Optional<Contribution> contribution() {
            return Optional.of(contribution);
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return scope;
        }
    }
}
