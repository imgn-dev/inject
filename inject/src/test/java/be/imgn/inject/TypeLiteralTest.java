package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeLiteralTest {
    @Test
    void capturesGenericTypeArgument() {
        TypeLiteral<List<String>> literal = new TypeLiteral<>() {};
        assertThat(literal)
                .isEqualTo(new TypeLiteral<List<String>>() {})
                .isNotEqualTo(new TypeLiteral<List<Integer>>() {});
        assertThat(literal.getType()).isInstanceOf(ParameterizedType.class);
    }

    @Test
    void ofWrapsRawClass() {
        assertThat(TypeLiteral.of(String.class).getType()).isEqualTo(String.class);
    }

    @Test
    @SuppressWarnings("NullAway")
    void ofRejectsNull() {
        Class<String> nullType = null;
        assertThatNullPointerException().isThrownBy(() -> TypeLiteral.of(nullType));
    }
}
