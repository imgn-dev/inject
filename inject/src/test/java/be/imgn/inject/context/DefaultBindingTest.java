package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Singleton;
import be.imgn.inject.UnsatisfiedDependencyException;

class DefaultBindingTest {

    /** A conditional factory for {@code String}, the "real" binding a default competes with. */
    static final class GatedFactory extends Factory<String> {
        private final AnnotationMetadata metadata;

        GatedFactory(AnnotationValue condition) {
            super(String.class);
            this.metadata = AnnotationMetadata.of(condition);
        }

        @Override
        protected String createInstance(Context context) {
            return "real";
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return metadata;
        }
    }

    /** A default factory for {@code String}, optionally scoped and optionally conditional. */
    static final class FallbackFactory extends Factory<String> {
        private final AnnotationMetadata metadata;
        private final Optional<Class<? extends Annotation>> scope;
        private int built;

        FallbackFactory() {
            this(AnnotationMetadata.empty(), Optional.empty());
        }

        FallbackFactory(AnnotationMetadata metadata, Optional<Class<? extends Annotation>> scope) {
            super(String.class);
            this.metadata = metadata;
            this.scope = scope;
        }

        @Override
        protected String createInstance(Context context) {
            built++;
            return "fallback-" + built;
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return metadata;
        }

        @Override
        protected Optional<Class<? extends Annotation>> scope() {
            return scope;
        }

        @Override
        protected boolean isDefault() {
            return true;
        }
    }

    /** A default factory exposing two keys, competing with a real binding that claims only one of them. */
    static final class MultiKeyFallbackFactory extends Factory<String> {
        MultiKeyFallbackFactory() {
            super(String.class);
        }

        @Override
        protected String createInstance(Context context) {
            return "fallback";
        }

        @Override
        protected List<Key<?>> exposedKeys() {
            return List.of(Key.of(String.class), Key.of(CharSequence.class));
        }

        @Override
        protected boolean isDefault() {
            return true;
        }
    }

    private static AnnotationValue onProperty(String name, String havingValue) {
        return AnnotationValue.of(
                ConditionalOnProperty.class,
                Map.of("value", name, "havingValue", havingValue, "matchIfMissing", false),
                Set.of(ConditioningAnnotation.class));
    }

    private static Context context(Map<String, String> properties, Factory<?>... factories) {
        return new DefaultContext(
                List.of(factories), List.of(new SingletonScope()), List.of(new OnPropertyCondition()), properties);
    }

    @Test
    void factoryIsNotDefaultByDefault() {
        assertThat(new GatedFactory(onProperty("x", "")).isDefault()).isFalse();
    }

    @Test
    void defaultLosesToALiveBinding() {
        Context context = context(
                Map.of("feature.x", "on"), new GatedFactory(onProperty("feature.x", "on")), new FallbackFactory());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("real");
    }

    @Test
    void defaultWinsWhenTheConditionFails() {
        Context context = context(Map.of(), new GatedFactory(onProperty("feature.x", "on")), new FallbackFactory());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("fallback-1");
    }

    @Test
    void defaultWinsWhenNoOtherBindingExists() {
        Context context = context(Map.of(), new FallbackFactory());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("fallback-1");
    }

    @Test
    void duplicateLiveDefaultsThrowEvenWhenARealBindingWins() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> context(
                        Map.of("feature.x", "on"),
                        new GatedFactory(onProperty("feature.x", "on")),
                        new FallbackFactory(),
                        new FallbackFactory()))
                .withMessageContaining("Duplicate default binding");
    }

    @Test
    void conditionalDefaultOutOfTheGraphWhenItsConditionFails() {
        Context context = context(
                Map.of(), new FallbackFactory(AnnotationMetadata.of(onProperty("fallback.x", "on")), Optional.empty()));
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)));
        assertThat(context.getBeanDefinition(Key.of(String.class))).isEmpty();
    }

    @Test
    void scopedDefaultIsWrappedInItsScope() {
        Context context =
                context(Map.of(), new FallbackFactory(AnnotationMetadata.empty(), Optional.of(Singleton.class)));
        assertThat(context.getProvider(Key.of(String.class)).get())
                .isEqualTo("fallback-1")
                .isEqualTo(context.getProvider(Key.of(String.class)).get());
    }

    @Test
    void winningDefaultIsTheBeanDefinition() {
        FallbackFactory fallback = new FallbackFactory();
        Context context = context(Map.of(), fallback);
        assertThat(context.getBeanDefinition(Key.of(String.class))).containsSame(fallback);
    }

    @Test
    void discardedDefaultContributesNoBeanDefinition() {
        GatedFactory real = new GatedFactory(onProperty("feature.x", "on"));
        Context context = context(Map.of("feature.x", "on"), real, new FallbackFactory());
        assertThat(context.getBeanDefinition(Key.of(String.class))).containsSame(real);
    }

    @Test
    void multiKeyDefaultWinsOnlyTheKeyTheRealBindingDoesNotClaim() {
        GatedFactory real = new GatedFactory(onProperty("feature.x", "on"));
        MultiKeyFallbackFactory fallback = new MultiKeyFallbackFactory();
        Context context = context(Map.of("feature.x", "on"), real, fallback);

        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("real");
        assertThat(context.getProvider(Key.of(CharSequence.class)).get()).isEqualTo("fallback");
        assertThat(context.getBeanDefinition(Key.of(String.class))).containsSame(real);
        assertThat(context.getBeanDefinition(Key.of(CharSequence.class))).containsSame(fallback);
    }

    @Test
    void duplicateLiveDefaultsThrowWithNoRealBinding() {
        assertThatExceptionOfType(DuplicateBindingException.class)
                .isThrownBy(() -> context(Map.of(), new FallbackFactory(), new FallbackFactory()))
                .withMessageContaining("Duplicate default binding");
    }
}
