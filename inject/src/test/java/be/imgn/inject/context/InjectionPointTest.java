package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Named;
import be.imgn.inject.Qualifier;

class InjectionPointTest {
    private static final Factory<Object> FACTORY = new Factory<>(Object.class) {
        @Override
        protected Object createInstance(Context context) {
            return new Object();
        }
    };

    @Test
    void constructorPointKeysForParameters() {
        ConstructorInjectionPoint point = new ConstructorInjectionPoint(
                FACTORY,
                List.of(new Parameter("a", String.class), new Parameter("b", Integer.class)),
                AnnotationMetadata.empty());
        assertThat(point)
                .returns(FACTORY, ConstructorInjectionPoint::factory)
                .extracting(p -> List.of(p.keyForParameter(0), p.keyForParameter(1)))
                .isEqualTo(List.of(Key.of(String.class), Key.of(Integer.class)));
    }

    @Test
    void fieldPointBuildsKeyFromTypeAndQualifier() {
        Qualifier named = new Qualifier(Named.class, Map.of("value", "db"));
        FieldInjectionPoint qualified =
                new FieldInjectionPoint(FACTORY, "conn", String.class, named, AnnotationMetadata.empty());
        assertThat(qualified)
                .returns("conn", FieldInjectionPoint::fieldName)
                .returns(false, FieldInjectionPoint::isProvider)
                .extracting(FieldInjectionPoint::key)
                .isEqualTo(Key.of(String.class, named));
    }

    @Test
    void fieldPointConvenienceCtorHasNoQualifier() {
        FieldInjectionPoint point =
                new FieldInjectionPoint(FACTORY, "logger", String.class, AnnotationMetadata.empty());
        assertThat(point.qualifier()).isNull();
        assertThat(point.key()).isEqualTo(Key.of(String.class));
    }

    @Test
    void parameterQualifierPresence() {
        Qualifier named = new Qualifier(Named.class, Map.of("value", "v"));
        assertThat(new Parameter("x", String.class).hasQualifier()).isFalse();
        assertThat(new Parameter("x", String.class, named).hasQualifier()).isTrue();
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNullFactory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FieldInjectionPoint(null, "x", String.class, AnnotationMetadata.empty()));
    }
}
