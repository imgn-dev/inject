package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Key;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.fixtures.LeafFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BeanDefinitionIntrospectionTest {
    private static final AnnotationValue SCOPED =
            AnnotationValue.of(Singleton.class, Map.of(), Set.of(ScopingAnnotation.class));

    private Context context() {
        return new DefaultContext(List.of(new WidgetFactory(), new LeafFactory()), List.of());
    }

    @Test
    void resolvesDefinitionByKey() {
        assertThat(context().getBeanDefinition(Key.of(Widget.class)))
                .hasValueSatisfying(definition -> assertThat(definition)
                        .returns(Key.of(Widget.class), BeanDefinition::key)
                        .extracting(BeanDefinition::annotationMetadata)
                        .returns(true, m -> m.isPresent(Singleton.class)));
    }

    @Test
    void exposesInjectionPoints() {
        assertThat(context().getBeanDefinition(Key.of(Widget.class)))
                .hasValueSatisfying(definition -> assertThat(definition.injectionPoints())
                        .singleElement()
                        .isInstanceOf(FieldInjectionPoint.class));
    }

    @Test
    void unknownKeyHasNoDefinition() {
        assertThat(context().getBeanDefinition(Key.of(Long.class))).isEmpty();
    }

    @Test
    void queriesDefinitionsByStereotype() {
        Context context = context();
        assertThat(context.getBeanDefinitionsByStereotype(ScopingAnnotation.class))
                .singleElement()
                .returns(Key.of(Widget.class), BeanDefinition::key);
        assertThat(context.getBeanDefinitionsByStereotype(InjectingAnnotation.class))
                .isEmpty();
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNullArguments() {
        Context context = context();
        assertThatNullPointerException().isThrownBy(() -> context.getBeanDefinition(null));
        assertThatNullPointerException().isThrownBy(() -> context.getBeanDefinitionsByStereotype(null));
    }

    /** A bean carrying definition-level metadata and one field injection point. */
    static final class Widget {}

    static final class WidgetFactory extends Factory<Widget> {
        WidgetFactory() {
            super(Widget.class);
        }

        @Override
        protected Widget createInstance(Context context) {
            return new Widget();
        }

        @Override
        public AnnotationMetadata annotationMetadata() {
            return AnnotationMetadata.of(SCOPED);
        }

        @Override
        protected List<FieldInjectionPoint> fieldInjectionPoints() {
            return List.of(new FieldInjectionPoint(this, "name", String.class, AnnotationMetadata.empty()));
        }
    }
}
