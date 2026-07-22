package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.Types;
import be.imgn.inject.UnsatisfiedDependencyException;

class MultibindingTest {

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

    private static final Key<?> SET_KEY = Key.of(Types.setOf(CharSequence.class));
    private static final Key<?> MAP_KEY = Key.of(Types.mapOf(String.class, CharSequence.class));

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
        Context context = new DefaultContext(List.of(intoSet("a")), List.of());
        Provider<?> provider = context.getProvider(SET_KEY);
        assertThat((Set<CharSequence>) provider.get()).isEqualTo(provider.get());
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
}
