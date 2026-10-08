package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.inject.ConditionResolutionException;
import be.imgn.inject.ConditionalOnProperty;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.Key;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionalBeanTest {
    private static AnnotationValue onProperty(String name, String havingValue, boolean matchIfMissing) {
        return AnnotationValue.of(
                ConditionalOnProperty.class,
                Map.of("value", name, "havingValue", havingValue, "matchIfMissing", matchIfMissing),
                Set.of(ConditioningAnnotation.class));
    }

    private Context context(AnnotationValue condition, Map<String, String> properties) {
        return new DefaultContext(
                List.of(new GatedFactory(condition)), List.of(), List.of(new OnPropertyCondition()), properties);
    }

    @Test
    void registersBeanWhenPropertyHasTheRequiredValue() {
        Context context = context(onProperty("feature.x", "on", false), Map.of("feature.x", "on"));
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("gated");
    }

    @Test
    void withholdsBeanWhenPropertyValueDiffers() {
        Context context = context(onProperty("feature.x", "on", false), Map.of("feature.x", "off"));
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(String.class)).get());
        assertThat(context.getBeanDefinition(Key.of(String.class))).isEmpty();
    }

    @Test
    void withholdsBeanWhenPropertyMissingAndNoMatchIfMissing() {
        Context context = context(onProperty("feature.x", "", false), Map.of());
        assertThat(context.getBeanDefinition(Key.of(String.class))).isEmpty();
    }

    @Test
    void registersBeanWhenPropertyMissingButMatchIfMissing() {
        Context context = context(onProperty("feature.x", "", true), Map.of());
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("gated");
    }

    @Test
    void registersBeanWhenPropertyPresentWithAnyValueAndNoRequiredValue() {
        Context context = context(onProperty("feature.x", "", false), Map.of("feature.x", "whatever"));
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("gated");
    }

    @Test
    void unhandledConditioningAnnotationIsAnError() {
        AnnotationValue unknown = AnnotationValue.of(
                ConditionalOnProperty.class, Map.of("value", "x"), Set.of(ConditioningAnnotation.class));
        assertThatExceptionOfType(ConditionResolutionException.class)
                .isThrownBy(
                        () -> new DefaultContext(List.of(new GatedFactory(unknown)), List.of(), List.of(), Map.of()))
                .withMessageContaining("ConditionalOnProperty");
    }

    @Test
    void twoConditionsForOneAnnotationAreAnError() {
        AnnotationValue condition = onProperty("feature.x", "on", false);
        List<Condition> conditions = List.of(new OnPropertyCondition(), new AlwaysMatchingCondition());
        assertThatExceptionOfType(ConditionResolutionException.class)
                .isThrownBy(
                        () -> new DefaultContext(List.of(new GatedFactory(condition)), List.of(), conditions, Map.of()))
                .withMessageContaining("Duplicate condition for @ConditionalOnProperty")
                .withMessageContaining(OnPropertyCondition.class.getName())
                .withMessageContaining(AlwaysMatchingCondition.class.getName());
    }

    /** A factory for {@code String} carrying a definition-level conditioning annotation. */
    static final class GatedFactory extends Factory<String> {
        private final AnnotationMetadata metadata;

        GatedFactory(AnnotationValue condition) {
            super(String.class);
            this.metadata = AnnotationMetadata.of(condition);
        }

        @Override
        protected String createInstance(Context context) {
            return "gated";
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return metadata;
        }
    }

    /** A second condition claiming {@code @ConditionalOnProperty}, as a stray jar on the classpath could. */
    static final class AlwaysMatchingCondition implements Condition {
        @Override
        public Class<? extends Annotation> annotationType() {
            return ConditionalOnProperty.class;
        }

        @Override
        public boolean matches(ConditionContext context, AnnotationValue annotation) {
            return true;
        }
    }
}
