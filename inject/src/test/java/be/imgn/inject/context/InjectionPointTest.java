package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Key;

class InjectionPointTest {
    @Test
    void constructorPointExposesItsKeys() {
        ConstructorInjectionPoint point =
                new ConstructorInjectionPoint(List.of(Key.of(String.class), Key.of(Integer.class)));
        assertThat(point.keys()).containsExactly(Key.of(String.class), Key.of(Integer.class));
        assertThat(ConstructorInjectionPoint.none().keys()).isEmpty();
    }

    @Test
    void fieldPointExposesSingletonKeyList() {
        FieldInjectionPoint point = new FieldInjectionPoint("logger", Key.of(String.class));
        assertThat(point)
                .returns("logger", FieldInjectionPoint::name)
                .returns(List.of(Key.of(String.class)), FieldInjectionPoint::keys);
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new FieldInjectionPoint("x", null));
    }
}
