package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TypesTest {
    @Test
    void setOfEqualsJdkCapturedTypeBothDirections() {
        Type ours = Types.setOf(String.class);
        Type jdk = new TypeLiteral<Set<String>>() {}.getType();
        assertThat(ours).isEqualTo(jdk).hasSameHashCodeAs(jdk);
        assertThat(jdk).isEqualTo(ours);
    }

    @Test
    void mapOfEqualsJdkCapturedTypeBothDirections() {
        Type ours = Types.mapOf(String.class, Integer.class);
        Type jdk = new TypeLiteral<Map<String, Integer>>() {}.getType();
        assertThat(ours).isEqualTo(jdk).hasSameHashCodeAs(jdk);
        assertThat(jdk).isEqualTo(ours);
    }

    @Test
    void keysBuiltFromEitherSideAreEqual() {
        assertThat(Key.of(Types.setOf(String.class)))
                .isEqualTo(Key.of(new TypeLiteral<Set<String>>() {}))
                .isNotEqualTo(Key.of(Types.setOf(Integer.class)));
    }

    @Test
    void distinctTypesAreNotEqual() {
        assertThat(Types.setOf(String.class))
                .isNotEqualTo(Types.setOf(Integer.class))
                .isNotEqualTo(new TypeLiteral<List<String>>() {}.getType());
    }

    @Test
    void hasReadableToString() {
        assertThat(Types.setOf(String.class).getTypeName()).contains("Set").contains("String");
    }

    @Test
    @SuppressWarnings("NullAway")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> Types.setOf(null));
        assertThatNullPointerException().isThrownBy(() -> Types.mapOf(null, String.class));
        assertThatNullPointerException().isThrownBy(() -> Types.mapOf(String.class, null));
    }
}
