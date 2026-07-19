package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Type;
import java.util.List;

import org.junit.jupiter.api.Test;

class KeyTest {
    @Test
    void unqualifiedKeyHasTypeAndNoQualifier() {
        Key<String> key = Key.of(String.class);
        assertThat(key)
                .returns(String.class, Key::type)
                .returns(false, Key::hasQualifier)
                .returns(null, Key::annotation)
                .returns(null, Key::annotationType);
    }

    @Test
    void instanceQualifiedKeysDifferByAttributeValue() {
        Named db = Names.named("db");
        Named cache = Names.named("cache");
        assertThat(Key.of(String.class, db))
                .returns(true, Key::hasQualifier)
                .returns(Named.class, Key::annotationType)
                .isEqualTo(Key.of(String.class, Names.named("db")))
                .isNotEqualTo(Key.of(String.class, cache))
                .isNotEqualTo(Key.of(String.class));
    }

    @Test
    void markerQualifiedKeysMatchByTypeAndDifferFromInstance() {
        assertThat(Key.of(String.class, Named.class))
                .returns(true, Key::hasQualifier)
                .returns(null, Key::annotation)
                .isEqualTo(Key.of(String.class, Named.class))
                .isNotEqualTo(Key.of(String.class))
                .isNotEqualTo(Key.of(String.class, Names.named("db")));
    }

    @Test
    void typeLiteralKeyCapturesGenerics() {
        Key<List<String>> key = Key.of(new TypeLiteral<List<String>>() {});
        assertThat(key.type()).isEqualTo(new TypeLiteral<List<String>>() {}.getType());
    }

    @Test
    void typeOverloadEqualsClassOverload() {
        Type stringType = String.class;
        assertThat(Key.of(stringType)).isEqualTo(Key.of(String.class));
    }

    @Test
    @SuppressWarnings("NullAway")
    void ofRejectsNullType() {
        Class<Object> nullType = null;
        assertThatNullPointerException().isThrownBy(() -> Key.of(nullType));
    }
}
