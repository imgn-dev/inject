package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;
import be.imgn.inject.Named;
import be.imgn.inject.Names;

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
    void fieldPointBuildsKeyFromTypeAndInstanceQualifier() {
        Named db = Names.named("db");
        FieldInjectionPoint qualified =
                new FieldInjectionPoint(FACTORY, "conn", String.class, db, AnnotationMetadata.empty());
        assertThat(qualified)
                .returns("conn", FieldInjectionPoint::fieldName)
                .returns(false, FieldInjectionPoint::isProvider)
                .extracting(FieldInjectionPoint::key)
                .isEqualTo(Key.of(String.class, db));
    }

    @Test
    void fieldPointBuildsKeyFromMarkerQualifier() {
        FieldInjectionPoint qualified =
                new FieldInjectionPoint(FACTORY, "conn", String.class, Named.class, AnnotationMetadata.empty());
        assertThat(qualified.key()).isEqualTo(Key.of(String.class, Named.class));
    }

    @Test
    void fieldPointConvenienceCtorHasNoQualifier() {
        FieldInjectionPoint point =
                new FieldInjectionPoint(FACTORY, "logger", String.class, AnnotationMetadata.empty());
        assertThat(point.hasQualifier()).isFalse();
        assertThat(point.key()).isEqualTo(Key.of(String.class));
    }

    @Test
    void parameterQualifierPresence() {
        assertThat(new Parameter("x", String.class).hasQualifier()).isFalse();
        assertThat(new Parameter("x", String.class, Names.named("v")).hasQualifier())
                .isTrue();
        assertThat(new Parameter("x", String.class, Named.class).hasQualifier()).isTrue();
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNullFactory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FieldInjectionPoint(null, "x", String.class, AnnotationMetadata.empty()));
    }
}
